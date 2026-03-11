package atari800

import spinal.core._

class Pokey(CUSTOM_KEYBOARD_SCAN: Int = 0) extends Component {
  val io = new Bundle {
    val enable179          = in  Bool()
    val addr               = in  Bits(4 bits)
    val dataIn             = in  Bits(8 bits)
    val wrEn               = in  Bool()

    val keyboardScanEnable = in  Bool()
    val keyboardScan       = out Bits(6 bits)
    val keyboardResponse   = in  Bits(2 bits)

    val potIn              = in  Bits(8 bits)

    val sioIn1             = in  Bool()
    val sioIn2             = in  Bool()
    val sioIn3             = in  Bool()

    val dataOut            = out Bits(8 bits)

    val channel0Out        = out Bits(4 bits)
    val channel1Out        = out Bits(4 bits)
    val channel2Out        = out Bits(4 bits)
    val channel3Out        = out Bits(4 bits)

    val irqNOut            = out Bool()

    val sioOut1            = out Bool()
    val sioOut2            = out Bool()
    val sioOut3            = out Bool()

    val sioClockinIn       = in  Bool()
    val sioClockinOut      = out Bool()
    val sioClockinOe       = out Bool()
    val sioClockout        = out Bool()

    val potReset           = out Bool()
  }

  // clock enables
  val enable64 = Bool()
  val enable15 = Bool()

  // audio registers
  val audf0Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audc0Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audf1Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audc1Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audf2Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audc2Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audf3Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audc3Reg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val audctlReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  val audf0Next   = Bits(8 bits)
  val audc0Next   = Bits(8 bits)
  val audf1Next   = Bits(8 bits)
  val audc1Next   = Bits(8 bits)
  val audf2Next   = Bits(8 bits)
  val audc2Next   = Bits(8 bits)
  val audf3Next   = Bits(8 bits)
  val audc3Next   = Bits(8 bits)
  val audctlNext  = Bits(8 bits)

  audf0Reg  := audf0Next
  audc0Reg  := audc0Next
  audf1Reg  := audf1Next
  audc1Reg  := audc1Next
  audf2Reg  := audf2Next
  audc2Reg  := audc2Next
  audf3Reg  := audf3Next
  audc3Reg  := audc3Next
  audctlReg := audctlNext

  // IRQ registers
  val irqenReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val irqstReg = Reg(Bits(8 bits)) init B"xFF"
  val irqNReg  = Reg(Bool()) init True

  val irqenNext = Bits(8 bits)
  val irqstNext = Bits(8 bits)
  val irqNNext  = Bool()

  irqenReg := irqenNext
  irqstReg := irqstNext
  irqNReg  := irqNNext

  // SKCTL
  val skctlReg  = Reg(Bits(8 bits)) init B(0, 8 bits)
  val skctlNext = Bits(8 bits)
  skctlReg := skctlNext

  // channel output
  val chan0OutputReg = Reg(Bool()) init False
  val chan1OutputReg = Reg(Bool()) init False
  val chan2OutputReg = Reg(Bool()) init False
  val chan3OutputReg = Reg(Bool()) init False
  val chan0OutputNext = Bool()
  val chan1OutputNext = Bool()
  val chan2OutputNext = Bool()
  val chan3OutputNext = Bool()
  chan0OutputReg := chan0OutputNext
  chan1OutputReg := chan1OutputNext
  chan2OutputReg := chan2OutputNext
  chan3OutputReg := chan3OutputNext

  val chan0OutputDelReg = Reg(Bool()) init False
  val chan1OutputDelReg = Reg(Bool()) init False
  val chan0OutputDelNext = Bool()
  val chan1OutputDelNext = Bool()
  chan0OutputDelReg := chan0OutputDelNext
  chan1OutputDelReg := chan1OutputDelNext

  // high pass
  val highpass0Reg = Reg(Bool()) init False
  val highpass1Reg = Reg(Bool()) init False
  val highpass0Next = Bool()
  val highpass1Next = Bool()
  highpass0Reg := highpass0Next
  highpass1Reg := highpass1Next

