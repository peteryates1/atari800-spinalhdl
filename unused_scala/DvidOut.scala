package atari800

import spinal.core._

// DVI-D Output Module
// Takes parallel RGB + sync and outputs serialized TMDS on 4 pairs
// Uses two ALTDDIO_OUT instances: one for P (normal), one for N (inverted)
class DvidOut extends Component {
  val io = new Bundle {
    val clkPixel = in  Bool()   // pixel clock (~57 MHz)
    val clkTmds  = in  Bool()   // 5x pixel clock (~283 MHz, DDR gives 10x)

    val red      = in  Bits(8 bits)
    val green    = in  Bits(8 bits)
    val blue     = in  Bits(8 bits)
    val hsync    = in  Bool()
    val vsync    = in  Bool()
    val de       = in  Bool()   // data enable (active video)

    val tmdsD0P  = out Bool()   // blue + hsync/vsync
    val tmdsD0N  = out Bool()
    val tmdsD1P  = out Bool()   // green
    val tmdsD1N  = out Bool()
    val tmdsD2P  = out Bool()   // red
    val tmdsD2N  = out Bool()
    val tmdsClkP = out Bool()   // pixel clock
    val tmdsClkN = out Bool()
  }

  // Pixel clock domain for TMDS encoders
  val pixelDomain = ClockDomain(
    clock = io.clkPixel,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  // TMDS clock domain for serializer
  val tmdsDomain = ClockDomain(
    clock = io.clkTmds,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  // TMDS encoded words
  val tmdsCh0, tmdsCh1, tmdsCh2 = Bits(10 bits)

  // TMDS encoders in pixel clock domain
  val pixelArea = new ClockingArea(pixelDomain) {
    val encBlue = new TmdsEncoder
    encBlue.io.data   := io.blue
    encBlue.io.ctrl   := io.vsync ## io.hsync
    encBlue.io.dataEn := io.de
    tmdsCh0 := encBlue.io.tmdsOut

    val encGreen = new TmdsEncoder
    encGreen.io.data   := io.green
    encGreen.io.ctrl   := B"00"
    encGreen.io.dataEn := io.de
    tmdsCh1 := encGreen.io.tmdsOut

    val encRed = new TmdsEncoder
    encRed.io.data   := io.red
    encRed.io.ctrl   := B"00"
    encRed.io.dataEn := io.de
    tmdsCh2 := encRed.io.tmdsOut

    // Latch TMDS words
    val ch0Lat = RegNext(tmdsCh0) init 0
    val ch1Lat = RegNext(tmdsCh1) init 0
    val ch2Lat = RegNext(tmdsCh2) init 0
  }

  // Serializer in TMDS clock domain
  val tmdsArea = new ClockingArea(tmdsDomain) {
    val shift0H = Reg(Bits(5 bits)) init 0
    val shift0L = Reg(Bits(5 bits)) init 0
    val shift1H = Reg(Bits(5 bits)) init 0
    val shift1L = Reg(Bits(5 bits)) init 0
    val shift2H = Reg(Bits(5 bits)) init 0
    val shift2L = Reg(Bits(5 bits)) init 0
    val shiftCH = Reg(Bits(5 bits)) init 0
    val shiftCL = Reg(Bits(5 bits)) init 0
    val shiftCnt = Reg(UInt(3 bits)) init 0

    // Helper: extract even/odd bits for DDR (LSB first)
    def evenBits(v: Bits): Bits = v(8) ## v(6) ## v(4) ## v(2) ## v(0)
    def oddBits(v: Bits): Bits  = v(9) ## v(7) ## v(5) ## v(3) ## v(1)

    when(shiftCnt === 4) {
      shiftCnt := 0
      // Load new TMDS words split into DDR halves
      shift0H := evenBits(pixelArea.ch0Lat)
      shift0L := oddBits(pixelArea.ch0Lat)
      shift1H := evenBits(pixelArea.ch1Lat)
      shift1L := oddBits(pixelArea.ch1Lat)
      shift2H := evenBits(pixelArea.ch2Lat)
      shift2L := oddBits(pixelArea.ch2Lat)
      // Clock pattern: 0000011111 -> h=01010, l=00011
      shiftCH := B"01010"
      shiftCL := B"00011"
    } otherwise {
      shiftCnt := shiftCnt + 1
      shift0H := B"0" ## shift0H(4 downto 1)
      shift0L := B"0" ## shift0L(4 downto 1)
      shift1H := B"0" ## shift1H(4 downto 1)
      shift1L := B"0" ## shift1L(4 downto 1)
      shift2H := B"0" ## shift2H(4 downto 1)
      shift2L := B"0" ## shift2L(4 downto 1)
      shiftCH := B"0" ## shiftCH(4 downto 1)
      shiftCL := B"0" ## shiftCL(4 downto 1)
    }

    val ddrH = shiftCH(0) ## shift2H(0) ## shift1H(0) ## shift0H(0)
    val ddrL = shiftCL(0) ## shift2L(0) ## shift1L(0) ## shift0L(0)
  }

  // ALTDDIO_OUT for P (positive) outputs
  val ddrP = new AltddioOut(4)
  ddrP.io.datainH   := tmdsArea.ddrH
  ddrP.io.datainL   := tmdsArea.ddrL
  ddrP.io.outclock  := io.clkTmds

  // ALTDDIO_OUT for N (negative/inverted) outputs
  val ddrN = new AltddioOut(4)
  ddrN.io.datainH   := ~tmdsArea.ddrH
  ddrN.io.datainL   := ~tmdsArea.ddrL
  ddrN.io.outclock  := io.clkTmds

  io.tmdsD0P  := ddrP.io.dataout(0)
  io.tmdsD0N  := ddrN.io.dataout(0)
  io.tmdsD1P  := ddrP.io.dataout(1)
  io.tmdsD1N  := ddrN.io.dataout(1)
  io.tmdsD2P  := ddrP.io.dataout(2)
  io.tmdsD2N  := ddrN.io.dataout(2)
  io.tmdsClkP := ddrP.io.dataout(3)
  io.tmdsClkN := ddrN.io.dataout(3)
}
