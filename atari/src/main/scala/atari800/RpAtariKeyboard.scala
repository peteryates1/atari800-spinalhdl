package atari800

import spinal.core._
import spinal.lib._

// RP2040 supervisor -> Atari keyboard bridge.
//
// The RP2040 runs a USB HID host (TinyUSB + PIO-USB) and forwards RAW 8-byte
// HID boot-keyboard reports over its hardware SPI (GPIO16-19 = the dedicated
// rp_sck/rp_mosi/rp_miso/rp_csn link). All HID->Atari mapping stays in the
// FPGA and reuses the proven Ch376UsbKeyboard logic (AtariHidMap + the same
// KEYBOARD_RESPONSE generation), per the all-SpinalHDL principle.
//
// SPI protocol (mode 0, MSB first, one frame per CS-low window):
//   byte 0 = command
//     0x4B 'K': bytes 1..8 = HID boot report (mods, reserved, key1..key6);
//               committed atomically at CS rise.
//     0x43 'C': byte 1 = control bits — 0 reset, 1 start, 2 select, 3 option,
//               4 halt (6502 HALT while the supervisor loads/verifies SDRAM)
//               (held levels; cleared by the next 'C' frame).
//     0x57 'W': bytes 1..3 = SDRAM byte address (23:0, 4-aligned); bytes 4..N
//               stream data. Each 4-byte quad is written to SDRAM (32-bit,
//               little-endian: first byte at lowest address) via the arbiter
//               loader port while the frame streams. Trailing partial quads
//               are dropped (ROM images are 4-byte multiples).
//     0x5A 'Z': zero the load byte-counter and checksum.
//     0x56 'V': bytes 1..3 = SDRAM byte address, bytes 4..6 = length; on CS
//               rise the FPGA reads length bytes through the loader port
//               (byte mode - the CPU's exact view) summing them; vBusy and
//               the 16-bit content sum appear in the status stream. Immune
//               to MISO pacing: reads are clocked by SDRAM completes.
//     0x52 'R': bytes 1..3 = SDRAM byte address; every further byte clocked
//               returns (on MISO, 2-byte pipeline delay) successive SDRAM
//               bytes read through the same port/byte-access mode the CPU
//               uses - the ground-truth view of memory.
//   MISO status stream: 0xA5, frameCount, ldCnt.lo, ldCnt.hi, sum.lo, sum.hi
//   (sum = 16-bit sum of all data bytes streamed since 'Z' — lets the
//   supervisor verify a load without a read channel).
class RpAtariKeyboard extends Component {
  val io = new Bundle {
    // SPI slave (async inputs from the RP2040)
    val spiSck  = in  Bool()
    val spiMosi = in  Bool()
    val spiCsN  = in  Bool()
    val spiMiso = out Bool()
    // Atari keyboard matrix interface
    val keyboardScan     = in  Bits(6 bits)
    val keyboardResponse = out Bits(2 bits)
    // Console keys from the keyboard (F5/F6/F7), active-high "pressed"
    val consolStart  = out Bool()
    val consolSelect = out Bool()
    val consolOption = out Bool()
    // Supervisor control overrides (held levels from the 'C' frame)
    val ctrlReset  = out Bool()
    val ctrlStart  = out Bool()
    val ctrlSelect = out Bool()
    val ctrlOption = out Bool()
    val ctrlHalt   = out Bool()
    val supDisplay = out Bool()          // ctrl bit 5: show the RP2040 supervisor framebuffer on HDMI
    // SDRAM loader port (arbiter port D; complete idles low, pulses high)
    val ldReq      = out Bool()
    val ldAddr     = out Bits(24 bits)
    val ldData     = out Bits(32 bits)
    val ldWrite    = out Bool()          // high: write (W); low: read (R)
    val ldRdData   = in  Bits(32 bits)   // arbiter port D dataOut
    val ldComplete = in  Bool()
    val ldDest     = out Bits(2 bits)     // 0=SDRAM(port D), 1=BRAM OS-ROM, 2=BRAM RAM
    // debug
    val frameCount = out UInt(8 bits)
    // capture-window offset write (SPI 'G' command). The holding registers
    // live in a BOOT-reset domain in the top (survive the Atari reset, which
    // resets this component); offsetWr pulses when a new pair arrives.
    val offsetH    = out UInt(9 bits)
    val offsetV    = out UInt(9 bits)
    val offsetWr   = out Bool()
    // cartridge select (SPI 'X' command). Holding register lives in the same
    // BOOT-reset cfgArea so it survives the Atari reset; cartWr pulses on set.
    val cartSel    = out Bits(6 bits)
    val cartWr     = out Bool()
    // capture pixel-sample phase (SPI 'P' command): which of the 8 sys/8 sub-
    // phases samples VIDEO_B. Held in the BOOT-reset cfgArea so it survives reset.
    val pixPhase   = out UInt(3 bits)
    val pixPhaseWr = out Bool()
    val meterLate  = in UInt(16 bits)   // fb-read late-line count (status 10,11)
    val meterDrop  = in UInt(16 bits)   // fb-write dropped-quad count (12,13)
    // captured-content bounding box (status 14..21): the live picture's
    // extent inside the framebuffer, for centring the capture window
    val bbMinX     = in UInt(9 bits)
    val bbMaxX     = in UInt(9 bits)
    val bbMinY     = in UInt(9 bits)
    val bbMaxY     = in UInt(9 bits)
    val aMaxWait   = in UInt(10 bits)   // worst port-A SDRAM stall (status 22,23)
    // SIO bridge register access (SPI 'Q'=write, 'S'=read). Drives the SioBridge
    // bus in the top; sioRd/sioWr are one-cycle pulses. The 16-bit read result
    // is latched and streamed back in the MISO status block (bytes 24,25).
    val sioAddr    = out UInt(4 bits)
    val sioRd      = out Bool()
    val sioWr      = out Bool()
    val sioWrData  = out Bits(16 bits)
    val sioRdData  = in  Bits(32 bits)
  }