  // volume
  val volumeChannel0Reg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val volumeChannel1Reg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val volumeChannel2Reg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val volumeChannel3Reg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val volumeChannel0Next = Bits(4 bits)
  val volumeChannel1Next = Bits(4 bits)
  val volumeChannel2Next = Bits(4 bits)
  val volumeChannel3Next = Bits(4 bits)
  volumeChannel0Reg := volumeChannel0Next
  volumeChannel1Reg := volumeChannel1Next
  volumeChannel2Reg := volumeChannel2Next
  volumeChannel3Reg := volumeChannel3Next

  // serial
  val serinReg           = Reg(Bits(8 bits)) init B(0, 8 bits)
  val serinShiftReg      = Reg(Bits(10 bits)) init B(0, 10 bits)
  val serinBitcountReg   = Reg(Bits(4 bits)) init B(0, 4 bits)
  val seroutShiftReg     = Reg(Bits(10 bits)) init B(0, 10 bits)
  val seroutHoldingReg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val seroutHoldingFullReg = Reg(Bool()) init False
  val seroutActiveReg    = Reg(Bool()) init False
  val sioOutReg          = Reg(Bool()) init True
  val serialOutReg       = Reg(Bool()) init True
  val serialIpFramingReg = Reg(Bool()) init False
  val serialIpOverrunReg = Reg(Bool()) init False
  val clockValReg        = Reg(Bool()) init False
  val clockSyncReg       = Reg(Bool()) init False
  val keyboardOverrunReg = Reg(Bool()) init False
  val serinClockReg      = Reg(Bool()) init False
  val serinClockLastReg  = Reg(Bool()) init False
  val seroutClockReg     = Reg(Bool()) init False
  val seroutClockLastReg = Reg(Bool()) init False
  val twotoneReg         = Reg(Bool()) init False
  val sioInReg           = Reg(Bool()) init False
  val seroutBitcountReg  = Reg(Bits(4 bits)) init B(0, 4 bits)

  val serinNext           = Bits(8 bits)
  val serinShiftNext      = Bits(10 bits)
  val serinBitcountNext   = Bits(4 bits)
  val seroutShiftNext     = Bits(10 bits)
  val seroutHoldingNext   = Bits(8 bits)
  val seroutHoldingFullNext = Bool()
  val seroutActiveNext    = Bool()
  val sioOutNext          = Bool()
  val serialOutNext       = Bool()
  val serialIpFramingNext = Bool()
  val serialIpOverrunNext = Bool()
  val clockNext           = Bool()
  val clockSyncNext       = Bool()
  val keyboardOverrunNext = Bool()
  val serinClockNext      = Bool()
  val serinClockLastNext  = Bool()
  val seroutClockNext     = Bool()
  val seroutClockLastNext = Bool()
  val twotoneNext         = Bool()
  val sioInNext           = Bool()
  val seroutBitcountNext  = Bits(4 bits)

  serinReg           := serinNext
  serinShiftReg      := serinShiftNext
  serinBitcountReg   := serinBitcountNext
  seroutShiftReg     := seroutShiftNext
  seroutHoldingReg   := seroutHoldingNext
  seroutHoldingFullReg := seroutHoldingFullNext
  seroutActiveReg    := seroutActiveNext
  sioOutReg          := sioOutNext
  serialOutReg       := serialOutNext
  serialIpFramingReg := serialIpFramingNext
  serialIpOverrunReg := serialIpOverrunNext
  clockValReg        := clockNext
  clockSyncReg       := clockSyncNext
  keyboardOverrunReg := keyboardOverrunNext
  serinClockReg      := serinClockNext
  serinClockLastReg  := serinClockLastNext
  seroutClockReg     := seroutClockNext
  seroutClockLastReg := seroutClockLastNext
  twotoneReg         := twotoneNext
  sioInReg           := sioInNext
  seroutBitcountReg  := seroutBitcountNext

  // pots
  val pot0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot3Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot4Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot5Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot6Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val pot7Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val allpotReg = Reg(Bits(8 bits)) init B"xFF"
  val potCounterReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val potResetReg = Reg(Bool()) init True

  val pot0Next = Bits(8 bits)
  val pot1Next = Bits(8 bits)
  val pot2Next = Bits(8 bits)
  val pot3Next = Bits(8 bits)
  val pot4Next = Bits(8 bits)
  val pot5Next = Bits(8 bits)
  val pot6Next = Bits(8 bits)
  val pot7Next = Bits(8 bits)
  val allpotNext = Bits(8 bits)
  val potCounterNext = Bits(8 bits)
  val potResetNext = Bool()

