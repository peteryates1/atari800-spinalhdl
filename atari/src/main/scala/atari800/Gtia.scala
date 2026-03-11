package atari800

import spinal.core._

class Gtia extends Component {
  val io = new Bundle {
    val addr                = in  Bits(5 bits)
    val cpuDataIn           = in  Bits(8 bits)
    val wrEn                = in  Bool()
    val memoryDataIn        = in  Bits(8 bits)
    val anticFetch          = in  Bool()
    val cpuEnableOriginal   = in  Bool()
    val enable179           = in  Bool()
    val pal                 = in  Bool()
    val colourClockOriginal = in  Bool()
    val colourClock         = in  Bool()
    val colourClockHighres  = in  Bool()
    val an                  = in  Bits(3 bits)
    val consolIn            = in  Bits(4 bits)
    val consolOut           = out Bits(4 bits)
    val trig                = in  Bits(4 bits)
    val dataOut             = out Bits(8 bits)
    val colourOut           = out Bits(8 bits)
    val vsync               = out Bool()
    val hsync               = out Bool()
    val csync               = out Bool()
    val blank               = out Bool()
    val burst               = out Bool()
    val startOfField        = out Bool()
    val oddLine             = out Bool()
  }

  // address decoder
  val decodeAddr1 = new CompleteAddressDecoder(width = 5)
  decodeAddr1.io.addrIn := io.addr
  val addrDecoded = decodeAddr1.io.addrDecoded

  // registers - positions
  val hposp0RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp1RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp2RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp3RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm0RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm1RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm2RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm3RawReg = Reg(Bits(8 bits)) init B(0, 8 bits)

  val hposp0SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp1SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp2SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposp3SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm0SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm1SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm2SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hposm3SnapReg = Reg(Bits(8 bits)) init B(0, 8 bits)

  // sizes
  val sizep0RawReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep1RawReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep2RawReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep3RawReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizemRawReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  val sizep0SnapReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep1SnapReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep2SnapReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizep3SnapReg = Reg(Bits(2 bits)) init B(0, 2 bits)
  val sizemSnapReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  // graphics
  val grafp0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val grafp1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val grafp2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val grafp3Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val grafmReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  // colours (7 downto 1 only)
  val colpm0RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm1RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm2RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm3RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf0RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf1RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf2RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf3RawReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colbkRawReg  = Reg(Bits(7 bits)) init B(0, 7 bits)

  val colpm0SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm1SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm2SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpm3SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf0SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf1SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf2SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colpf3SnapReg = Reg(Bits(7 bits)) init B(0, 7 bits)
  val colbkSnapReg  = Reg(Bits(7 bits)) init B(0, 7 bits)

  val priorRawReg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val priorSnapReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  val vdelayReg       = Reg(Bits(8 bits)) init B(0, 8 bits)
  val gractlReg       = Reg(Bits(5 bits)) init B(0, 5 bits)
  val consolOutputReg = Reg(Bits(4 bits)) init B"xF"

  val colourReg   = Reg(Bits(8 bits)) init B(0, 8 bits)
  val hrcolourReg = Reg(Bits(8 bits)) init B(0, 8 bits)

  val csyncValReg = Reg(Bool()) init False
  val vsyncValReg = Reg(Bool()) init False
  val hsyncValReg = Reg(Bool()) init False
  val burstValReg = Reg(Bool()) init False
  val hblankReg   = Reg(Bool()) init False

  val anPrevReg  = Reg(Bits(3 bits)) init B(0, 3 bits)
  val anPrev2Reg = Reg(Bits(3 bits)) init B(0, 3 bits)
  val anPrev3Reg = Reg(Bits(3 bits)) init B(0, 3 bits)

  val highresReg  = Reg(Bool()) init False
  val activeHrReg = Reg(Bits(2 bits)) init B(0, 2 bits)

  val trigReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val oddScanlineReg = Reg(Bool()) init False

  val PMG_DMA_MISSILE     = B"000"
  val PMG_DMA_PLAYER0     = B"001"
  val PMG_DMA_PLAYER1     = B"010"
  val PMG_DMA_PLAYER2     = B"011"
  val PMG_DMA_PLAYER3     = B"100"
  val PMG_DMA_DONE        = B"101"
  val PMG_DMA_INSTRUCTION = B"110"

  val pmgDmaStateReg = Reg(Bits(3 bits)) init PMG_DMA_DONE

  // collisions
  val m0pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m1pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m2pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m3pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m0plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m1plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m2plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val m3plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p0pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p1pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p2pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p3pfReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p0plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p1plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p2plReg = Reg(Bits(4 bits)) init B(0, 4 bits)
  val p3plReg = Reg(Bits(4 bits)) init B(0, 4 bits)

  val activeBkModifyReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val activeBkValidReg  = Reg(Bits(8 bits)) init B(0, 8 bits)

  val hposAltReg = Reg(Bool()) init False

