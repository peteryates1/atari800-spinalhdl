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
//     0x43 'C': byte 1 = control bits — 0 reset, 1 start, 2 select, 3 option
//               (held levels; cleared by the next 'C' frame).
//   MISO returns a status stream: 0xA5, frameCount, 0x00...
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
    // debug
    val frameCount = out UInt(8 bits)
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
  val byteIdx  = Reg(UInt(4 bits)) init 0
  val cmdReg   = Reg(Bits(8 bits)) init 0
  val byteDone = False
  val byteVal  = shiftIn(6 downto 0) ## mosi

  when(csFall) { bitCnt := 0; byteIdx := 0 }
  when(!csN && sckRise) {
    shiftIn := byteVal
    bitCnt  := bitCnt + 1
    when(bitCnt === 7) { byteDone := True }
  }

  // ---- MISO status stream (slave shifts on falling edge, mode 0) ----
  val frameCnt = Reg(UInt(8 bits)) init 0
  val shiftOut = Reg(Bits(8 bits)) init 0
  val outIdx   = Reg(UInt(4 bits)) init 0
  when(csFall) { shiftOut := B(0xA5, 8 bits); outIdx := 0 }
  when(!csN && sckFall) {
    when(bitCnt === 0) {   // byte boundary: load the next status byte whole
      shiftOut := Mux(outIdx === 0, frameCnt.asBits, B(0, 8 bits))
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

  val ctrlBits = Reg(Bits(4 bits)) init 0

  val hidMap = Mem(Bits(7 bits), AtariHidMap.table.map(v => B(v, 7 bits)))

  when(byteDone) {
    when(byteIdx === 0) {
      cmdReg := byteVal
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
          when(byteIdx === 1) { ctrlBits := byteVal(3 downto 0) }
        }
      }
    }
    byteIdx := byteIdx + 1
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
