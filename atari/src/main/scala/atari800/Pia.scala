package atari800

import spinal.core._
import spinal.core.sim._

class Pia extends Component {
  val io = new Bundle {
    val addr       = in  Bits(2 bits)
    val cpuDataIn  = in  Bits(8 bits)
    val en         = in  Bool()
    val wrEn       = in  Bool()

    val enableOrig = in  Bool()

    val ca1 = in  Bool()
    val cb1 = in  Bool()

    val ca2DirOut = out Bool()
    val ca2Out    = out Bool()
    val ca2In     = in  Bool()

    val cb2DirOut = out Bool()
    val cb2Out    = out Bool()
    val cb2In     = in  Bool()

    val portaDirOut = out Bits(8 bits)
    val portaOut    = out Bits(8 bits)
    val portaIn     = in  Bits(8 bits)

    val portbDirOut = out Bits(8 bits)
    val portbOut    = out Bits(8 bits)
    val portbIn     = in  Bits(8 bits)

    val dataOut = out Bits(8 bits)

    val irqaN = out Bool()
    val irqbN = out Bool()
  }

  // synchronizers
  val ca1Synchronizer = new Synchronizer
  ca1Synchronizer.io.raw := io.ca1
  val ca1Sync = ca1Synchronizer.io.sync

  val ca2Synchronizer = new Synchronizer
  ca2Synchronizer.io.raw := io.ca2In
  val ca2InSync = ca2Synchronizer.io.sync

  val cb1Synchronizer = new Synchronizer
  cb1Synchronizer.io.raw := io.cb1
  val cb1Sync = cb1Synchronizer.io.sync

  val cb2Synchronizer = new Synchronizer
  cb2Synchronizer.io.raw := io.cb2In
  val cb2InSync = cb2Synchronizer.io.sync

  // address decoder
  val decodeAddr1 = new CompleteAddressDecoder(width = 2)
  decodeAddr1.io.addrIn := io.addr
  val addrDecoded = decodeAddr1.io.addrDecoded

  // registers
  val portaOutputReg    = Reg(Bits(8 bits)) init B(0, 8 bits)
  val portaInputReg     = Reg(Bits(8 bits)) init B(0, 8 bits)
  val portaDirectionReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val portaControlReg   = Reg(Bits(6 bits)) init B(0, 6 bits)

  val portbOutputReg    = Reg(Bits(8 bits)) init B"xFF"
  portbOutputReg.simPublic()
  val portbInputReg     = Reg(Bits(8 bits)) init B(0, 8 bits)
  val portbDirectionReg = Reg(Bits(8 bits)) init B"xFF"
  portbDirectionReg.simPublic()
  val portbControlReg   = Reg(Bits(6 bits)) init B(0, 6 bits)

  val irqaReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val irqbReg = Reg(Bits(2 bits)) init B(0, 2 bits)

  val ca1EdgeReg = Reg(Bool()) init False
  val ca2EdgeReg = Reg(Bool()) init False
  val cb1EdgeReg = Reg(Bool()) init False
  val cb2EdgeReg = Reg(Bool()) init False

  val ca1Reg = Reg(Bool()) init False
  val ca2Reg = Reg(Bool()) init False
  val cb1Reg = Reg(Bool()) init False
  val cb2Reg = Reg(Bool()) init False

  val ca2OutputReg = Reg(Bool()) init False
  val cb2OutputReg = Reg(Bool()) init False

  // next signals
  val portaOutputNext    = Bits(8 bits)
  val portaInputNext     = Bits(8 bits)
  val portaDirectionNext = Bits(8 bits)
  val portaControlNext   = Bits(6 bits)
  val portbOutputNext    = Bits(8 bits)
  val portbInputNext     = Bits(8 bits)
  val portbDirectionNext = Bits(8 bits)
  val portbControlNext   = Bits(6 bits)
  val irqaNext           = Bits(2 bits)
  val irqbNext           = Bits(2 bits)
  val ca1EdgeNext        = Bool()
  val ca2EdgeNext        = Bool()
  val cb1EdgeNext        = Bool()
  val cb2EdgeNext        = Bool()
  val ca2OutputNext      = Bool()
  val cb2OutputNext      = Bool()

  // register update
  portaOutputReg    := portaOutputNext
  portaInputReg     := portaInputNext
  portaDirectionReg := portaDirectionNext
  portaControlReg   := portaControlNext
  portbOutputReg    := portbOutputNext
  portbInputReg     := portbInputNext
  portbDirectionReg := portbDirectionNext
  portbControlReg   := portbControlNext
  irqaReg           := irqaNext
  irqbReg           := irqbNext
  ca1Reg            := ca1Sync
  ca2Reg            := ca2InSync
  cb1Reg            := cb1Sync
  cb2Reg            := cb2InSync
  ca1EdgeReg        := ca1EdgeNext
  ca2EdgeReg        := ca2EdgeNext
  cb1EdgeReg        := cb1EdgeNext
  cb2EdgeReg        := cb2EdgeNext
  ca2OutputReg      := ca2OutputNext
  cb2OutputReg      := cb2OutputNext

  // read/write helpers
  val readOra  = Bool()
  val readOrb  = Bool()
  val writeOra = Bool()
  val writeOrb = Bool()

  // Write to registers
  portaOutputNext    := portaOutputReg
  portbOutputNext    := portbOutputReg
  portaDirectionNext := portaDirectionReg
  portbDirectionNext := portbDirectionReg
  portaControlNext   := portaControlReg
  portbControlNext   := portbControlReg
  writeOra := False
  writeOrb := False