  // next signals
  val hposp0RawNext = Bits(8 bits); val hposp1RawNext = Bits(8 bits)
  val hposp2RawNext = Bits(8 bits); val hposp3RawNext = Bits(8 bits)
  val hposm0RawNext = Bits(8 bits); val hposm1RawNext = Bits(8 bits)
  val hposm2RawNext = Bits(8 bits); val hposm3RawNext = Bits(8 bits)
  val sizep0RawNext = Bits(2 bits); val sizep1RawNext = Bits(2 bits)
  val sizep2RawNext = Bits(2 bits); val sizep3RawNext = Bits(2 bits)
  val sizemRawNext  = Bits(8 bits)
  val grafp0Next = Bits(8 bits); val grafp1Next = Bits(8 bits)
  val grafp2Next = Bits(8 bits); val grafp3Next = Bits(8 bits)
  val grafmNext  = Bits(8 bits)
  val colpm0RawNext = Bits(7 bits); val colpm1RawNext = Bits(7 bits)
  val colpm2RawNext = Bits(7 bits); val colpm3RawNext = Bits(7 bits)
  val colpf0RawNext = Bits(7 bits); val colpf1RawNext = Bits(7 bits)
  val colpf2RawNext = Bits(7 bits); val colpf3RawNext = Bits(7 bits)
  val colbkRawNext  = Bits(7 bits)
  val priorRawNext  = Bits(8 bits)
  val vdelayNext    = Bits(8 bits)
  val gractlNext    = Bits(5 bits)
  val consolOutputNext = Bits(4 bits)
  val colourNext   = Bits(8 bits)
  val hrcolourNext = Bits(8 bits)
  val csyncNext = Bool(); val vsyncNext = Bool(); val hsyncNext = Bool()
  val burstNext = Bool(); val hblankNext = Bool()
  val anPrevNext = Bits(3 bits); val anPrev2Next = Bits(3 bits); val anPrev3Next = Bits(3 bits)
  val highresNext = Bool(); val activeHrNext = Bits(2 bits)
  val trigNext = Bits(4 bits); val oddScanlineNext = Bool()
  val pmgDmaStateNext = Bits(3 bits)
  val m0pfNext = Bits(4 bits); val m1pfNext = Bits(4 bits)
  val m2pfNext = Bits(4 bits); val m3pfNext = Bits(4 bits)
  val m0plNext = Bits(4 bits); val m1plNext = Bits(4 bits)
  val m2plNext = Bits(4 bits); val m3plNext = Bits(4 bits)
  val p0pfNext = Bits(4 bits); val p1pfNext = Bits(4 bits)
  val p2pfNext = Bits(4 bits); val p3pfNext = Bits(4 bits)
  val p0plNext = Bits(4 bits); val p1plNext = Bits(4 bits)
  val p2plNext = Bits(4 bits); val p3plNext = Bits(4 bits)
  val activeBkModifyNext = Bits(8 bits); val activeBkValidNext = Bits(8 bits)
  val hposAltNext = Bool()

  val hposp0SnapNext = Bits(8 bits); val hposp1SnapNext = Bits(8 bits)
  val hposp2SnapNext = Bits(8 bits); val hposp3SnapNext = Bits(8 bits)
  val hposm0SnapNext = Bits(8 bits); val hposm1SnapNext = Bits(8 bits)
  val hposm2SnapNext = Bits(8 bits); val hposm3SnapNext = Bits(8 bits)
  val sizep0SnapNext = Bits(2 bits); val sizep1SnapNext = Bits(2 bits)
  val sizep2SnapNext = Bits(2 bits); val sizep3SnapNext = Bits(2 bits)
  val sizemSnapNext  = Bits(8 bits)
  val colpm0SnapNext = Bits(7 bits); val colpm1SnapNext = Bits(7 bits)
  val colpm2SnapNext = Bits(7 bits); val colpm3SnapNext = Bits(7 bits)
  val colpf0SnapNext = Bits(7 bits); val colpf1SnapNext = Bits(7 bits)
  val colpf2SnapNext = Bits(7 bits); val colpf3SnapNext = Bits(7 bits)
  val colbkSnapNext  = Bits(7 bits)
  val priorSnapNext  = Bits(8 bits)

  // register update
  hposp0RawReg := hposp0RawNext; hposp1RawReg := hposp1RawNext
  hposp2RawReg := hposp2RawNext; hposp3RawReg := hposp3RawNext
  hposm0RawReg := hposm0RawNext; hposm1RawReg := hposm1RawNext
  hposm2RawReg := hposm2RawNext; hposm3RawReg := hposm3RawNext
  sizep0RawReg := sizep0RawNext; sizep1RawReg := sizep1RawNext
  sizep2RawReg := sizep2RawNext; sizep3RawReg := sizep3RawNext
  sizemRawReg  := sizemRawNext
  grafp0Reg := grafp0Next; grafp1Reg := grafp1Next
  grafp2Reg := grafp2Next; grafp3Reg := grafp3Next
  grafmReg  := grafmNext
  colpm0RawReg := colpm0RawNext; colpm1RawReg := colpm1RawNext
  colpm2RawReg := colpm2RawNext; colpm3RawReg := colpm3RawNext
  colpf0RawReg := colpf0RawNext; colpf1RawReg := colpf1RawNext
  colpf2RawReg := colpf2RawNext; colpf3RawReg := colpf3RawNext
  colbkRawReg  := colbkRawNext
  priorRawReg  := priorRawNext
  vdelayReg    := vdelayNext
  gractlReg    := gractlNext
  consolOutputReg := consolOutputNext
  colourReg   := colourNext; hrcolourReg := hrcolourNext
  csyncValReg := csyncNext; vsyncValReg := vsyncNext; hsyncValReg := hsyncNext
  burstValReg := burstNext; hblankReg   := hblankNext
  anPrevReg := anPrevNext; anPrev2Reg := anPrev2Next; anPrev3Reg := anPrev3Next
  highresReg := highresNext; activeHrReg := activeHrNext
  trigReg := trigNext; oddScanlineReg := oddScanlineNext
  pmgDmaStateReg := pmgDmaStateNext
  m0pfReg := m0pfNext; m1pfReg := m1pfNext; m2pfReg := m2pfNext; m3pfReg := m3pfNext
  m0plReg := m0plNext; m1plReg := m1plNext; m2plReg := m2plNext; m3plReg := m3plNext
  p0pfReg := p0pfNext; p1pfReg := p1pfNext; p2pfReg := p2pfNext; p3pfReg := p3pfNext
  p0plReg := p0plNext; p1plReg := p1plNext; p2plReg := p2plNext; p3plReg := p3plNext
  activeBkModifyReg := activeBkModifyNext; activeBkValidReg := activeBkValidNext
  hposp0SnapReg := hposp0SnapNext; hposp1SnapReg := hposp1SnapNext
  hposp2SnapReg := hposp2SnapNext; hposp3SnapReg := hposp3SnapNext
  hposm0SnapReg := hposm0SnapNext; hposm1SnapReg := hposm1SnapNext
  hposm2SnapReg := hposm2SnapNext; hposm3SnapReg := hposm3SnapNext
  sizep0SnapReg := sizep0SnapNext; sizep1SnapReg := sizep1SnapNext
  sizep2SnapReg := sizep2SnapNext; sizep3SnapReg := sizep3SnapNext
  sizemSnapReg  := sizemSnapNext
  colpm0SnapReg := colpm0SnapNext; colpm1SnapReg := colpm1SnapNext
  colpm2SnapReg := colpm2SnapNext; colpm3SnapReg := colpm3SnapNext
  colpf0SnapReg := colpf0SnapNext; colpf1SnapReg := colpf1SnapNext
  colpf2SnapReg := colpf2SnapNext; colpf3SnapReg := colpf3SnapNext
  colbkSnapReg  := colbkSnapNext
  priorSnapReg  := priorSnapNext
  hposAltReg := hposAltNext

