package atari800

import spinal.core._

class FreezerDebugTrigger extends Component {
  val io = new Bundle {
    // cpu state
    val cpuAddr          = in  Bits(16 bits)
    val cpuWriteData     = in  Bits(8 bits)
    val cpuReadData      = in  Bits(8 bits)
    val cpuFetch         = in  Bool()
    val cpuFetchComplete = in  Bool()
    val cpuWN            = in  Bool()

    // freezer info
    val freezerEnable = in  Bool()
    val freezerState  = in  Bits(3 bits)

    // settings on what we should match
    val debugAddr      = in  Bits(16 bits)
    val debugData      = in  Bits(8 bits)
    val debugRead      = in  Bool()
    val debugWrite     = in  Bool()
    val debugDataMatch = in  Bool()

    val freezerTrigger = out Bool()
    val freezerNmiN    = out Bool()
  }

  val FREEZER_DEBUG_IDLE        = B"00"
  val FREEZER_DEBUG_WAIT_FROZEN = B"10"
  val FREEZER_DEBUG_IDLE_NEXT   = B"11"

  // regs
  val freezerActivateDebugStateReg  = Reg(Bits(2 bits)) init FREEZER_DEBUG_IDLE
  val freezerActivateDebugStateNext = Bits(2 bits)

  freezerActivateDebugStateReg := freezerActivateDebugStateNext

  // match combinatorial logic
  val addrMatch = Bool()
  addrMatch := (io.cpuAddr === io.debugAddr)

  val addrValid = io.cpuFetch

  val readDataValid  = io.cpuFetchComplete & io.cpuWN
  val writeDataValid = io.cpuFetch & ~io.cpuWN

  val readDataMatch  = Bool()
  readDataMatch := (io.cpuReadData === io.debugData)

  val writeDataMatch = Bool()
  writeDataMatch := (io.cpuWriteData === io.debugData)

  val readMode       = io.debugRead
  val writeMode      = io.debugWrite
  val dataMustMatch  = io.debugDataMatch

  val dataMatch = (readMode & readDataMatch & readDataValid) |
    (writeMode & writeDataMatch & writeDataValid) |
    ~dataMustMatch

  val freezerDebugTriggerSig = addrMatch & addrValid & (readMode | writeMode) & dataMatch & io.freezerEnable

  // state machine to generate nmi until freezer active
  freezerActivateDebugStateNext := freezerActivateDebugStateReg
  io.freezerNmiN := True

  switch(freezerActivateDebugStateReg) {
    is(FREEZER_DEBUG_IDLE) {
      when(freezerDebugTriggerSig) {
        freezerActivateDebugStateNext := FREEZER_DEBUG_WAIT_FROZEN
      }
    }
    is(FREEZER_DEBUG_WAIT_FROZEN) {
      io.freezerNmiN := False
      when(io.freezerState === B"100") {
        freezerActivateDebugStateNext := FREEZER_DEBUG_IDLE_NEXT
      }
    }
    is(FREEZER_DEBUG_IDLE_NEXT) {
      freezerActivateDebugStateNext := FREEZER_DEBUG_IDLE
    }
    default {
      freezerActivateDebugStateNext := FREEZER_DEBUG_IDLE
    }
  }

  // output
  io.freezerTrigger := freezerActivateDebugStateReg(1)
}