  pot0Reg := pot0Next
  pot1Reg := pot1Next
  pot2Reg := pot2Next
  pot3Reg := pot3Next
  pot4Reg := pot4Next
  pot5Reg := pot5Next
  pot6Reg := pot6Next
  pot7Reg := pot7Next
  allpotReg := allpotNext
  potCounterReg := potCounterNext
  potResetReg := potResetNext

  // noise registers
  val noise4Reg    = Reg(Bits(3 bits)) init B(0, 3 bits)
  val noise5Reg    = Reg(Bits(3 bits)) init B(0, 3 bits)
  val noiseLargeReg = Reg(Bits(3 bits)) init B(0, 3 bits)
  val noise4Next    = Bits(3 bits)
  val noise5Next    = Bits(3 bits)
  val noiseLargeNext = Bits(3 bits)
  noise4Reg    := noise4Next
  noise5Reg    := noise5Next
  noiseLargeReg := noiseLargeNext

  // address decoder
  val decodeAddr1 = new CompleteAddressDecoder(width = 4)
  decodeAddr1.io.addrIn := io.addr
  val addrDecoded = decodeAddr1.io.addrDecoded

  // timer enables
  val audf0Enable = Bool()
  val audf1Enable = Bool()
  val audf2Enable = Bool()
  val audf3Enable = Bool()

  val audf0Pulse = Bool()
  val audf1Pulse = Bool()
  val audf2Pulse = Bool()
  val audf3Pulse = Bool()

  // clock selection
  audf0Enable := enable64
  audf1Enable := enable64
  audf2Enable := enable64
  audf3Enable := enable64

  when(audctlReg(0)) {
    audf0Enable := enable15
    audf1Enable := enable15
    audf2Enable := enable15
    audf3Enable := enable15
  }
  when(audctlReg(6)) { audf0Enable := io.enable179 }
  when(audctlReg(5)) { audf2Enable := io.enable179 }
  when(audctlReg(4)) { audf1Enable := audf0Pulse }
  when(audctlReg(3)) { audf3Enable := audf2Pulse }

  // timers
  val audf0Reload = Bool()
  val audf1Reload = Bool()
  val audf2Reload = Bool()
  val audf3Reload = Bool()

  val timer0 = new PokeyCountdownTimer(UNDERFLOW_DELAY = 3)
  timer0.io.enable := audf0Enable
  timer0.io.enableUnderflow := io.enable179
  timer0.io.wrEn := audf0Reload
  timer0.io.dataIn := audf0Next
  audf0Pulse := timer0.io.dataOut

  val timer1 = new PokeyCountdownTimer(UNDERFLOW_DELAY = 3)
  timer1.io.enable := audf1Enable
  timer1.io.enableUnderflow := io.enable179
  timer1.io.wrEn := audf1Reload
  timer1.io.dataIn := audf1Next
  audf1Pulse := timer1.io.dataOut

  val timer2 = new PokeyCountdownTimer(UNDERFLOW_DELAY = 3)
  timer2.io.enable := audf2Enable
  timer2.io.enableUnderflow := io.enable179
  timer2.io.wrEn := audf2Reload
  timer2.io.dataIn := audf2Next
  audf2Pulse := timer2.io.dataOut

  val timer3 = new PokeyCountdownTimer(UNDERFLOW_DELAY = 3)
  timer3.io.enable := audf3Enable
  timer3.io.enableUnderflow := io.enable179
  timer3.io.wrEn := audf3Reload
  timer3.io.dataIn := audf3Next
  audf3Pulse := timer3.io.dataOut

  // timer reload logic
  val stimerWrite        = Bool()
  val stimerWriteDelayed = Bool()
  val asyncSerialReset   = Bool()
  val twotoneReset       = Bool()
  val twotoneResetDelayed = Bool()

  audf0Reload := ((~audctlReg(4) & audf0Pulse)) | (audctlReg(4) & audf1Pulse) | stimerWriteDelayed | twotoneResetDelayed
  audf1Reload := audf1Pulse | stimerWriteDelayed | twotoneResetDelayed
  audf2Reload := ((~audctlReg(3) & audf2Pulse)) | (audctlReg(3) & audf3Pulse) | stimerWriteDelayed | asyncSerialReset
  audf3Reload := audf3Pulse | stimerWriteDelayed | asyncSerialReset

