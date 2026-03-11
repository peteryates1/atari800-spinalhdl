package atari800

import spinal.core._

class Mmu(system: Int = 0) extends Component {
  val io = new Bundle {
    val addr  = in  Bits(5 bits)  // (15 downto 11)
    val refN  = in  Bool()
    val rd4   = in  Bool()
    val rd5   = in  Bool()
    val mpdN  = in  Bool()
    val ren   = in  Bool()  // ROM ON on/off
    val beN   = in  Bool()  // BASIC ON on/off
    val mapN  = in  Bool()
    val s4N   = out Bool()
    val s5N   = out Bool()
    val basic = out Bool()
    val ioOut = out Bool()
    val os    = out Bool()
    val ci    = out Bool()
  }

  // addr(4)=A15, addr(3)=A14, addr(2)=A13, addr(1)=A12, addr(0)=A11
  val a15 = io.addr(4)
  val a14 = io.addr(3)
  val a13 = io.addr(2)
  val a12 = io.addr(1)
  val a11 = io.addr(0)

  val s4 = ~a13 & ~a14 & a15 & io.rd4 & io.refN                                       // 100X (8000-9fff)
  val s5 = a13 & ~a14 & a15 & io.rd5 & io.refN                                        // 101x (A000-Bfff)
  val ioInt = a12 & ~a11 & ~a13 & a14 & a15 & io.refN                                 // 11010 (D000-D7ff)

  val osen = io.ren & io.refN
  val osInt = (a13 & a14 & a15 & osen) |                                                // 111x (E000-Ffff)
    (~a12 & ~a13 & a14 & a15 & osen) |                                                  // 1100 (C000-Cfff)
    (a12 & a11 & ~a13 & a14 & a15 & io.mpdN & osen) |                                  // 11011(D800-Dfff)
    (a12 & ~a11 & ~a13 & a14 & ~a15 & ~io.mapN & osen)                                 // 01010(5000-5fff) self test

  val basicInt = a13 & ~io.beN & ~a14 & a15 & ~io.rd5 & io.refN                       // 101x (A000-Bfff) when no cart and basic on

  io.ci := s4 | s5 | basicInt | osInt | ioInt | ~io.refN                               // Refresh cycle

  io.s4N  := ~s4
  io.s5N  := ~s5
  io.basic := basicInt
  io.os    := osInt
  io.ioOut := ioInt
}
