package atari800

import spinal.core._

class SharedEnable(cycleLength: Int = 16) extends Component {
  val io = new Bundle {
    val anticRefresh      = in  Bool()
    val memoryReadyCpu    = in  Bool()
    val memoryReadyAntic  = in  Bool()
    val pause6502         = in  Bool()
    val throttleCount6502 = in  Bits(6 bits)

    val anticEnable179 = out Bool()
    val oldcpuEnable   = out Bool()
    val cpuEnableOut   = out Bool()
  }

  val cycleLengthBits = log2Up(cycleLength)

  // Clock divider for 1.79MHz enable
  val enable179ClockDiv = new EnableDivider(cycleLength)
  enable179ClockDiv.io.enableIn := True
  val enable179 = enable179ClockDiv.io.enableOut

  // Speed shift register for turbo
  val speedShiftReg  = Reg(Bits(cycleLength bits)) init B(0, cycleLength bits)
  val speedShiftNext = Bits(cycleLength bits)
  speedShiftReg := speedShiftNext

  // Speed shift logic
  val speedShiftTemp = Bits(cycleLength bits)
  when(enable179) {
    speedShiftTemp := B(0, cycleLength bits)
    speedShiftTemp(0) := True
  } otherwise {
    speedShiftTemp := speedShiftReg
  }

  speedShiftNext(cycleLength - 1 downto 1) := speedShiftTemp(cycleLength - 2 downto 0)

  val speedShift = Bool()
  val speedShiftTerms = (0 to cycleLengthBits).map(i =>
    speedShiftTemp(cycleLength / (1 << i) - 1) & io.throttleCount6502(i)
  )
  speedShift := speedShiftTerms.reduce(_ | _)
  speedShiftNext(0) := speedShift

  // Delay line for ANTIC enable (1 cycle early)
  val delayLinePhase = new DelayLine(cycleLength - 1)
  delayLinePhase.io.syncReset := False
  delayLinePhase.io.dataIn    := enable179
  delayLinePhase.io.enable    := True
  val enable179Early = delayLinePhase.io.dataOut

  // CPU extra enable register
  val cpuExtraEnableReg = Reg(Bool()) init False

  // Old cycle state machine
  val oldcycleStateIdle                   = B"000"
  val oldcycleStateFirstCycleInProgress   = B"001"
  val oldcycleStateCycleInProgress        = B"010"
  val oldcycleStateWillStartWhenMemFree   = B"011"
  val oldcycleStateDelayed                = B"100"

  val oldcycleStateReg = Reg(Bits(3 bits)) init oldcycleStateIdle

  val memoryReady = io.memoryReadyCpu | io.memoryReadyAntic
  val skipCycle   = io.pause6502 | io.anticRefresh

  val cpuEnable = (speedShiftReg(0) | cpuExtraEnableReg | enable179) & ~skipCycle
  val cpuExtraEnableNext = cpuEnable & ~memoryReady
  cpuExtraEnableReg := cpuExtraEnableNext

  val oldcycleGo        = Bool()
  val oldcycleStateNext = Bits(3 bits)

  oldcycleStateReg := oldcycleStateNext

  oldcycleGo        := False
  oldcycleStateNext := oldcycleStateReg

  switch(oldcycleStateReg) {
    is(oldcycleStateIdle) {
      when(enable179) {
        when(skipCycle) {
          oldcycleGo := True
        } otherwise {
          when(memoryReady) {
            oldcycleGo := True
          } otherwise {
            oldcycleStateNext := oldcycleStateFirstCycleInProgress
          }
        }
      } otherwise {
        when(cpuEnable & ~memoryReady) {
          oldcycleStateNext := oldcycleStateCycleInProgress
        }
      }
    }
    is(oldcycleStateFirstCycleInProgress) {
      when(memoryReady) {
        oldcycleGo := True
        oldcycleStateNext := oldcycleStateIdle
      }
    }
    is(oldcycleStateCycleInProgress) {
      when(enable179) {
        when(memoryReady) {
          oldcycleStateNext := oldcycleStateDelayed
        } otherwise {
          oldcycleStateNext := oldcycleStateWillStartWhenMemFree
        }
      } otherwise {
        when(memoryReady) {
          oldcycleStateNext := oldcycleStateIdle
        }
      }
    }
    is(oldcycleStateWillStartWhenMemFree) {
      when(memoryReady) {
        oldcycleStateNext := oldcycleStateDelayed
      }
    }
    is(oldcycleStateDelayed) {
      oldcycleStateNext := oldcycleStateIdle
      when(skipCycle) {
        oldcycleGo := True
      } otherwise {
        when(memoryReady) {
          oldcycleGo := True
        } otherwise {
          oldcycleStateNext := oldcycleStateFirstCycleInProgress
        }
      }
    }
    default {
      oldcycleStateNext := oldcycleStateIdle
    }
  }

  // Outputs
  io.oldcpuEnable   := oldcycleGo
  io.anticEnable179 := enable179Early
  io.cpuEnableOut   := cpuEnable
}