  val twotoneDel = new LatchDelayLine(COUNT = 2)
  twotoneDel.io.syncReset := False
  twotoneDel.io.dataIn := twotoneReset
  twotoneDel.io.enable := io.enable179
  twotoneResetDelayed := twotoneDel.io.dataOut

  // stimer delay
  val stimerDelay = new LatchDelayLine(COUNT = 3)
  stimerDelay.io.syncReset := False
  stimerDelay.io.dataIn := stimerWrite
  stimerDelay.io.enable := io.enable179
  stimerWriteDelayed := stimerDelay.io.dataOut

  // write to registers
  val seroutHoldingLoad = Bool()
  val serialReset       = Bool()
  val skrestWrite       = Bool()
  val potgoWrite        = Bool()

  audf0Next  := audf0Reg
  audc0Next  := audc0Reg
  audf1Next  := audf1Reg
  audc1Next  := audc1Reg
  audf2Next  := audf2Reg
  audc2Next  := audc2Reg
  audf3Next  := audf3Reg
  audc3Next  := audc3Reg
  audctlNext := audctlReg
  irqenNext  := irqenReg
  skctlNext  := skctlReg
  stimerWrite := False
  seroutHoldingLoad := False
  seroutHoldingNext := seroutHoldingReg
  serialReset := False
  skrestWrite := False
  potgoWrite  := False

  when(io.wrEn) {
    when(addrDecoded(0))  { audf0Next := io.dataIn }
    when(addrDecoded(1))  { audc0Next := io.dataIn }
    when(addrDecoded(2))  { audf1Next := io.dataIn }
    when(addrDecoded(3))  { audc1Next := io.dataIn }
    when(addrDecoded(4))  { audf2Next := io.dataIn }
    when(addrDecoded(5))  { audc2Next := io.dataIn }
    when(addrDecoded(6))  { audf3Next := io.dataIn }
    when(addrDecoded(7))  { audc3Next := io.dataIn }
    when(addrDecoded(8))  { audctlNext := io.dataIn }
    when(addrDecoded(9))  { stimerWrite := True }    // STIMER
    when(addrDecoded(10)) { skrestWrite := True }    // SKREST
    when(addrDecoded(11)) { potgoWrite := True }     // POTGO
    when(addrDecoded(13)) {                          // SEROUT
      seroutHoldingNext := io.dataIn
      seroutHoldingLoad := True
    }
    when(addrDecoded(14)) { irqenNext := io.dataIn } // IRQEN
    when(addrDecoded(15)) {                          // SKCTL
      skctlNext := io.dataIn
      when(io.dataIn(6 downto 4) === B"000") {
        serialReset := True
      }
    }
  }

  // read from registers
  val noise4    = Bool()
  val noise5    = Bool()
  val noiseLarge = Bool()
  val randOut   = Bits(8 bits)
  val kbcode    = Bits(8 bits)
  val keyHeld   = Bool()
  val shiftHeld = Bool()
  val otherKeyIrq = Bool()
  val breakIrq    = Bool()
  val waitingForStartBit = Bool()

  waitingForStartBit := (serinBitcountReg === B"x9")

  io.dataOut := B"xFF"
  when(addrDecoded(0))  { io.dataOut := pot0Reg }
  when(addrDecoded(1))  { io.dataOut := pot1Reg }
  when(addrDecoded(2))  { io.dataOut := pot2Reg }
  when(addrDecoded(3))  { io.dataOut := pot3Reg }
  when(addrDecoded(4))  { io.dataOut := pot4Reg }
  when(addrDecoded(5))  { io.dataOut := pot5Reg }
  when(addrDecoded(6))  { io.dataOut := pot6Reg }
  when(addrDecoded(7))  { io.dataOut := pot7Reg }
  when(addrDecoded(8))  { io.dataOut := allpotReg }
  when(addrDecoded(9))  { io.dataOut := kbcode }
  when(addrDecoded(10)) { io.dataOut := randOut }
  when(addrDecoded(13)) { io.dataOut := serinReg }
  when(addrDecoded(14)) { io.dataOut := irqstReg }
  when(addrDecoded(15)) {
    io.dataOut := ~serialIpFramingReg ## ~keyboardOverrunReg ## ~serialIpOverrunReg ## sioInReg ## ~shiftHeld ## ~keyHeld ## waitingForStartBit ## B"1"
  }