  // delayed registers (from delay lines, directly assigned, or wide_delay_line)
  val hposp0DelayedReg = Bits(8 bits); val hposp1DelayedReg = Bits(8 bits)
  val hposp2DelayedReg = Bits(8 bits); val hposp3DelayedReg = Bits(8 bits)
  val hposm0DelayedReg = Bits(8 bits); val hposm1DelayedReg = Bits(8 bits)
  val hposm2DelayedReg = Bits(8 bits); val hposm3DelayedReg = Bits(8 bits)
  val sizep0DelayedReg = Bits(2 bits); val sizep1DelayedReg = Bits(2 bits)
  val sizep2DelayedReg = Bits(2 bits); val sizep3DelayedReg = Bits(2 bits)
  val sizemDelayedReg  = Bits(8 bits)
  val priorDelayedReg  = Bits(8 bits)
  val priorDelayed2Reg = Bits(2 bits)
  val colbkDelayedReg  = Bits(7 bits)
  val colpm0DelayedReg = Bits(7 bits); val colpm1DelayedReg = Bits(7 bits)
  val colpm2DelayedReg = Bits(7 bits); val colpm3DelayedReg = Bits(7 bits)
  val colpf0DelayedReg = Bits(7 bits); val colpf1DelayedReg = Bits(7 bits)
  val colpf2DelayedReg = Bits(7 bits); val colpf3DelayedReg = Bits(7 bits)

  // hpos alt
  hposAltNext := hposAltReg
  val resetCounter     = Bool()
  val counterLoadValue = Bits(8 bits)
  when(io.colourClock) { hposAltNext := ~hposAltReg }
  when(resetCounter)   { hposAltNext := False }

  // active signals
  val visibleLive = Bool()
  val activeBkLive  = Bool()
  val activePf0Live = Bool(); val activePf1Live = Bool()
  val activePf2Live = Bool(); val activePf2CollisionLive = Bool()
  val activePf3Live = Bool(); val activePf3CollisionLive = Bool()
  val activePm0Live = Bool(); val activePm1Live = Bool()
  val activePm2Live = Bool(); val activePm3Live = Bool()
  val activeP0Live = Bool(); val activeP1Live = Bool()
  val activeP2Live = Bool(); val activeP3Live = Bool()
  val activeM0Live = Bool(); val activeM1Live = Bool()
  val activeM2Live = Bool(); val activeM3Live = Bool()

  // hpos counter
  val hposReg = Bits(8 bits)
  val counterHpos = new SimpleCounter(COUNT_WIDTH = 8)
  counterHpos.io.increment := io.colourClockOriginal
  counterHpos.io.load      := resetCounter
  counterHpos.io.loadValue := counterLoadValue
  hposReg := counterHpos.io.currentValue

  // decode ANTIC input
  hblankNext := hblankReg
  resetCounter := False
  counterLoadValue := B(0, 8 bits)
  vsyncNext := vsyncValReg
  oddScanlineNext := oddScanlineReg
  io.startOfField := False
  highresNext := highresReg
  anPrevNext := anPrevReg; anPrev2Next := anPrev2Reg; anPrev3Next := anPrev3Reg
  visibleLive := False
  activeHrNext := activeHrReg
  activeBkModifyNext := activeBkModifyReg
  activeBkValidNext := activeBkValidReg
  activeBkLive := False
  activePf0Live := False; activePf1Live := False
  activePf2Live := False; activePf2CollisionLive := False
  activePf3Live := False; activePf3CollisionLive := False
  activePm0Live := False; activePm1Live := False
  activePm2Live := False; activePm3Live := False

