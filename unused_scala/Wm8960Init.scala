package atari800

import spinal.core._
import spinal.lib._
import spinal.lib.io._
import spinal.lib.fsm._

// WM8960 I2C Initialization
// Sends configuration registers to WM8960 audio codec at startup
// I2C address: 0x1A (7-bit)
class Wm8960Init(clkFreq: Int = 56670000, i2cFreq: Int = 100000) extends Component {
  val io = new Bundle {
    val i2cScl = out Bool()
    val i2cSda = master(TriState(Bool()))
    val done   = out Bool()
  }

  val I2C_ADDR = B"0011010"  // 0x1A
  val CLK_DIV  = clkFreq / (i2cFreq * 4)

  // WM8960 register init data: {reg_addr[6:0], data[8:0]} = 16 bits each
  val initRegs = Vec(Bits(16 bits), 16)
  initRegs( 0) := B"0000111" ## B"100000000"  // R15: Reset
  initRegs( 1) := B"0011001" ## B"011000000"  // R25: Power 1
  initRegs( 2) := B"0011010" ## B"111111000"  // R26: Power 2
  initRegs( 3) := B"0101111" ## B"000001100"  // R47: Power 3
  initRegs( 4) := B"0000111" ## B"000000010"  // R7: Audio Interface
  initRegs( 5) := B"0000100" ## B"000000000"  // R4: Clocking 1
  initRegs( 6) := B"0100010" ## B"100000000"  // R34: Left out mix
  initRegs( 7) := B"0100101" ## B"100000000"  // R37: Right out mix
  initRegs( 8) := B"0000010" ## B"101111001"  // R2: Left headphone vol
  initRegs( 9) := B"0000011" ## B"101111001"  // R3: Right headphone vol
  initRegs(10) := B"0101000" ## B"101111001"  // R40: Left speaker vol
  initRegs(11) := B"0101001" ## B"101111001"  // R41: Right speaker vol
  initRegs(12) := B"0110001" ## B"011110111"  // R49: Class D control
  initRegs(13) := B"0001010" ## B"111111111"  // R10: Left DAC vol
  initRegs(14) := B"0001011" ## B"111111111"  // R11: Right DAC vol
  initRegs(15) := B"0000101" ## B"000000000"  // R5: DAC control

  val NUM_REGS = 16

  // I2C clock tick generator
  val clkCnt  = Reg(UInt(16 bits)) init 0
  val clkTick = False
  when(clkCnt === CLK_DIV - 1) {
    clkCnt  := 0
    clkTick := True
  } otherwise {
    clkCnt := clkCnt + 1
  }

  // I2C output registers
  val sdaOut   = Reg(Bool()) init True
  val sclOut   = Reg(Bool()) init True
  val sdaOe    = Reg(Bool()) init False
  val regIdx   = Reg(UInt(log2Up(NUM_REGS + 1) bits)) init 0
  val bitIdx   = Reg(UInt(4 bits)) init 0
  val byteVal  = Reg(Bits(8 bits)) init 0
  val subState = Reg(UInt(2 bits)) init 0
  val pauseCnt = Reg(UInt(20 bits)) init 0
  val isDone   = Reg(Bool()) init False

  io.i2cScl        := Mux(sclOut, True, False)
  io.i2cSda.write  := sdaOut
  io.i2cSda.writeEnable := sdaOe
  io.done          := isDone

  // Current register data
  val currentReg = initRegs(regIdx.resized)
  val i2cByte1   = currentReg(15 downto 9) ## currentReg(8)
  val i2cByte2   = currentReg(7 downto 0)

  val fsm = new StateMachine {
    val sIdle     = new State with EntryPoint
    val sStart    = new State
    val sSendAddr = new State
    val sAckWait  = new State
    val sSendReg  = new State
    val sSendData = new State
    val sStop     = new State
    val sPause    = new State
    val sDone     = new State

    sIdle.whenIsActive {
      when(clkTick) {
        pauseCnt := pauseCnt + 1
        when(pauseCnt(19)) {
          subState := 0
          goto(sStart)
        }
      }
    }

    sStart.whenIsActive {
      sdaOe := True
      when(clkTick) {
        switch(subState) {
          is(0) { sclOut := True; sdaOut := True; subState := 1 }
          is(1) { sdaOut := False; subState := 2 }
          is(2) { sclOut := False; subState := 3 }
          is(3) {
            byteVal := I2C_ADDR ## False
            bitIdx := 7
            subState := 0
            goto(sSendAddr)
          }
        }
      }
    }

    sSendAddr.whenIsActive {
      when(clkTick) {
        switch(subState) {
          is(0) { sdaOut := byteVal(bitIdx.resized); subState := 1 }
          is(1) { sclOut := True; subState := 2 }
          is(2) { sclOut := False; subState := 3 }
          is(3) {
            subState := 0
            when(bitIdx === 0) {
              sdaOe := False
              byteVal := i2cByte1
              bitIdx := 7
              goto(sAckWait)
            } otherwise {
              bitIdx := bitIdx - 1
            }
          }
        }
      }
    }

    sAckWait.whenIsActive {
      when(clkTick) {
        switch(subState) {
          is(0) { sclOut := True; subState := 1 }
          is(1) { sclOut := False; subState := 2 }
          is(2) {
            sdaOe := True
            subState := 0
            goto(sSendReg)
          }
          default { subState := 0 }
        }
      }
    }

    sSendReg.whenIsActive {
      when(clkTick) {
        switch(subState) {
          is(0) { sdaOut := byteVal(bitIdx.resized); subState := 1 }
          is(1) { sclOut := True; subState := 2 }
          is(2) { sclOut := False; subState := 3 }
          is(3) {
            subState := 0
            when(bitIdx === 0) {
              sdaOe := False
              byteVal := i2cByte2
              bitIdx := 7
              goto(sSendData)
            } otherwise {
              bitIdx := bitIdx - 1
            }
          }
        }
      }
    }

    sSendData.whenIsActive {
      when(clkTick) {
        switch(subState) {
          is(0) { sdaOe := True; sdaOut := byteVal(bitIdx.resized); subState := 1 }
          is(1) { sclOut := True; subState := 2 }
          is(2) { sclOut := False; subState := 3 }
          is(3) {
            subState := 0
            when(bitIdx === 0) {
              sdaOe := False
              goto(sStop)
            } otherwise {
              bitIdx := bitIdx - 1
            }
          }
        }
      }
    }

    sStop.whenIsActive {
      sdaOe := True
      when(clkTick) {
        switch(subState) {
          is(0) { sdaOut := False; sclOut := False; subState := 1 }
          is(1) { sclOut := True; subState := 2 }
          is(2) { sdaOut := True; subState := 3 }
          is(3) {
            sdaOe := False
            pauseCnt := 0
            subState := 0
            goto(sPause)
          }
        }
      }
    }

    sPause.whenIsActive {
      when(clkTick) {
        pauseCnt := pauseCnt + 1
        when(pauseCnt(15)) {
          when(regIdx === NUM_REGS - 1) {
            goto(sDone)
          } otherwise {
            regIdx := regIdx + 1
            subState := 0
            goto(sStart)
          }
        }
      }
    }

    sDone.whenIsActive {
      sdaOe := False
      sclOut := True
      isDone := True
    }
  }
}