  // fire interrupts
  val serialIpReadyInterrupt  = Bool()
  val serialOpNeededInterrupt = Bool()

  irqstNext := irqstReg | ~irqenReg
  irqNNext := False
  when((irqstReg | (B"0000" ## ~irqenReg(3) ## B"000")) === B"xFF") {
    irqNNext := True
  }
  when(audf0Pulse) { irqstNext(0) := ~irqenReg(0) }
  when(audf1Pulse) { irqstNext(1) := ~irqenReg(1) }
  when(audf3Pulse) { irqstNext(2) := ~irqenReg(2) }
  when(otherKeyIrq) { irqstNext(6) := ~irqenReg(6) }
  when(breakIrq)    { irqstNext(7) := ~irqenReg(7) }
  when(serialIpReadyInterrupt) { irqstNext(5) := ~irqenReg(5) }
  irqstNext(3) := seroutActiveReg
  when(serialOpNeededInterrupt) { irqstNext(4) := ~irqenReg(4) }

  // noise filters
  val audf0PulseNoise = Bool()
  val audf1PulseNoise = Bool()
  val audf2PulseNoise = Bool()
  val audf3PulseNoise = Bool()

  val pokeyNoiseFilter0 = new PokeyNoiseFilter
  pokeyNoiseFilter0.io.noiseSelect := audc0Reg(7 downto 5)
  pokeyNoiseFilter0.io.pulseIn := audf0Pulse
  pokeyNoiseFilter0.io.noise4 := noise4
  pokeyNoiseFilter0.io.noise5 := noise5
  pokeyNoiseFilter0.io.noiseLarge := noiseLarge
  pokeyNoiseFilter0.io.syncReset := stimerWriteDelayed
  audf0PulseNoise := pokeyNoiseFilter0.io.pulseOut

  val pokeyNoiseFilter1 = new PokeyNoiseFilter
  pokeyNoiseFilter1.io.noiseSelect := audc1Reg(7 downto 5)
  pokeyNoiseFilter1.io.pulseIn := audf1Pulse
  pokeyNoiseFilter1.io.noise4 := noise4Reg(0)
  pokeyNoiseFilter1.io.noise5 := noise5Reg(0)
  pokeyNoiseFilter1.io.noiseLarge := noiseLargeReg(0)
  pokeyNoiseFilter1.io.syncReset := stimerWriteDelayed
  audf1PulseNoise := pokeyNoiseFilter1.io.pulseOut

  val pokeyNoiseFilter2 = new PokeyNoiseFilter
  pokeyNoiseFilter2.io.noiseSelect := audc2Reg(7 downto 5)
  pokeyNoiseFilter2.io.pulseIn := audf2Pulse
  pokeyNoiseFilter2.io.noise4 := noise4Reg(1)
  pokeyNoiseFilter2.io.noise5 := noise5Reg(1)
  pokeyNoiseFilter2.io.noiseLarge := noiseLargeReg(1)
  pokeyNoiseFilter2.io.syncReset := stimerWriteDelayed
  audf2PulseNoise := pokeyNoiseFilter2.io.pulseOut

  val pokeyNoiseFilter3 = new PokeyNoiseFilter
  pokeyNoiseFilter3.io.noiseSelect := audc3Reg(7 downto 5)
  pokeyNoiseFilter3.io.pulseIn := audf3Pulse
  pokeyNoiseFilter3.io.noise4 := noise4Reg(2)
  pokeyNoiseFilter3.io.noise5 := noise5Reg(2)
  pokeyNoiseFilter3.io.noiseLarge := noiseLargeReg(2)
  pokeyNoiseFilter3.io.syncReset := stimerWriteDelayed
  audf3PulseNoise := pokeyNoiseFilter3.io.pulseOut

  // audio output stage
  chan0OutputNext := audf0PulseNoise
  chan1OutputNext := audf1PulseNoise
  chan2OutputNext := audf2PulseNoise
  chan3OutputNext := audf3PulseNoise

  // high pass filters
  highpass0Next := highpass0Reg
  highpass1Next := highpass1Reg
  when(audctlReg(2)) {
    when(audf2Pulse) { highpass0Next := chan0OutputReg }
  } otherwise {
    highpass0Next := True
  }
  when(audctlReg(1)) {
    when(audf3Pulse) { highpass1Next := chan1OutputReg }
  } otherwise {
    highpass1Next := True
  }

  // delayed channel outputs
  chan0OutputDelNext := chan0OutputDelReg
  chan1OutputDelNext := chan1OutputDelReg
  when(io.enable179) {
    chan0OutputDelNext := chan0OutputReg
    chan1OutputDelNext := chan1OutputReg
  }

  // clock dividers
  val initmode = ~(skctlNext(1) | skctlNext(0))

  val enable64Div = new SyncresetEnableDivider(COUNT = 28, RESETCOUNT = 6)
  enable64Div.io.syncreset := initmode
  enable64Div.io.enableIn := io.enable179
  enable64 := enable64Div.io.enableOut

  val enable15Div = new SyncresetEnableDivider(COUNT = 114, RESETCOUNT = 33)
  enable15Div.io.syncreset := initmode
  enable15Div.io.enableIn := io.enable179
  enable15 := enable15Div.io.enableOut

  // noise circuits (LFSR)
  val poly1719Lfsr = new PokeyPoly17_9
  poly1719Lfsr.io.init := initmode
  poly1719Lfsr.io.enable := io.enable179
  poly1719Lfsr.io.select9_17 := audctlReg(7)
  noiseLarge := poly1719Lfsr.io.bitOut
  randOut := poly1719Lfsr.io.randOut

  val poly5Lfsr = new PokeyPoly5
  poly5Lfsr.io.init := initmode
  poly5Lfsr.io.enable := io.enable179
  noise5 := poly5Lfsr.io.bitOut

  val poly4Lfsr = new PokeyPoly4
  poly4Lfsr.io.init := initmode
  poly4Lfsr.io.enable := io.enable179
  noise4 := poly4Lfsr.io.bitOut

  // noise delay between channels
  noise4Next    := noise4Reg
  noise5Next    := noise5Reg
  noiseLargeNext := noiseLargeReg
  when(io.enable179) {
    noiseLargeNext := noiseLargeReg(1 downto 0) ## noiseLarge
    noise5Next     := noise5Reg(1 downto 0) ## noise5
    noise4Next     := noise4Reg(1 downto 0) ## noise4
  }

  // volume output
  volumeChannel0Next := B"0000"
  volumeChannel1Next := B"0000"
  volumeChannel2Next := B"0000"
  volumeChannel3Next := B"0000"

  when((chan0OutputDelReg ^ highpass0Reg) | audc0Reg(4)) { volumeChannel0Next := audc0Reg(3 downto 0) }
  when((chan1OutputDelReg ^ highpass1Reg) | audc1Reg(4)) { volumeChannel1Next := audc1Reg(3 downto 0) }
  when(chan2OutputReg | audc2Reg(4)) { volumeChannel2Next := audc2Reg(3 downto 0) }
  when(chan3OutputReg | audc3Reg(4)) { volumeChannel3Next := audc3Reg(3 downto 0) }

  // serial port output
  val seroutSyncReset = serialReset | stimerWriteDelayed
  val seroutEnable = Bool()
  val seroutEnableDelayed = Bool()
  val serinEnable = Bool()
  val serinEnableDelayed = Bool()

  val seroutClockDelay = new DelayLine(COUNT = 2)
  seroutClockDelay.io.syncReset := seroutSyncReset
  seroutClockDelay.io.dataIn := seroutEnable
  seroutClockDelay.io.enable := io.enable179
  seroutEnableDelayed := seroutClockDelay.io.dataOut

  val serinClockDelay = new DelayLine(COUNT = 5)
  serinClockDelay.io.syncReset := seroutSyncReset
  serinClockDelay.io.dataIn := serinEnable
  serinClockDelay.io.enable := io.enable179
  serinEnableDelayed := serinClockDelay.io.dataOut

  // serial output process
  seroutClockNext     := seroutClockReg
  seroutClockLastNext := seroutClockReg
  seroutShiftNext     := seroutShiftReg
  seroutBitcountNext  := seroutBitcountReg
  seroutHoldingFullNext := seroutHoldingFullReg
  seroutActiveNext    := seroutActiveReg
  serialOutNext       := serialOutReg
  sioOutNext          := serialOutReg
  twotoneNext         := twotoneReg
  twotoneReset        := False
  serialOpNeededInterrupt := False

  when((audf1Pulse | (audf0Pulse & serialOutReg))) {
    twotoneNext  := ~twotoneReg
    twotoneReset := skctlReg(3)
  }
  when(skctlReg(3)) { sioOutNext := twotoneReg }

  when(seroutEnableDelayed) { seroutClockNext := ~seroutClockReg }

  when(~seroutClockLastReg & seroutClockReg) {
    seroutShiftNext := False ## seroutShiftReg(9 downto 1)
    serialOutNext   := seroutShiftReg(1) | ~seroutActiveReg

    when(seroutBitcountReg === B"x0") {
      when(seroutHoldingFullReg) {
        seroutBitcountNext  := B"x9"
        seroutShiftNext     := True ## seroutHoldingReg ## False
        serialOutNext       := False
        seroutHoldingFullNext := False
        serialOpNeededInterrupt := True
        seroutActiveNext    := True
      } otherwise {
        seroutActiveNext := False
        serialOutNext    := True
      }
    } otherwise {
      seroutBitcountNext := B(seroutBitcountReg.asUInt - 1)
    }
  }

  when(skctlReg(7)) { serialOutNext := False }
  when(seroutHoldingLoad) { seroutHoldingFullNext := True }

  when(serialReset) {
    twotoneNext := False
    seroutBitcountNext := B(0, 4 bits)
    seroutShiftNext := B(0, 10 bits)
    seroutHoldingFullNext := False
    seroutClockNext := False
    seroutClockLastNext := False
    seroutActiveNext := False
  }

  // serial port input
  val sioIn1Synchronizer = new Synchronizer
  sioIn1Synchronizer.io.raw := io.sioIn1
  val sioIn1Synced = sioIn1Synchronizer.io.sync

  val sioIn2Synchronizer = new Synchronizer
  sioIn2Synchronizer.io.raw := io.sioIn2
  val sioIn2Synced = sioIn2Synchronizer.io.sync

  val sioIn3Synchronizer = new Synchronizer
  sioIn3Synchronizer.io.raw := io.sioIn3
  val sioIn3Synced = sioIn3Synchronizer.io.sync

  sioInNext := sioIn1Synced & sioIn2Synced & sioIn3Synced

  // serial input process
  serinClockNext     := serinClockReg
  serinClockLastNext := serinClockReg
  serinShiftNext     := serinShiftReg
  serinBitcountNext  := serinBitcountReg
  serinNext          := serinReg
  serialIpOverrunNext := serialIpOverrunReg
  serialIpFramingNext := serialIpFramingReg
  serialIpReadyInterrupt := False
  asyncSerialReset := False

  when(serinEnableDelayed) { serinClockNext := ~serinClockReg }
  when(skctlReg(4) & sioInReg & waitingForStartBit) {
    asyncSerialReset := True
    serinClockNext := True
  }

  when(serinClockLastReg & ~serinClockReg) {
    when((waitingForStartBit & ~sioInReg) | ~waitingForStartBit) {
      serinShiftNext := sioInReg ## serinShiftReg(9 downto 1)
      when(serinBitcountReg === B"x0") {
        serinNext := serinShiftReg(9 downto 2)
        serinBitcountNext := B"x9"
        serialIpReadyInterrupt := True
        when(~irqstReg(5)) { serialIpOverrunNext := True }
        when(~sioInReg)     { serialIpFramingNext := True }
      } otherwise {
        serinBitcountNext := B(serinBitcountReg.asUInt - 1)
      }
    }
  }

  when(skrestWrite) {
    serialIpOverrunNext := False
    serialIpFramingNext := False
  }

  when(serialReset) {
    serinClockNext := False
    serinBitcountNext := B"x9"
    serinShiftNext := B(0, 10 bits)
  }

  // serial clocks
  val clockInput = Bool()
  clockNext     := io.sioClockinIn
  clockSyncNext := clockValReg
  clockInput    := True

  switch(skctlReg(6 downto 4)) {
    is(B"000") {
      serinEnable  := ~clockSyncReg & clockValReg
      seroutEnable := ~clockSyncReg & clockValReg
    }
    is(B"001") {
      serinEnable  := audf3Pulse
      seroutEnable := ~clockSyncReg & clockValReg
    }
    is(B"010") {
      serinEnable  := audf3Pulse
      seroutEnable := audf3Pulse
      clockInput   := False
    }
    is(B"011") {
      serinEnable  := audf3Pulse
      seroutEnable := audf3Pulse
    }
    is(B"100") {
      serinEnable  := ~clockSyncReg & clockValReg
      seroutEnable := audf3Pulse
    }
    is(B"101") {
      serinEnable  := audf3Pulse
      seroutEnable := audf3Pulse
    }
    is(B"110") {
      serinEnable  := audf3Pulse
      seroutEnable := audf1Pulse
      clockInput   := False
    }
    is(B"111") {
      serinEnable  := audf3Pulse
      seroutEnable := audf1Pulse
    }
  }

  // keyboard overrun
  keyboardOverrunNext := keyboardOverrunReg
  when(otherKeyIrq & ~irqstReg(6)) { keyboardOverrunNext := True }
  when(skrestWrite) { keyboardOverrunNext := False }

  // keyboard scanner
  val pokeyKeyboardScanner1 = new PokeyKeyboardScanner
  if (CUSTOM_KEYBOARD_SCAN == 1) {
    pokeyKeyboardScanner1.io.enable := io.keyboardScanEnable
  } else {
    pokeyKeyboardScanner1.io.enable := enable15
  }
  pokeyKeyboardScanner1.io.keyboardResponse := io.keyboardResponse
  pokeyKeyboardScanner1.io.debounceDisable := ~skctlReg(0)
  pokeyKeyboardScanner1.io.scanEnable := skctlReg(1)
  io.keyboardScan := pokeyKeyboardScanner1.io.keyboardScan
  keyHeld     := pokeyKeyboardScanner1.io.keyHeld
  shiftHeld   := pokeyKeyboardScanner1.io.shiftHeld
  kbcode      := pokeyKeyboardScanner1.io.keycode
  otherKeyIrq := pokeyKeyboardScanner1.io.otherKeyIrq
  breakIrq    := pokeyKeyboardScanner1.io.breakIrq

  // POT scan
  pot0Next := pot0Reg
  pot1Next := pot1Reg
  pot2Next := pot2Reg
  pot3Next := pot3Reg
  pot4Next := pot4Reg
  pot5Next := pot5Reg
  pot6Next := pot6Reg
  pot7Next := pot7Reg
  allpotNext := allpotReg
  potResetNext := potResetReg
  potCounterNext := potCounterReg

  when((enable15 & ~skctlReg(2)) | (io.enable179 & skctlReg(2))) {
    potCounterNext := B(potCounterReg.asUInt + 1)
    when(potCounterReg === B"xE4") {
      potResetNext := True
      allpotNext := B(0, 8 bits)
    }
    when(~potResetReg) {
      when(~io.potIn(0)) { pot0Next := potCounterReg }
      when(~io.potIn(1)) { pot1Next := potCounterReg }
      when(~io.potIn(2)) { pot2Next := potCounterReg }
      when(~io.potIn(3)) { pot3Next := potCounterReg }
      when(~io.potIn(4)) { pot4Next := potCounterReg }
      when(~io.potIn(5)) { pot5Next := potCounterReg }
      when(~io.potIn(6)) { pot6Next := potCounterReg }
      when(~io.potIn(7)) { pot7Next := potCounterReg }
      allpotNext := allpotReg & ~io.potIn
    }
  }

  when(potgoWrite) {
    potCounterNext := B(0, 8 bits)
    potResetNext := False
    allpotNext := B"xFF"
  }

  // outputs
  io.irqNOut := irqNReg

  io.channel0Out := volumeChannel0Reg
  io.channel1Out := volumeChannel1Reg
  io.channel2Out := volumeChannel2Reg
  io.channel3Out := volumeChannel3Reg

  io.sioOut1 := sioOutReg
  io.sioOut2 := sioOutReg
  io.sioOut3 := sioOutReg

  io.sioClockout  := seroutClockReg
  io.sioClockinOe := ~clockInput
  io.sioClockinOut := serinClockReg

  io.potReset := potResetReg
}
