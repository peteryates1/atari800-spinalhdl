package atari800

import spinal.core._

class PokeyMixerMux extends Component {
  val io = new Bundle {
    val enable179 = in Bool()

    val channelL0     = in Bits(4 bits)
    val channelL1     = in Bits(4 bits)
    val channelL2     = in Bits(4 bits)
    val channelL3     = in Bits(4 bits)
    val covoxChannelL0 = in Bits(8 bits)
    val covoxChannelL1 = in Bits(8 bits)
    val sidChannelL0  = in Bits(8 bits)

    val channelR0     = in Bits(4 bits)
    val channelR1     = in Bits(4 bits)
    val channelR2     = in Bits(4 bits)
    val channelR3     = in Bits(4 bits)
    val covoxChannelR0 = in Bits(8 bits)
    val covoxChannelR1 = in Bits(8 bits)
    val sidChannelR0  = in Bits(8 bits)

    val gtiaSound = in Bool()
    val sioAudio  = in Bits(8 bits)

    val volumeOutL = out Bits(16 bits)
    val volumeOutR = out Bits(16 bits)
  }

  val leftChannelReg = Reg(Bool())

  val channel0Sel     = Bits(4 bits)
  val channel1Sel     = Bits(4 bits)
  val channel2Sel     = Bits(4 bits)
  val channel3Sel     = Bits(4 bits)
  val covoxChannel0Sel = Bits(8 bits)
  val covoxChannel1Sel = Bits(8 bits)
  val sidChannel0Sel  = Bits(8 bits)

  val volumeOutLReg = Reg(Bits(16 bits))
  val volumeOutRReg = Reg(Bits(16 bits))

  val leftChannelNext = ~leftChannelReg
  leftChannelReg := leftChannelNext

  // Mux input
  channel0Sel     := B(0, 4 bits)
  channel1Sel     := B(0, 4 bits)
  channel2Sel     := B(0, 4 bits)
  channel3Sel     := B(0, 4 bits)
  covoxChannel0Sel := B(0, 8 bits)
  covoxChannel1Sel := B(0, 8 bits)
  sidChannel0Sel  := B(0, 8 bits)

  when(leftChannelReg) {
    channel0Sel     := io.channelL0
    channel1Sel     := io.channelL1
    channel2Sel     := io.channelL2
    channel3Sel     := io.channelL3
    covoxChannel0Sel := io.covoxChannelL0
    covoxChannel1Sel := io.covoxChannelL1
    sidChannel0Sel  := io.sidChannelL0
  } otherwise {
    channel0Sel     := io.channelR0
    channel1Sel     := io.channelR1
    channel2Sel     := io.channelR2
    channel3Sel     := io.channelR3
    covoxChannel0Sel := io.covoxChannelR0
    covoxChannel1Sel := io.covoxChannelR1
    sidChannel0Sel  := io.sidChannelR0
  }

  // Shared mixer
  val sharedPokeyMixer = new PokeyMixer
  sharedPokeyMixer.io.channel0      := channel0Sel
  sharedPokeyMixer.io.channel1      := channel1Sel
  sharedPokeyMixer.io.channel2      := channel2Sel
  sharedPokeyMixer.io.channel3      := channel3Sel
  sharedPokeyMixer.io.covoxChannel0 := covoxChannel0Sel
  sharedPokeyMixer.io.covoxChannel1 := covoxChannel1Sel
  sharedPokeyMixer.io.sidChannel0   := sidChannel0Sel
  sharedPokeyMixer.io.gtiaSoundBit  := io.gtiaSound
  sharedPokeyMixer.io.sioAudio      := io.sioAudio

  val volumeOutNext = sharedPokeyMixer.io.volumeOutNext

  // Mux output
  val volumeOutLNext = Bits(16 bits)
  val volumeOutRNext = Bits(16 bits)

  volumeOutLNext := volumeOutLReg
  volumeOutRNext := volumeOutRReg

  when(leftChannelReg) {
    volumeOutLNext := volumeOutNext
  } otherwise {
    volumeOutRNext := volumeOutNext
  }

  volumeOutLReg := volumeOutLNext
  volumeOutRReg := volumeOutRNext

  // Low pass filter output
  val filterLeft = new SimpleLowPassFilter
  filterLeft.io.audioIn  := volumeOutLReg
  filterLeft.io.sampleIn := io.enable179

  val filterRight = new SimpleLowPassFilter
  filterRight.io.audioIn  := volumeOutRReg
  filterRight.io.sampleIn := io.enable179

  io.volumeOutL := filterLeft.io.audioOut
  io.volumeOutR := filterRight.io.audioOut
}
