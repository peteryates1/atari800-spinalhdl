package atari800

import spinal.core._

class PokeyKeyboardScanner extends Component {
  val io = new Bundle {
    val enable           = in  Bool()
    val keyboardResponse = in  Bits(2 bits)
    val debounceDisable  = in  Bool()
    val scanEnable       = in  Bool()

    val keyboardScan = out Bits(6 bits)
    val keyHeld      = out Bool()
    val shiftHeld    = out Bool()
    val keycode      = out Bits(8 bits)
    val otherKeyIrq  = out Bool()
    val breakIrq     = out Bool()
  }

  val stateWaitKey      = B"00"
  val stateKeyBounce    = B"01"
  val stateValidKey     = B"10"
  val stateKeyDebounce  = B"11"

  val bincntReg          = Reg(Bits(6 bits)) init B(0, 6 bits)
  val breakPressedReg    = Reg(Bool()) init False
  val shiftPressedReg    = Reg(Bool()) init False
  val controlPressedReg  = Reg(Bool()) init False
  val compareLatchReg    = Reg(Bits(6 bits)) init B(0, 6 bits)
  val keycodeLatchReg    = Reg(Bits(8 bits)) init B(8 bits, default -> True)
  val keyHeldReg         = Reg(Bool()) init False
  val stateReg           = Reg(Bits(2 bits)) init stateWaitKey
  val irqReg             = Reg(Bool()) init False
  val breakIrqReg        = Reg(Bool()) init False

  val bincntNext         = Bits(6 bits)
  val breakPressedNext   = Bool()
  val shiftPressedNext   = Bool()
  val controlPressedNext = Bool()
  val compareLatchNext   = Bits(6 bits)
  val keycodeLatchNext   = Bits(8 bits)
  val keyHeldNext        = Bool()
  val stateNext          = Bits(2 bits)
  val irqNext            = Bool()
  val breakIrqNext       = Bool()

  bincntReg         := bincntNext
  breakPressedReg   := breakPressedNext
  shiftPressedReg   := shiftPressedNext
  controlPressedReg := controlPressedNext
  compareLatchReg   := compareLatchNext
  keycodeLatchReg   := keycodeLatchNext
  keyHeldReg        := keyHeldNext
  stateReg          := stateNext
  irqReg            := irqNext
  breakIrqReg       := breakIrqNext

  // Combinational next state
  bincntNext         := bincntReg
  stateNext          := stateReg
  compareLatchNext   := compareLatchReg
  irqNext            := False
  breakIrqNext       := False
  breakPressedNext   := breakPressedReg
  shiftPressedNext   := shiftPressedReg
  controlPressedNext := controlPressedReg
  keycodeLatchNext   := keycodeLatchReg
  keyHeldNext        := keyHeldReg

  val myKey = Bool()
  myKey := False
  when(bincntReg === compareLatchReg || io.debounceDisable) {
    myKey := True
  }

  when(io.enable & io.scanEnable) {
    bincntNext := B(bincntReg.asUInt + 1)
    keyHeldNext := False

    switch(stateReg) {
      is(stateWaitKey) {
        when(~io.keyboardResponse(0)) {
          when(io.debounceDisable) {
            keycodeLatchNext := controlPressedReg.asBits ## shiftPressedReg.asBits ## bincntReg
            irqNext := True
            keyHeldNext := True
          } otherwise {
            stateNext := stateKeyBounce
            compareLatchNext := bincntReg
          }
        }
      }
      is(stateKeyBounce) {
        when(~io.keyboardResponse(0)) {
          when(myKey) {
            keycodeLatchNext := controlPressedReg.asBits ## shiftPressedReg.asBits ## compareLatchReg
            irqNext := True
            keyHeldNext := True
            stateNext := stateValidKey
          } otherwise {
            stateNext := stateWaitKey
          }
        } otherwise {
          when(myKey) {
            stateNext := stateWaitKey
          }
        }
      }
      is(stateValidKey) {
        keyHeldNext := True
        when(myKey) {
          when(io.keyboardResponse(0)) {
            stateNext := stateKeyDebounce
          }
        }
      }
      is(stateKeyDebounce) {
        keyHeldNext := True
        when(myKey) {
          when(io.keyboardResponse(0)) {
            keyHeldNext := False
            stateNext := stateWaitKey
          } otherwise {
            stateNext := stateValidKey
          }
        }
      }
    }

    when(bincntReg(3 downto 0) === B"0000") {
      switch(bincntReg(5 downto 4)) {
        is(B"11") {
          breakPressedNext := ~io.keyboardResponse(1)
        }
        is(B"01") {
          shiftPressedNext := ~io.keyboardResponse(1)
        }
        is(B"00") {
          controlPressedNext := ~io.keyboardResponse(1)
        }
        default {
          // nothing
        }
      }
    }
  }

  when(breakPressedNext & ~breakPressedReg) {
    breakIrqNext := True
  }

  // Outputs
  io.keyboardScan := ~bincntReg
  io.keyHeld      := keyHeldReg
  io.shiftHeld    := shiftPressedReg
  io.keycode      := keycodeLatchReg
  io.otherKeyIrq  := irqReg
  io.breakIrq     := breakIrqReg
}