  when(io.colourClock) {
    visibleLive := True
    vsyncNext := False
    hblankNext := False
    anPrevNext := io.an; anPrev2Next := anPrevReg; anPrev3Next := anPrev2Reg

    activePm0Live := activeP0Live | (activeM0Live & ~priorDelayedReg(4))
    activePm1Live := activeP1Live | (activeM1Live & ~priorDelayedReg(4))
    activePm2Live := activeP2Live | (activeM2Live & ~priorDelayedReg(4))
    activePm3Live := activeP3Live | (activeM3Live & ~priorDelayedReg(4))

    activeBkModifyNext := B(0, 8 bits)
    activeBkValidNext  := B"xFF"
    activeHrNext := B(0, 2 bits)

    when(highresReg & (priorDelayedReg(7 downto 6) === B"00")) {
      when(io.an(2)) { activeHrNext := io.an(1 downto 0) }
      activeBkLive := ~io.an(2) & ~io.an(1) & ~io.an(0)
      activePf2Live := io.an(2)
      activePf2CollisionLive := io.an(2) & (io.an(1) | io.an(0))
    } otherwise {
      switch(priorDelayedReg(7 downto 6)) {
        is(B"00") { // normal mode
          activeBkLive  := ~io.an(2) & ~io.an(1) & ~io.an(0)
          activePf0Live := io.an(2) & ~io.an(1) & ~io.an(0)
          activePf1Live := io.an(2) & ~io.an(1) & io.an(0)
          activePf2Live := io.an(2) & io.an(1) & ~io.an(0)
          activePf2CollisionLive := io.an(2) & io.an(1) & ~io.an(0)
          activePf3CollisionLive := io.an(2) & io.an(1) & io.an(0)
        }
        is(B"01") { // 16 luminance
          activeBkLive := True
          when(hposAltReg) {
            activeBkModifyNext(3 downto 0) := anPrevReg(1 downto 0) ## io.an(1 downto 0)
          } otherwise {
            activeBkModifyNext(3 downto 0) := activeBkModifyReg(3 downto 0)
          }
        }
        is(B"10") { // 9 colour
          when(hposAltReg) {
            val nineColourKey = anPrevReg(1 downto 0) ## io.an(1 downto 0)
            switch(nineColourKey) {
              is(B"0000") { activePm0Live := True }
              is(B"0001") { activePm1Live := True }
              is(B"0010") { activePm2Live := True }
              is(B"0011") { activePm3Live := True }
              is(B"0100", B"1100") { activePf0Live := io.an(2); activeBkLive := ~io.an(2) }
              is(B"0101", B"1101") { activePf1Live := io.an(2); activeBkLive := ~io.an(2) }
              is(B"0110", B"1110") { activePf2Live := io.an(2); activePf2CollisionLive := io.an(2); activeBkLive := ~io.an(2) }
              is(B"0111", B"1111") { activePf3CollisionLive := io.an(2); activeBkLive := ~io.an(2) }
              default { activeBkLive := True }
            }
          } otherwise {
            val nineColourKey2 = anPrev2Reg(1 downto 0) ## anPrevReg(1 downto 0)
            switch(nineColourKey2) {
              is(B"0000") { activePm0Live := True }
              is(B"0001") { activePm1Live := True }
              is(B"0010") { activePm2Live := True }
              is(B"0011") { activePm3Live := True }
              is(B"0100", B"1100") { activePf0Live := anPrevReg(2); activeBkLive := ~anPrevReg(2) }
              is(B"0101", B"1101") { activePf1Live := anPrevReg(2); activeBkLive := ~anPrevReg(2) }
              is(B"0110", B"1110") { activePf2Live := anPrevReg(2); activePf2CollisionLive := anPrevReg(2); activeBkLive := ~anPrevReg(2) }
              is(B"0111", B"1111") { activePf3CollisionLive := anPrevReg(2); activeBkLive := ~anPrevReg(2) }
              default { activeBkLive := True }
            }
          }
        }
        is(B"11") { // 16 colour
          activeBkLive := True
          when(hposAltReg) {
            activeBkModifyNext(7 downto 4) := anPrevReg(1 downto 0) ## io.an(1 downto 0)
          } otherwise {
            activeBkModifyNext(7 downto 4) := activeBkModifyReg(7 downto 4)
          }
          when(activeBkModifyNext(7 downto 4) === B"0000") {
            activeBkValidNext(3 downto 0) := B"0000"
          }
        }
      }
    }

    when(priorDelayedReg(4)) {
      activePf3Live := activePf3CollisionLive | activeM0Live | activeM1Live | activeM2Live | activeM3Live
    } otherwise {
      activePf3Live := activePf3CollisionLive
    }

    when(~(priorDelayed2Reg === B"00")) {
      highresNext := False
    }

    // hblank
    when(anPrevReg(2 downto 1) === B"01") {
      hblankNext := True
      activeBkLive := False; activePf0Live := False; activePf1Live := False
      activePf2Live := False; activePf2CollisionLive := False
      activePf3Live := False; activePf3CollisionLive := False
      highresNext := anPrevReg(0)
      when(~hblankReg & ~vsyncValReg) {
        resetCounter := True
        counterLoadValue := B"xE0"
        oddScanlineNext := ~oddScanlineReg
      }
    }
    when(io.an(2 downto 1) === B"01") { visibleLive := False }

    // vsync
    when(anPrevReg === B"001") {
      activeBkLive := False; activePf0Live := False; activePf1Live := False
      activePf2Live := False; activePf2CollisionLive := False
      activePf3Live := False; activePf3CollisionLive := False
      vsyncNext := True; oddScanlineNext := False; visibleLive := False
      io.startOfField := ~vsyncValReg
    }

    // during vblank reset counter
    when((hposReg === B"xE3") & io.colourClockOriginal) {
      resetCounter := True
      counterLoadValue := B(0, 8 bits)
    }
  }

  // hsync/csync/burst generation
  val hsyncStart = Bool(); val hsyncEnd = Bool()
  val csyncStart = Bool(); val csyncEnd = Bool()
  val burstStart = Bool(); val burstEnd = Bool()

  hsyncStart := False; hsyncNext := hsyncValReg
  csyncStart := False; csyncNext := csyncValReg
  burstStart := False; burstNext := burstValReg

  when(hposReg.asUInt === U"xD4") { csyncStart := vsyncValReg; csyncNext := vsyncValReg }
  when(hposReg.asUInt === U"x0") {
    csyncStart := ~vsyncValReg; csyncNext := ~vsyncValReg
    hsyncStart := True; hsyncNext := True
  }
  when((hposReg.asUInt === U"x14") & ~vsyncValReg) { burstStart := True; burstNext := True }
  when(hsyncEnd) { hsyncNext := False }
  when(csyncEnd) { csyncNext := False }
  when(burstEnd) { burstNext := False }
  when(~vsyncNext & vsyncValReg) { csyncNext := False }

  val hsyncDelay = new DelayLine(COUNT = 15)
  hsyncDelay.io.syncReset := False; hsyncDelay.io.dataIn := hsyncStart; hsyncDelay.io.enable := io.colourClockOriginal
  hsyncEnd := hsyncDelay.io.dataOut

  val csyncDelay = new DelayLine(COUNT = 15)
  csyncDelay.io.syncReset := False; csyncDelay.io.dataIn := csyncStart; csyncDelay.io.enable := io.colourClockOriginal
  csyncEnd := csyncDelay.io.dataOut

  val burstDelay = new DelayLine(COUNT = 8)
  burstDelay.io.syncReset := False; burstDelay.io.dataIn := burstStart; burstDelay.io.enable := io.colourClockOriginal
  burstEnd := burstDelay.io.dataOut

  // PMG DMA
  val grafmDmaLoad = Bool(); val grafmDmaNext = Bits(8 bits)
  val grafp0DmaLoad = Bool(); val grafp0DmaNext = Bits(8 bits)
  val grafp1DmaLoad = Bool(); val grafp1DmaNext = Bits(8 bits)
  val grafp2DmaLoad = Bool(); val grafp2DmaNext = Bits(8 bits)
  val grafp3DmaLoad = Bool(); val grafp3DmaNext = Bits(8 bits)

  pmgDmaStateNext := pmgDmaStateReg
  grafmDmaLoad := False; grafmDmaNext := grafmReg
  grafp0DmaLoad := False; grafp0DmaNext := B(0, 8 bits)
  grafp1DmaLoad := False; grafp1DmaNext := B(0, 8 bits)
  grafp2DmaLoad := False; grafp2DmaNext := B(0, 8 bits)
  grafp3DmaLoad := False; grafp3DmaNext := B(0, 8 bits)