  // ---- Input synchronisers (SPI clock << sys clock; 3-stage) ----
  val sck  = BufferCC(io.spiSck,  False, bufferDepth = 3)
  val mosi = BufferCC(io.spiMosi, False, bufferDepth = 3)
  val csN  = BufferCC(io.spiCsN,  True,  bufferDepth = 3)

  val sckPrev = RegNext(sck) init False
  val csPrev  = RegNext(csN) init True
  val sckRise = sck && !sckPrev
  val sckFall = !sck && sckPrev
  val csFall  = !csN && csPrev
  val csRise  = csN && !csPrev

  // ---- Byte deserialiser ----
  val bitCnt   = Reg(UInt(3 bits)) init 0
  val shiftIn  = Reg(Bits(8 bits)) init 0
  val byteIdx  = Reg(UInt(12 bits)) init 0   // wide: 'W' frames stream hundreds of bytes
  val cmdReg   = Reg(Bits(8 bits)) init 0
  val byteDone = False
  val byteVal  = shiftIn(6 downto 0) ## mosi

  when(csFall) { bitCnt := 0; byteIdx := 0 }
  when(!csN && sckRise) {
    shiftIn := byteVal
    bitCnt  := bitCnt + 1
    when(bitCnt === 7) { byteDone := True }
  }

