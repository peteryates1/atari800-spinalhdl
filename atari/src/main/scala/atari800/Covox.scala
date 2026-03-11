package atari800

import spinal.core._

class Covox extends Component {
  val io = new Bundle {
    val addr    = in  Bits(2 bits)
    val dataIn  = in  Bits(8 bits)
    val wrEn    = in  Bool()

    val covoxChannel0 = out Bits(8 bits)
    val covoxChannel1 = out Bits(8 bits)
    val covoxChannel2 = out Bits(8 bits)
    val covoxChannel3 = out Bits(8 bits)
  }

  val addrDec = new CompleteAddressDecoder(width = 2)
  addrDec.io.addrIn := io.addr
  val addrDecoded = addrDec.io.addrDecoded

  val channel0Reg = Reg(Bits(8 bits))
  val channel1Reg = Reg(Bits(8 bits))
  val channel2Reg = Reg(Bits(8 bits))
  val channel3Reg = Reg(Bits(8 bits))

  val channel0Next = Bits(8 bits)
  val channel1Next = Bits(8 bits)
  val channel2Next = Bits(8 bits)
  val channel3Next = Bits(8 bits)

  channel0Reg := channel0Next
  channel1Reg := channel1Next
  channel2Reg := channel2Next
  channel3Reg := channel3Next

  // next state logic
  channel0Next := channel0Reg
  channel1Next := channel1Reg
  channel2Next := channel2Reg
  channel3Next := channel3Reg

  when(io.wrEn) {
    when(addrDecoded(0)) { channel0Next := io.dataIn }
    when(addrDecoded(1)) { channel1Next := io.dataIn }
    when(addrDecoded(2)) { channel2Next := io.dataIn }
    when(addrDecoded(3)) { channel3Next := io.dataIn }
  }

  // output
  io.covoxChannel0 := channel0Reg
  io.covoxChannel1 := channel1Reg
  io.covoxChannel2 := channel2Reg
  io.covoxChannel3 := channel3Reg
}
