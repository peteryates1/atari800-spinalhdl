package atari800

import spinal.core._

class GtiaPriority extends Component {
  val io = new Bundle {
    val colourEnable = in  Bool()
    val prior        = in  Bits(8 bits)
    val p0           = in  Bool()
    val p1           = in  Bool()
    val p2           = in  Bool()
    val p3           = in  Bool()
    val pf0          = in  Bool()
    val pf1          = in  Bool()
    val pf2          = in  Bool()
    val pf3          = in  Bool()
    val bk           = in  Bool()

    val p0Out  = out Bool()
    val p1Out  = out Bool()
    val p2Out  = out Bool()
    val p3Out  = out Bool()
    val pf0Out = out Bool()
    val pf1Out = out Bool()
    val pf2Out = out Bool()
    val pf3Out = out Bool()
    val bkOut  = out Bool()
  }

  // Combinational priority logic (actual GTIA logic)
  val P01  = io.p0 | io.p1
  val P23  = io.p2 | io.p3
  val PF01 = io.pf0 | io.pf1
  val PF23 = io.pf2 | io.pf3

  val PRI0  = io.prior(0)
  val PRI1  = io.prior(1)
  val PRI2  = io.prior(2)
  val PRI3  = io.prior(3)
  val MULTI = io.prior(5)

  val PRI01 = PRI0 | PRI1
  val PRI12 = PRI1 | PRI2
  val PRI23 = PRI2 | PRI3
  val PRI03 = PRI0 | PRI3

  val SP0 = io.p0 & ~(PF01 & PRI23) & ~(PRI2 & PF23)
  val SP1 = io.p1 & ~(PF01 & PRI23) & ~(PRI2 & PF23) & (~io.p0 | MULTI)
  val SP2 = io.p2 & ~P01 & ~(PF23 & PRI12) & ~(PF01 & ~PRI0)
  val SP3 = io.p3 & ~P01 & ~(PF23 & PRI12) & ~(PF01 & ~PRI0) & (~io.p2 | MULTI)
  val SF3 = io.pf3 & ~(P23 & PRI03) & ~(P01 & ~PRI2)
  val SF0 = io.pf0 & ~(P23 & PRI0) & ~(P01 & PRI01) & ~SF3
  val SF1 = io.pf1 & ~(P23 & PRI0) & ~(P01 & PRI01) & ~SF3
  val SF2 = io.pf2 & ~(P23 & PRI03) & ~(P01 & ~PRI2) & ~SF3
  val SB  = ~P01 & ~P23 & ~PF01 & ~PF23

  // Registered outputs
  val sp0Reg = Reg(Bool())
  val sp1Reg = Reg(Bool())
  val sp2Reg = Reg(Bool())
  val sp3Reg = Reg(Bool())
  val sf0Reg = Reg(Bool())
  val sf1Reg = Reg(Bool())
  val sf2Reg = Reg(Bool())
  val sf3Reg = Reg(Bool())
  val sbReg  = Reg(Bool())

  val sp0Next = Bool()
  val sp1Next = Bool()
  val sp2Next = Bool()
  val sp3Next = Bool()
  val sf0Next = Bool()
  val sf1Next = Bool()
  val sf2Next = Bool()
  val sf3Next = Bool()
  val sbNext  = Bool()

  sp0Reg := sp0Next
  sp1Reg := sp1Next
  sp2Reg := sp2Next
  sp3Reg := sp3Next
  sf0Reg := sf0Next
  sf1Reg := sf1Next
  sf2Reg := sf2Next
  sf3Reg := sf3Next
  sbReg  := sbNext

  sp0Next := sp0Reg
  sp1Next := sp1Reg
  sp2Next := sp2Reg
  sp3Next := sp3Reg
  sf0Next := sf0Reg
  sf1Next := sf1Reg
  sf2Next := sf2Reg
  sf3Next := sf3Reg
  sbNext  := sbReg

  when(io.colourEnable) {
    sp0Next := SP0
    sp1Next := SP1
    sp2Next := SP2
    sp3Next := SP3
    sf0Next := SF0
    sf1Next := SF1
    sf2Next := SF2
    sf3Next := SF3
    sbNext  := SB
  }

  io.p0Out  := sp0Reg
  io.p1Out  := sp1Reg
  io.p2Out  := sp2Reg
  io.p3Out  := sp3Reg
  io.pf0Out := sf0Reg
  io.pf1Out := sf1Reg
  io.pf2Out := sf2Reg
  io.pf3Out := sf3Reg
  io.bkOut  := sbReg
}