  // ---- SDRAM loader state ----
  // W bytes are queued as (address, data) pairs: the SPI stream never stalls,
  // and a starving arbiter port D (lowest priority, under live video traffic)
  // just deepens the queue instead of silently clobbering a single buffer -
  // the failure mode that corrupted every ROM load until the 'V' content
  // check caught it. 512 deep = ~4 ms of absorbed starvation at 1 MHz SPI.
  val wq = StreamFifo(Bits(32 bits), 512)
  val pushPtr  = Reg(UInt(24 bits)) init 0        // W push-side address
  val wqOvf    = RegInit(False)                   // sticky: a push was ever lost
  wq.io.push.valid   := False
  wq.io.push.payload := pushPtr.asBits ## B(0, 8 bits)
  val ldPtr    = Reg(UInt(24 bits)) init 0        // R/V drain-side byte address
  val wrValid  = Reg(Bool()) init False
  val ldSum    = Reg(UInt(16 bits)) init 0
  val ldCnt    = Reg(UInt(16 bits)) init 0
  val ldIsRead = Reg(Bool()) init False
  val ldDestReg = Reg(Bits(2 bits)) init 0        // 'B': load destination
  io.ldDest := ldDestReg
  val rdByte   = Reg(Bits(8 bits)) init 0
  val drainCnt = Reg(UInt(16 bits)) init 0        // completed queue writes
  val vBusy    = Reg(Bool()) init False
  val vLen     = Reg(UInt(24 bits)) init 0
  val vSum     = Reg(UInt(16 bits)) init 0

  // ---- SIO bridge register access ('Q' write / 'S' read) ----
  // Declared before the MISO block, which streams sioRdLatch in bytes 24,25.
  val sioAddrReg   = Reg(UInt(4 bits)) init 0
  val sioWrDataReg = Reg(Bits(16 bits)) init 0
  val sioRdReg     = Reg(Bool()) init False; sioRdReg := False   // one-cycle pulse
  val sioWrReg     = Reg(Bool()) init False; sioWrReg := False   // one-cycle pulse
  val sioRdLatch   = Reg(Bits(16 bits)) init 0