  when(hposReg === B"xE1") { pmgDmaStateNext := PMG_DMA_MISSILE }

  switch(pmgDmaStateReg) {
    is(PMG_DMA_MISSILE) {
      when(io.anticFetch & io.cpuEnableOriginal & hblankReg & ~visibleLive & (hposReg(7 downto 4) === B"0000")) {
        grafmDmaLoad := gractlReg(0)
        when(oddScanlineReg | ~vdelayReg(0)) { grafmDmaNext(1 downto 0) := io.memoryDataIn(1 downto 0) }
        when(oddScanlineReg | ~vdelayReg(1)) { grafmDmaNext(3 downto 2) := io.memoryDataIn(3 downto 2) }
        when(oddScanlineReg | ~vdelayReg(2)) { grafmDmaNext(5 downto 4) := io.memoryDataIn(5 downto 4) }
        when(oddScanlineReg | ~vdelayReg(3)) { grafmDmaNext(7 downto 6) := io.memoryDataIn(7 downto 6) }
        pmgDmaStateNext := PMG_DMA_INSTRUCTION
      }
    }
    is(PMG_DMA_INSTRUCTION) {
      when(io.cpuEnableOriginal) { pmgDmaStateNext := PMG_DMA_PLAYER0 }
    }
    is(PMG_DMA_PLAYER0) {
      when(io.cpuEnableOriginal) {
        grafp0DmaNext := io.memoryDataIn
        grafp0DmaLoad := gractlReg(1) & (oddScanlineReg | ~vdelayReg(4))
        pmgDmaStateNext := PMG_DMA_PLAYER1
      }
    }
    is(PMG_DMA_PLAYER1) {
      when(io.cpuEnableOriginal) {
        grafp1DmaNext := io.memoryDataIn
        grafp1DmaLoad := gractlReg(1) & (oddScanlineReg | ~vdelayReg(5))
        pmgDmaStateNext := PMG_DMA_PLAYER2
      }
    }
    is(PMG_DMA_PLAYER2) {
      when(io.cpuEnableOriginal) {
        grafp2DmaNext := io.memoryDataIn
        grafp2DmaLoad := gractlReg(1) & (oddScanlineReg | ~vdelayReg(6))
        pmgDmaStateNext := PMG_DMA_PLAYER3
      }
    }
    is(PMG_DMA_PLAYER3) {
      when(io.cpuEnableOriginal) {
        grafp3DmaNext := io.memoryDataIn
        grafp3DmaLoad := gractlReg(1) & (oddScanlineReg | ~vdelayReg(7))
        pmgDmaStateNext := PMG_DMA_DONE
      }
    }
  }

  // PMG display
  val player0 = new GtiaPlayer; player0.io.colourEnable := io.colourClockOriginal; player0.io.livePosition := hposReg; player0.io.playerPosition := hposp0DelayedReg; player0.io.size := sizep0DelayedReg; player0.io.bitmap := grafp0Reg; activeP0Live := player0.io.output
  val player1 = new GtiaPlayer; player1.io.colourEnable := io.colourClockOriginal; player1.io.livePosition := hposReg; player1.io.playerPosition := hposp1DelayedReg; player1.io.size := sizep1DelayedReg; player1.io.bitmap := grafp1Reg; activeP1Live := player1.io.output
  val player2 = new GtiaPlayer; player2.io.colourEnable := io.colourClockOriginal; player2.io.livePosition := hposReg; player2.io.playerPosition := hposp2DelayedReg; player2.io.size := sizep2DelayedReg; player2.io.bitmap := grafp2Reg; activeP2Live := player2.io.output
  val player3 = new GtiaPlayer; player3.io.colourEnable := io.colourClockOriginal; player3.io.livePosition := hposReg; player3.io.playerPosition := hposp3DelayedReg; player3.io.size := sizep3DelayedReg; player3.io.bitmap := grafp3Reg; activeP3Live := player3.io.output

  val grafmReg10Extended = grafmReg(1 downto 0) ## B"000000"
  val grafmReg32Extended = grafmReg(3 downto 2) ## B"000000"
  val grafmReg54Extended = grafmReg(5 downto 4) ## B"000000"
  val grafmReg76Extended = grafmReg(7 downto 6) ## B"000000"

  val missile0 = new GtiaPlayer; missile0.io.colourEnable := io.colourClockOriginal; missile0.io.livePosition := hposReg; missile0.io.playerPosition := hposm0DelayedReg; missile0.io.size := sizemDelayedReg(1 downto 0); missile0.io.bitmap := grafmReg10Extended; activeM0Live := missile0.io.output
  val missile1 = new GtiaPlayer; missile1.io.colourEnable := io.colourClockOriginal; missile1.io.livePosition := hposReg; missile1.io.playerPosition := hposm1DelayedReg; missile1.io.size := sizemDelayedReg(3 downto 2); missile1.io.bitmap := grafmReg32Extended; activeM1Live := missile1.io.output
  val missile2 = new GtiaPlayer; missile2.io.colourEnable := io.colourClockOriginal; missile2.io.livePosition := hposReg; missile2.io.playerPosition := hposm2DelayedReg; missile2.io.size := sizemDelayedReg(5 downto 4); missile2.io.bitmap := grafmReg54Extended; activeM2Live := missile2.io.output
  val missile3 = new GtiaPlayer; missile3.io.colourEnable := io.colourClockOriginal; missile3.io.livePosition := hposReg; missile3.io.playerPosition := hposm3DelayedReg; missile3.io.size := sizemDelayedReg(7 downto 6); missile3.io.bitmap := grafmReg76Extended; activeM3Live := missile3.io.output

  // priority
  val setP0 = Bool(); val setP1 = Bool(); val setP2 = Bool(); val setP3 = Bool()
  val setPf0 = Bool(); val setPf1 = Bool(); val setPf2 = Bool(); val setPf3 = Bool()
  val setBk = Bool()