  when(io.wrEn) {
    when(addrDecoded(0)) {
      when(portaControlReg(2)) {
        portaOutputNext := io.cpuDataIn
        writeOra := True
      } otherwise {
        portaDirectionNext := io.cpuDataIn
      }
    }
    when(addrDecoded(1)) {
      when(portbControlReg(2)) {
        portbOutputNext := io.cpuDataIn
        writeOrb := True
      } otherwise {
        portbDirectionNext := io.cpuDataIn
      }
    }
    when(addrDecoded(2)) {
      portaControlNext := io.cpuDataIn(5 downto 0)
    }
    when(addrDecoded(3)) {
      portbControlNext := io.cpuDataIn(5 downto 0)
    }
  }

  // Read from registers
  io.dataOut := B"xFF"
  readOra := False
  readOrb := False

  when(io.en) {
    when(addrDecoded(0)) {
      when(portaControlReg(2)) {
        io.dataOut := portaInputReg
        readOra := True
      } otherwise {
        io.dataOut := portaDirectionReg
      }
    }
    when(addrDecoded(1)) {
      when(portbControlReg(2)) {
        io.dataOut := portbInputReg
        readOrb := True
      } otherwise {
        io.dataOut := portbDirectionReg
      }
    }
    when(addrDecoded(2)) {
      io.dataOut := irqaReg(1) ## (irqaReg(0) & ~portaControlReg(5)) ## portaControlReg
    }
    when(addrDecoded(3)) {
      io.dataOut := irqbReg(1) ## (irqbReg(0) & ~portbControlReg(5)) ## portbControlReg
    }
  }

  // IRQ handling - port A
  val irqaBase1 = irqaReg(1) & ~readOra
  val irqaBase0 = irqaReg(0) & ~readOra
  ca2OutputNext := ca2OutputReg

  irqaNext(1) := irqaBase1
  irqaNext(0) := irqaBase0

  when(ca1Sync & ~ca1Reg) {
    irqaNext(1) := ca1EdgeReg | irqaBase1
  }
  when(~ca1Sync & ca1Reg) {
    irqaNext(1) := ~ca1EdgeReg | irqaBase1
  }

  when(ca2InSync & ~ca2Reg) {
    irqaNext(0) := ca2EdgeReg | (irqaBase0 & ~portaControlReg(5))
  }
  when(~ca2InSync & ca2Reg) {
    irqaNext(0) := ~ca2EdgeReg | (irqaBase0 & ~portaControlReg(5))
  }

  ca1EdgeNext := portaControlNext(1)
  ca2EdgeNext := portaControlNext(4)

  when(portaControlNext(5)) { // CA2 is an output
    switch(portaControlNext(4 downto 3)) {
      is(B"10") {
        ca2OutputNext := False
      }
      is(B"11") {
        ca2OutputNext := True
      }
      is(B"01") {
        when(readOra) {
          ca2OutputNext := False
        } elsewhen(io.enableOrig) {
          ca2OutputNext := True
        }
      }
      is(B"00") {
        when(readOra) {
          ca2OutputNext := False
        } elsewhen(irqaReg(1)) {
          ca2OutputNext := True
        }
      }
    }
  }

  // IRQ handling - port B
  val irqbBase1 = irqbReg(1) & ~readOrb
  val irqbBase0 = irqbReg(0) & ~readOrb
  cb2OutputNext := cb2OutputReg

  irqbNext(1) := irqbBase1
  irqbNext(0) := irqbBase0

  when(cb1Sync & ~cb1Reg) {
    irqbNext(1) := cb1EdgeReg | irqbBase1
  }
  when(~cb1Sync & cb1Reg) {
    irqbNext(1) := ~cb1EdgeReg | irqbBase1
  }

  when(cb2InSync & ~cb2Reg) {
    irqbNext(0) := cb2EdgeReg | (irqbBase0 & ~portbControlReg(5))
  }
  when(~cb2InSync & cb2Reg) {
    irqbNext(0) := ~cb2EdgeReg | (irqbBase0 & ~portbControlReg(5))
  }

  cb1EdgeNext := portbControlNext(1)
  cb2EdgeNext := portbControlNext(4)

  when(portbControlNext(5)) { // CB2 is an output
    switch(portbControlNext(4 downto 3)) {
      is(B"10") {
        cb2OutputNext := False
      }
      is(B"11") {
        cb2OutputNext := True
      }
      is(B"01") {
        when(writeOrb) {
          cb2OutputNext := False
        } elsewhen(io.enableOrig) {
          cb2OutputNext := True
        }
      }
      is(B"00") {
        when(writeOrb) {
          cb2OutputNext := False
        } elsewhen(irqbReg(1)) {
          cb2OutputNext := True
        }
      }
    }
  }

  // outputs
  io.ca2Out    := ca2OutputReg
  io.ca2DirOut := portaControlReg(5)
  io.cb2Out    := cb2OutputReg
  io.cb2DirOut := portbControlReg(5)

  io.portaOut    := portaOutputReg
  io.portaDirOut := portaDirectionReg
  portaInputNext := io.portaIn

  // portb_out - forced to 1 when in input mode (star raiders relies on this)
  for (i <- 0 until 8) {
    when(portbDirectionReg(i)) {
      io.portbOut(i) := portbOutputReg(i)
    } otherwise {
      io.portbOut(i) := True
    }
  }
  io.portbDirOut := portbDirectionReg
  portbInputNext := io.portbIn

  io.irqaN := ~((irqaReg(1) & portaControlReg(0)) | (irqaReg(0) & portaControlReg(3) & ~portaControlReg(5)))
  io.irqbN := ~((irqbReg(1) & portbControlReg(0)) | (irqbReg(0) & portbControlReg(3) & ~portbControlReg(5)))
}