  // ---- MISO status stream (slave shifts on falling edge, mode 0) ----
  val frameCnt = Reg(UInt(8 bits)) init 0
  val shiftOut = Reg(Bits(8 bits)) init 0
  val outIdx   = Reg(UInt(5 bits)) init 0
  when(csFall) { shiftOut := B(0xA5, 8 bits); outIdx := 0 }
  when(!csN && sckFall) {
    when(bitCnt === 0) {   // byte boundary: load the next status byte whole
      when(cmdReg === 0x52) {
        shiftOut := rdByte                 // 'R': stream SDRAM bytes
      } otherwise {
        switch(outIdx) {
          is(0) { shiftOut := frameCnt.asBits }
          is(1) { shiftOut := ldCnt(7 downto 0).asBits }
          is(2) { shiftOut := ldCnt(15 downto 8).asBits }
          is(3) { shiftOut := ldSum(7 downto 0).asBits }
          is(4) { shiftOut := ldSum(15 downto 8).asBits }
          is(5)  { shiftOut := vSum(7 downto 0).asBits }
          is(6)  { shiftOut := vSum(15 downto 8).asBits }
          is(7)  { shiftOut := B(0, 6 bits) ## wqOvf ## vBusy }
          is(8)  { shiftOut := drainCnt(7 downto 0).asBits }
          is(9)  { shiftOut := drainCnt(15 downto 8).asBits }
          is(10) { shiftOut := io.meterLate(7 downto 0).asBits }
          is(11) { shiftOut := io.meterLate(15 downto 8).asBits }
          is(12) { shiftOut := io.meterDrop(7 downto 0).asBits }
          is(13) { shiftOut := io.meterDrop(15 downto 8).asBits }
          is(14) { shiftOut := io.bbMinX(7 downto 0).asBits }
          is(15) { shiftOut := (B(0, 7 bits) ## io.bbMinX(8)) }
          is(16) { shiftOut := io.bbMaxX(7 downto 0).asBits }
          is(17) { shiftOut := (B(0, 7 bits) ## io.bbMaxX(8)) }
          is(18) { shiftOut := io.bbMinY(7 downto 0).asBits }
          is(19) { shiftOut := (B(0, 7 bits) ## io.bbMinY(8)) }
          is(20) { shiftOut := io.bbMaxY(7 downto 0).asBits }
          is(21) { shiftOut := (B(0, 7 bits) ## io.bbMaxY(8)) }
          is(22) { shiftOut := io.aMaxWait(7 downto 0).asBits }
          is(23) { shiftOut := (B(0, 6 bits) ## io.aMaxWait(9 downto 8)) }
          is(24) { shiftOut := sioRdLatch(7 downto 0) }    // SIO read result lo
          is(25) { shiftOut := sioRdLatch(15 downto 8) }   // SIO read result hi
          default { shiftOut := B(0, 8 bits) }
        }
      }
      outIdx := outIdx + 1
    } otherwise {
      shiftOut := shiftOut |<< 1
    }
  }
  io.spiMiso := shiftOut.msb

  // ---- Key state (same structure as Ch376UsbKeyboard) ----
  val atariKeys    = Reg(Bits(64 bits)) init 0
  val shiftPressed = Reg(Bool()) init False
  val ctrlPressed  = Reg(Bool()) init False
  val breakKey     = Reg(Bool()) init False
  val startKey     = Reg(Bool()) init False
  val selectKey    = Reg(Bool()) init False
  val optionKey    = Reg(Bool()) init False

  val nextKeys   = Reg(Bits(64 bits)) init 0
  val nextShift  = Reg(Bool()) init False
  val nextCtrl   = Reg(Bool()) init False
  val nextBreak  = Reg(Bool()) init False
  val nextStart  = Reg(Bool()) init False
  val nextSelect = Reg(Bool()) init False
  val nextOption = Reg(Bool()) init False

  val ctrlBits = Reg(Bits(6 bits)) init 0
  val hStartReg   = Reg(UInt(9 bits)) init 0   // transient carriers across the frame
  val vSkipReg    = Reg(UInt(9 bits)) init 0
  val offsetWrReg = Reg(Bool()) init False
  offsetWrReg := False
  val cartSelReg  = Reg(Bits(6 bits)) init 0
  val cartWrReg   = Reg(Bool()) init False
  cartWrReg := False
  val pixPhaseReg = Reg(UInt(3 bits)) init 7
  val pixPhaseWrReg = Reg(Bool()) init False
  pixPhaseWrReg := False

  val hidMap = Mem(Bits(7 bits), AtariHidMap.table.map(v => B(v, 7 bits)))

  when(byteDone) {
    when(byteIdx === 0) {
      cmdReg := byteVal
      when(byteVal === 0x5A) { ldSum := 0; ldCnt := 0; drainCnt := 0 }   // 'Z'
    } otherwise {
      switch(cmdReg) {
        is(B(0x4B, 8 bits)) {              // 'K': HID boot report
          when(byteIdx === 1) {            // modifiers
            nextShift  := byteVal(1) | byteVal(5)
            nextCtrl   := byteVal(0) | byteVal(4)
            nextKeys   := B(0, 64 bits)
            nextStart  := False
            nextSelect := False
            nextOption := False
            nextBreak  := False
          }
          when(byteIdx >= 3 && byteIdx <= 8) {   // key codes 1..6
            val keyCode = byteVal.asUInt
            when(keyCode === 0x3E) { nextStart  := True }
            when(keyCode === 0x3F) { nextSelect := True }
            when(keyCode === 0x40) { nextOption := True }
            when(keyCode === 0x48) { nextBreak  := True }
            val mapped = hidMap.readAsync(keyCode.resize(7))
            when(mapped(6)) {
              nextKeys(mapped(5 downto 0).asUInt) := True
            }
          }
        }
        is(B(0x47, 8 bits)) {              // 'G': set capture-window offset
          when(byteIdx === 1) { hStartReg := byteVal.asUInt.resize(9) }
          when(byteIdx === 2) { vSkipReg  := byteVal.asUInt.resize(9); offsetWrReg := True }
        }
        is(B(0x58, 8 bits)) {              // 'X': set cartridge select (CartLogic mode)
          when(byteIdx === 1) { cartSelReg := byteVal(5 downto 0); cartWrReg := True }
        }
        is(B(0x50, 8 bits)) {              // 'P': set capture pixel-sample phase
          when(byteIdx === 1) { pixPhaseReg := byteVal(2 downto 0).asUInt; pixPhaseWrReg := True }
        }
        is(B(0x43, 8 bits)) {              // 'C': control bits
          when(byteIdx === 1) { ctrlBits := byteVal(5 downto 0) }
        }
        is(B(0x52, 8 bits)) {              // 'R': SDRAM read-back
          when(byteIdx === 1) { ldPtr(23 downto 16) := byteVal.asUInt; ldIsRead := True }
          when(byteIdx === 2) { ldPtr(15 downto 8)  := byteVal.asUInt }
          when(byteIdx >= 3)  {
            when(byteIdx === 3) { ldPtr(7 downto 0) := byteVal.asUInt }
            wrValid := True                // issue a READ at ldPtr per byte
          }
        }
        is(B(0x56, 8 bits)) {              // 'V': content checksum
          when(byteIdx === 1) { ldPtr(23 downto 16) := byteVal.asUInt; ldIsRead := True }
          when(byteIdx === 2) { ldPtr(15 downto 8)  := byteVal.asUInt }
          when(byteIdx === 3) { ldPtr(7 downto 0)   := byteVal.asUInt }
          when(byteIdx === 4) { vLen(23 downto 16)  := byteVal.asUInt }
          when(byteIdx === 5) { vLen(15 downto 8)   := byteVal.asUInt }
          when(byteIdx === 6) { vLen(7 downto 0)    := byteVal.asUInt }
        }
        is(B(0x42, 8 bits)) {              // 'B': set load destination (0 SDRAM, 1 BRAM-ROM, 2 BRAM-RAM)
          when(byteIdx === 1) { ldDestReg := byteVal(1 downto 0) }
        }
        is(B(0x57, 8 bits)) {              // 'W': load stream to current destination
          when(byteIdx === 1) { pushPtr(23 downto 16) := byteVal.asUInt }
          when(byteIdx === 2) { pushPtr(15 downto 8)  := byteVal.asUInt }
          when(byteIdx === 3) { pushPtr(7 downto 0)   := byteVal.asUInt }
          when(byteIdx >= 4) {
            ldSum := ldSum + byteVal.asUInt.resize(16)
            ldCnt := ldCnt + 1
            wq.io.push.valid   := True
            wq.io.push.payload := pushPtr.asBits ## byteVal
            when(!wq.io.push.ready) { wqOvf := True }
            pushPtr := pushPtr + 1
          }
        }
        is(B(0x51, 8 bits)) {              // 'Q': SIO register write (addr, dataLo, dataHi)
          when(byteIdx === 1) { sioAddrReg := byteVal(3 downto 0).asUInt }
          when(byteIdx === 2) { sioWrDataReg(7 downto 0)  := byteVal }
          when(byteIdx === 3) { sioWrDataReg(15 downto 8) := byteVal; sioWrReg := True }
        }
        is(B(0x53, 8 bits)) {              // 'S': SIO register read (addr) -> sioRdLatch
          when(byteIdx === 1) { sioAddrReg := byteVal(3 downto 0).asUInt; sioRdReg := True }
        }
      }
    }
    when(byteIdx =/= byteIdx.maxValue) { byteIdx := byteIdx + 1 }
  }

  // ---- Loader drain: writes from the queue; V/R reads via ldPtr ----
  val ldInFlight = Reg(Bool()) init False
  val ldBusySeen = Reg(Bool()) init False
  val servingWq  = Reg(Bool()) init False
  val vGo = vBusy && !wq.io.pop.valid            // reads wait out queued writes
  io.ldReq := (wq.io.pop.valid || wrValid || vGo) && !ldInFlight
  io.ldAddr := ldPtr.asBits
  io.ldData := B(0, 32 bits)   // overridden from the queue on pop
  wq.io.pop.ready := False
  when(wq.io.pop.valid) {
    io.ldAddr := wq.io.pop.payload(31 downto 8)
    io.ldData := wq.io.pop.payload(7 downto 0) #* 4
  }
  when(io.ldReq) { ldInFlight := True; ldBusySeen := False; servingWq := wq.io.pop.valid }
  when(ldInFlight) {
    when(!io.ldComplete) { ldBusySeen := True }
    when(ldBusySeen && io.ldComplete) {
      ldInFlight := False
      when(servingWq) {
        wq.io.pop.ready := True                  // consume the entry we just wrote
        drainCnt := drainCnt + 1
      } otherwise {
        wrValid := False
        ldPtr   := ldPtr + 1
        when(ldIsRead) { rdByte := io.ldRdData(7 downto 0) }
        when(vBusy) {
          vSum := vSum + io.ldRdData(7 downto 0).asUInt.resize(16)
          vLen := vLen - 1
          when(vLen === 1) { vBusy := False }
        }
      }
    }
  }
  io.ldWrite := servingWq || !ldIsRead

  // start the verify sweep once the whole V frame has arrived
  when(csRise && cmdReg === 0x56 && byteIdx >= 7 && vLen =/= 0) {
    vBusy := True
    vSum  := 0
  }

  // commit a complete keyboard frame atomically at CS rise
  when(csRise && cmdReg === 0x4B && byteIdx >= 9) {
    atariKeys    := nextKeys
    shiftPressed := nextShift
    ctrlPressed  := nextCtrl
    breakKey     := nextBreak
    startKey     := nextStart
    selectKey    := nextSelect
    optionKey    := nextOption
    frameCnt     := frameCnt + 1
  }

  io.consolStart  := startKey
  io.consolSelect := selectKey
  io.consolOption := optionKey
  io.ctrlReset    := ctrlBits(0)
  io.ctrlStart    := ctrlBits(1)
  io.ctrlSelect   := ctrlBits(2)
  io.ctrlOption   := ctrlBits(3)
  io.ctrlHalt     := ctrlBits(4)
  io.supDisplay   := ctrlBits(5)
  io.offsetH      := hStartReg
  io.offsetV      := vSkipReg
  io.offsetWr     := offsetWrReg
  io.cartSel      := cartSelReg
  io.cartWr       := cartWrReg
  io.pixPhase     := pixPhaseReg
  io.pixPhaseWr   := pixPhaseWrReg
  io.frameCount   := frameCnt

  // SIO bus outputs + read capture. sioRd pulses the cycle after the 'S'
  // address byte; SioBridge's rdData (and its rxReadLatch on FIFO reads) is
  // valid one cycle later, so capture on RegNext(sioRd).
  io.sioAddr   := sioAddrReg
  io.sioRd     := sioRdReg
  io.sioWr     := sioWrReg
  io.sioWrData := sioWrDataReg
  when(RegNext(sioRdReg) init False) { sioRdLatch := io.sioRdData(15 downto 0) }

  // ---- KEYBOARD_RESPONSE generation (matches Ch376UsbKeyboard) ----
  io.keyboardResponse := B"11"
  val scanIdx = (~io.keyboardScan).asUInt
  when(atariKeys(scanIdx)) {
    io.keyboardResponse(0) := False
  }
  when(io.keyboardScan(5 downto 4) === B"00" && breakKey) {
    io.keyboardResponse(1) := False
  }
  when(io.keyboardScan(5 downto 4) === B"10" && shiftPressed) {
    io.keyboardResponse(1) := False
  }
  when(io.keyboardScan(5 downto 4) === B"11" && ctrlPressed) {
    io.keyboardResponse(1) := False
  }
}