  val priorityRules = new GtiaPriority
  priorityRules.io.colourEnable := io.colourClock
  priorityRules.io.prior := priorDelayedReg
  priorityRules.io.p0 := activePm0Live; priorityRules.io.p1 := activePm1Live
  priorityRules.io.p2 := activePm2Live; priorityRules.io.p3 := activePm3Live
  priorityRules.io.pf0 := activePf0Live; priorityRules.io.pf1 := activePf1Live
  priorityRules.io.pf2 := activePf2Live; priorityRules.io.pf3 := activePf3Live
  priorityRules.io.bk := activeBkLive
  setP0 := priorityRules.io.p0Out; setP1 := priorityRules.io.p1Out
  setP2 := priorityRules.io.p2Out; setP3 := priorityRules.io.p3Out
  setPf0 := priorityRules.io.pf0Out; setPf1 := priorityRules.io.pf1Out
  setPf2 := priorityRules.io.pf2Out; setPf3 := priorityRules.io.pf3Out
  setBk := priorityRules.io.bkOut

  // colour calculation
  val triggerSecondhalf = io.colourClockHighres & ~io.colourClock

  def fillBits(b: Bool): Bits = { val v = Bits(8 bits); for (i <- 0 until 8) v(i) := b; v }

  colourNext := colourReg; hrcolourNext := hrcolourReg
  when(triggerSecondhalf) { when(highresReg) { colourNext := hrcolourReg } }
  when(io.colourClock) {
    colourNext :=
      (((colbkDelayedReg ## False) | activeBkModifyNext) & activeBkValidNext & fillBits(setBk)) |
      ((colpf0DelayedReg ## False) & fillBits(setPf0)) |
      ((colpf1DelayedReg ## False) & fillBits(setPf1)) |
      ((colpf2DelayedReg ## False) & fillBits(setPf2)) |
      (((colpf3DelayedReg ## False) | activeBkModifyNext) & fillBits(setPf3)) |
      ((colpm0DelayedReg ## False) & fillBits(setP0)) |
      ((colpm1DelayedReg ## False) & fillBits(setP1)) |
      ((colpm2DelayedReg ## False) & fillBits(setP2)) |
      ((colpm3DelayedReg ## False) & fillBits(setP3))

    hrcolourNext :=
      (((colbkDelayedReg ## False) | activeBkModifyNext) & activeBkValidNext & fillBits(setBk)) |
      ((colpf0DelayedReg ## False) & fillBits(setPf0)) |
      ((colpf1DelayedReg ## False) & fillBits(setPf1)) |
      ((colpf2DelayedReg ## False) & fillBits(setPf2)) |
      (((colpf3DelayedReg ## False) | activeBkModifyNext) & fillBits(setPf3)) |
      ((colpm0DelayedReg ## False) & fillBits(setP0)) |
      ((colpm1DelayedReg ## False) & fillBits(setP1)) |
      ((colpm2DelayedReg ## False) & fillBits(setP2)) |
      ((colpm3DelayedReg ## False) & fillBits(setP3))

    when(~setBk & highresReg) {
      when(activeHrReg(1)) { colourNext(3 downto 0) := colpf1DelayedReg(3 downto 1) ## False }
      when(activeHrReg(0)) { hrcolourNext(3 downto 0) := colpf1DelayedReg(3 downto 1) ## False }
      when(activeHrReg(1) & gractlReg(4)) { colourNext(7 downto 4) := colpf1DelayedReg(6 downto 3) }
      when(activeHrReg(0) & gractlReg(4)) { hrcolourNext(7 downto 4) := colpf1DelayedReg(6 downto 3) }
    }
    when(~visibleLive) { colourNext := B(0, 8 bits); hrcolourNext := B(0, 8 bits) }
  }

  // collision detection
  m0pfNext := m0pfReg; m1pfNext := m1pfReg; m2pfNext := m2pfReg; m3pfNext := m3pfReg
  m0plNext := m0plReg; m1plNext := m1plReg; m2plNext := m2plReg; m3plNext := m3plReg
  p0pfNext := p0pfReg; p1pfNext := p1pfReg; p2pfNext := p2pfReg; p3pfNext := p3pfReg
  p0plNext := p0plReg; p1plNext := p1plReg; p2plNext := p2plReg; p3plNext := p3plReg

  val hitclrWrite = Bool()
  when(hitclrWrite) {
    m0pfNext := B(0, 4 bits); m1pfNext := B(0, 4 bits); m2pfNext := B(0, 4 bits); m3pfNext := B(0, 4 bits)
    m0plNext := B(0, 4 bits); m1plNext := B(0, 4 bits); m2plNext := B(0, 4 bits); m3plNext := B(0, 4 bits)
    p0pfNext := B(0, 4 bits); p1pfNext := B(0, 4 bits); p2pfNext := B(0, 4 bits); p3pfNext := B(0, 4 bits)
    p0plNext := B(0, 4 bits); p1plNext := B(0, 4 bits); p2plNext := B(0, 4 bits); p3plNext := B(0, 4 bits)
  } otherwise {
    when(visibleLive & io.colourClock) {
      val pfColl = activePf3CollisionLive ## activePf2CollisionLive ## activePf1Live ## activePf0Live
      val plColl = activeP3Live ## activeP2Live ## activeP1Live ## activeP0Live
      m0plNext := m0plReg | (fillBits(activeM0Live)(3 downto 0) & plColl)
      m1plNext := m1plReg | (fillBits(activeM1Live)(3 downto 0) & plColl)
      m2plNext := m2plReg | (fillBits(activeM2Live)(3 downto 0) & plColl)
      m3plNext := m3plReg | (fillBits(activeM3Live)(3 downto 0) & plColl)
      m0pfNext := m0pfReg | (fillBits(activeM0Live)(3 downto 0) & pfColl)
      m1pfNext := m1pfReg | (fillBits(activeM1Live)(3 downto 0) & pfColl)
      m2pfNext := m2pfReg | (fillBits(activeM2Live)(3 downto 0) & pfColl)
      m3pfNext := m3pfReg | (fillBits(activeM3Live)(3 downto 0) & pfColl)
      p0plNext := p0plReg | ((activeP0Live ## activeP0Live ## activeP0Live ## False) & plColl)
      p1plNext := p1plReg | ((activeP1Live ## activeP1Live ## False ## activeP1Live) & plColl)
      p2plNext := p2plReg | ((activeP2Live ## False ## activeP2Live ## activeP2Live) & plColl)
      p3plNext := p3plReg | ((False ## activeP3Live ## activeP3Live ## activeP3Live) & plColl)
      p0pfNext := p0pfReg | (fillBits(activeP0Live)(3 downto 0) & pfColl)
      p1pfNext := p1pfReg | (fillBits(activeP1Live)(3 downto 0) & pfColl)
      p2pfNext := p2pfReg | (fillBits(activeP2Live)(3 downto 0) & pfColl)
      p3pfNext := p3pfReg | (fillBits(activeP3Live)(3 downto 0) & pfColl)
    }
  }

  // Writes to registers
  hposp0RawNext := hposp0RawReg; hposp1RawNext := hposp1RawReg
  hposp2RawNext := hposp2RawReg; hposp3RawNext := hposp3RawReg
  hposm0RawNext := hposm0RawReg; hposm1RawNext := hposm1RawReg
  hposm2RawNext := hposm2RawReg; hposm3RawNext := hposm3RawReg
  sizep0RawNext := sizep0RawReg; sizep1RawNext := sizep1RawReg
  sizep2RawNext := sizep2RawReg; sizep3RawNext := sizep3RawReg
  sizemRawNext := sizemRawReg
  grafp0Next := grafp0Reg; grafp1Next := grafp1Reg
  grafp2Next := grafp2Reg; grafp3Next := grafp3Reg
  grafmNext := grafmReg
  colpm0RawNext := colpm0RawReg; colpm1RawNext := colpm1RawReg
  colpm2RawNext := colpm2RawReg; colpm3RawNext := colpm3RawReg
  colpf0RawNext := colpf0RawReg; colpf1RawNext := colpf1RawReg
  colpf2RawNext := colpf2RawReg; colpf3RawNext := colpf3RawReg
  colbkRawNext := colbkRawReg
  priorRawNext := priorRawReg; vdelayNext := vdelayReg
  gractlNext := gractlReg; consolOutputNext := consolOutputReg
  hitclrWrite := False

  when(grafmDmaLoad) { grafmNext := grafmDmaNext }
  when(grafp0DmaLoad) { grafp0Next := grafp0DmaNext }
  when(grafp1DmaLoad) { grafp1Next := grafp1DmaNext }
  when(grafp2DmaLoad) { grafp2Next := grafp2DmaNext }
  when(grafp3DmaLoad) { grafp3Next := grafp3DmaNext }

  when(io.wrEn) {
    when(addrDecoded(0))  { hposp0RawNext := io.cpuDataIn }
    when(addrDecoded(1))  { hposp1RawNext := io.cpuDataIn }
    when(addrDecoded(2))  { hposp2RawNext := io.cpuDataIn }
    when(addrDecoded(3))  { hposp3RawNext := io.cpuDataIn }
    when(addrDecoded(4))  { hposm0RawNext := io.cpuDataIn }
    when(addrDecoded(5))  { hposm1RawNext := io.cpuDataIn }
    when(addrDecoded(6))  { hposm2RawNext := io.cpuDataIn }
    when(addrDecoded(7))  { hposm3RawNext := io.cpuDataIn }
    when(addrDecoded(8))  { sizep0RawNext := io.cpuDataIn(1 downto 0) }
    when(addrDecoded(9))  { sizep1RawNext := io.cpuDataIn(1 downto 0) }
    when(addrDecoded(10)) { sizep2RawNext := io.cpuDataIn(1 downto 0) }
    when(addrDecoded(11)) { sizep3RawNext := io.cpuDataIn(1 downto 0) }
    when(addrDecoded(12)) { sizemRawNext := io.cpuDataIn }
    when(addrDecoded(13)) { grafp0Next := io.cpuDataIn }
    when(addrDecoded(14)) { grafp1Next := io.cpuDataIn }
    when(addrDecoded(15)) { grafp2Next := io.cpuDataIn }
    when(addrDecoded(16)) { grafp3Next := io.cpuDataIn }
    when(addrDecoded(17)) { grafmNext := io.cpuDataIn }
    when(addrDecoded(18)) { colpm0RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(19)) { colpm1RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(20)) { colpm2RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(21)) { colpm3RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(22)) { colpf0RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(23)) { colpf1RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(24)) { colpf2RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(25)) { colpf3RawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(26)) { colbkRawNext := io.cpuDataIn(7 downto 1) }
    when(addrDecoded(27)) { priorRawNext := io.cpuDataIn }
    when(addrDecoded(28)) { vdelayNext := io.cpuDataIn }
    when(addrDecoded(29)) { gractlNext := io.cpuDataIn(4 downto 0) }
    when(addrDecoded(30)) { hitclrWrite := True }
    when(addrDecoded(31)) { consolOutputNext := io.cpuDataIn(3 downto 0) }
  }

  // snapshot on enable_179
  hposp0SnapNext := hposp0SnapReg; hposp1SnapNext := hposp1SnapReg
  hposp2SnapNext := hposp2SnapReg; hposp3SnapNext := hposp3SnapReg
  hposm0SnapNext := hposm0SnapReg; hposm1SnapNext := hposm1SnapReg
  hposm2SnapNext := hposm2SnapReg; hposm3SnapNext := hposm3SnapReg
  sizep0SnapNext := sizep0SnapReg; sizep1SnapNext := sizep1SnapReg
  sizep2SnapNext := sizep2SnapReg; sizep3SnapNext := sizep3SnapReg
  sizemSnapNext := sizemSnapReg
  colpm0SnapNext := colpm0SnapReg; colpm1SnapNext := colpm1SnapReg
  colpm2SnapNext := colpm2SnapReg; colpm3SnapNext := colpm3SnapReg
  colpf0SnapNext := colpf0SnapReg; colpf1SnapNext := colpf1SnapReg
  colpf2SnapNext := colpf2SnapReg; colpf3SnapNext := colpf3SnapReg
  colbkSnapNext := colbkSnapReg; priorSnapNext := priorSnapReg

  when(io.enable179) {
    hposp0SnapNext := hposp0RawReg; hposp1SnapNext := hposp1RawReg
    hposp2SnapNext := hposp2RawReg; hposp3SnapNext := hposp3RawReg
    hposm0SnapNext := hposm0RawReg; hposm1SnapNext := hposm1RawReg
    hposm2SnapNext := hposm2RawReg; hposm3SnapNext := hposm3RawReg
    sizep0SnapNext := sizep0RawReg; sizep1SnapNext := sizep1RawReg
    sizep2SnapNext := sizep2RawReg; sizep3SnapNext := sizep3RawReg
    sizemSnapNext := sizemRawReg
    colpm0SnapNext := colpm0RawReg; colpm1SnapNext := colpm1RawReg
    colpm2SnapNext := colpm2RawReg; colpm3SnapNext := colpm3RawReg
    colpf0SnapNext := colpf0RawReg; colpf1SnapNext := colpf1RawReg
    colpf2SnapNext := colpf2RawReg; colpf3SnapNext := colpf3RawReg
    colbkSnapNext := colbkRawReg; priorSnapNext := priorRawReg
  }

  // delay lines for delayed regs
  priorDelayedReg(5 downto 0) := priorSnapReg(5 downto 0)

  val priorLongDelay = new WideDelayLine(COUNT = 1, WIDTH = 2)
  priorLongDelay.io.syncReset := False; priorLongDelay.io.dataIn := priorSnapReg(7 downto 6); priorLongDelay.io.enable := io.colourClockOriginal
  priorDelayedReg(7 downto 6) := priorLongDelay.io.dataOut

  val priorLongerDelay = new WideDelayLine(COUNT = 2, WIDTH = 2)
  priorLongerDelay.io.syncReset := False; priorLongerDelay.io.dataIn := priorSnapReg(7 downto 6); priorLongerDelay.io.enable := io.colourClockOriginal
  priorDelayed2Reg := priorLongerDelay.io.dataOut

  // direct assignments (no delay)
  colbkDelayedReg := colbkSnapReg
  colpm0DelayedReg := colpm0SnapReg; colpm1DelayedReg := colpm1SnapReg
  colpm2DelayedReg := colpm2SnapReg; colpm3DelayedReg := colpm3SnapReg
  colpf0DelayedReg := colpf0SnapReg; colpf1DelayedReg := colpf1SnapReg
  colpf2DelayedReg := colpf2SnapReg; colpf3DelayedReg := colpf3SnapReg

  // position delay lines (COUNT=3)
  for ((snapReg, delayedReg, name) <- Seq(
    (hposp0SnapReg, hposp0DelayedReg, "hposp0"), (hposp1SnapReg, hposp1DelayedReg, "hposp1"),
    (hposp2SnapReg, hposp2DelayedReg, "hposp2"), (hposp3SnapReg, hposp3DelayedReg, "hposp3"),
    (hposm0SnapReg, hposm0DelayedReg, "hposm0"), (hposm1SnapReg, hposm1DelayedReg, "hposm1"),
    (hposm2SnapReg, hposm2DelayedReg, "hposm2"), (hposm3SnapReg, hposm3DelayedReg, "hposm3")
  )) {
    val dl = new WideDelayLine(COUNT = 3, WIDTH = 8)
    dl.io.syncReset := False; dl.io.dataIn := snapReg; dl.io.enable := io.colourClockOriginal
    delayedReg := dl.io.dataOut
  }

  // size delay lines (COUNT=2)
  for ((snapReg, delayedReg, w) <- Seq(
    (sizep0SnapReg, sizep0DelayedReg, 2), (sizep1SnapReg, sizep1DelayedReg, 2),
    (sizep2SnapReg, sizep2DelayedReg, 2), (sizep3SnapReg, sizep3DelayedReg, 2),
    (sizemSnapReg, sizemDelayedReg, 8)
  )) {
    val dl = new WideDelayLine(COUNT = 2, WIDTH = w)
    dl.io.syncReset := False; dl.io.dataIn := snapReg; dl.io.enable := io.colourClockOriginal
    delayedReg := dl.io.dataOut
  }

  // joystick
  trigNext := io.trig
  when(gractlReg(2)) { trigNext := trigReg & io.trig }

  // read from registers
  io.dataOut := B"x0F"
  when(addrDecoded(0))  { io.dataOut := B"0000" ## m0pfReg }
  when(addrDecoded(1))  { io.dataOut := B"0000" ## m1pfReg }
  when(addrDecoded(2))  { io.dataOut := B"0000" ## m2pfReg }
  when(addrDecoded(3))  { io.dataOut := B"0000" ## m3pfReg }
  when(addrDecoded(4))  { io.dataOut := B"0000" ## p0pfReg }
  when(addrDecoded(5))  { io.dataOut := B"0000" ## p1pfReg }
  when(addrDecoded(6))  { io.dataOut := B"0000" ## p2pfReg }
  when(addrDecoded(7))  { io.dataOut := B"0000" ## p3pfReg }
  when(addrDecoded(8))  { io.dataOut := B"0000" ## m0plReg }
  when(addrDecoded(9))  { io.dataOut := B"0000" ## m1plReg }
  when(addrDecoded(10)) { io.dataOut := B"0000" ## m2plReg }
  when(addrDecoded(11)) { io.dataOut := B"0000" ## m3plReg }
  when(addrDecoded(12)) { io.dataOut := B"0000" ## p0plReg }
  when(addrDecoded(13)) { io.dataOut := B"0000" ## p1plReg }
  when(addrDecoded(14)) { io.dataOut := B"0000" ## p2plReg }
  when(addrDecoded(15)) { io.dataOut := B"0000" ## p3plReg }
  when(addrDecoded(16)) { io.dataOut := B"0000000" ## trigReg(0) }
  when(addrDecoded(17)) { io.dataOut := B"0000000" ## trigReg(1) }
  when(addrDecoded(18)) { io.dataOut := B"0000000" ## trigReg(2) }
  when(addrDecoded(19)) { io.dataOut := B"0000000" ## trigReg(3) }
  when(addrDecoded(20)) { io.dataOut := B"0000" ## ~(io.pal ## io.pal ## io.pal) ## B"1" }
  when(addrDecoded(31)) { io.dataOut := B"0000" ## (~io.consolIn & ~consolOutputReg) }

  // outputs
  io.colourOut := colourReg
  io.vsync     := vsyncValReg
  io.hsync     := hsyncValReg
  io.csync     := csyncValReg ^ vsyncValReg
  io.blank     := hblankReg | vsyncValReg
  io.burst     := burstValReg
  io.oddLine   := oddScanlineReg
  io.consolOut := consolOutputReg
}
