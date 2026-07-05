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
    // SDRAM loader port (arbiter port D; complete idles low, pulses high)
    val ldReq      = out Bool()
    val ldAddr     = out Bits(24 bits)
    val ldData     = out Bits(32 bits)
    val ldWrite    = out Bool()          // high: write (W); low: read (R)
    val ldRdData   = in  Bits(32 bits)   // arbiter port D dataOut
    val ldComplete = in  Bool()
    // debug
    val frameCount = out UInt(8 bits)
    val meterLate  = in UInt(16 bits)   // fb-read late-line count (status 10,11)
    val meterDrop  = in UInt(16 bits)   // fb-write dropped-quad count (12,13)
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
  val quadBuf  = Reg(Bits(24 bits)) init 0        // bytes 0..2 of the quad
  val wrData   = Reg(Bits(32 bits)) init 0
  val wrValid  = Reg(Bool()) init False
  val ldSum    = Reg(UInt(16 bits)) init 0
  val ldCnt    = Reg(UInt(16 bits)) init 0
  val ldIsRead = Reg(Bool()) init False
  val rdByte   = Reg(Bits(8 bits)) init 0
  val drainCnt = Reg(UInt(16 bits)) init 0        // completed queue writes
  val vBusy    = Reg(Bool()) init False
  val vLen     = Reg(UInt(24 bits)) init 0
  val vSum     = Reg(UInt(16 bits)) init 0

  // ---- MISO status stream (slave shifts on falling edge, mode 0) ----
  val frameCnt = Reg(UInt(8 bits)) init 0
  val shiftOut = Reg(Bits(8 bits)) init 0
  val outIdx   = Reg(UInt(4 bits)) init 0
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

  val ctrlBits = Reg(Bits(5 bits)) init 0

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
        is(B(0x43, 8 bits)) {              // 'C': control bits
          when(byteIdx === 1) { ctrlBits := byteVal(4 downto 0) }
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
        is(B(0x57, 8 bits)) {              // 'W': SDRAM load
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
  io.ldData := wrData
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
  io.frameCount   := frameCnt

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
