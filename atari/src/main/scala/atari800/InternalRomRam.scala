package atari800

import spinal.core._

// Internal ROM/RAM wrapper
// Conditionally instantiates OS ROM, BASIC ROM, and internal RAM based on generics
class InternalRomRam(internalRom: Int = 1, internalRam: Int = 16384) extends Component {
  val io = new Bundle {
    val clock   = in  Bool()
    val resetN  = in  Bool()

    val romAddr            = in  Bits(22 bits)
    val romWrEnable        = in  Bool()
    val romDataIn          = in  Bits(8 bits)
    val romRequestComplete = out Bool()
    val romRequest         = in  Bool()
    val romData            = out Bits(8 bits)

    val ramAddr            = in  Bits(19 bits)
    val ramWrEnable        = in  Bool()
    val ramDataIn          = in  Bits(8 bits)
    val ramRequestComplete = out Bool()
    val ramRequest         = in  Bool()
    val ramData            = out Bits(8 bits)
  }

  val romRequestReg = Reg(Bool()) init False
  val ramRequestReg = Reg(Bool()) init False
  val romRequestNext = Bool()
  val ramRequestNext = Bool()

  romRequestReg := romRequestNext
  ramRequestReg := ramRequestNext

  // RAM defaults (may be overridden if internalRam > 0)
  io.ramData := B(0xFF, 8 bits)
  io.ramRequestComplete := True
  ramRequestNext := False

  // =========================================================================
  // ROM section
  // =========================================================================
  if (internalRom == 4) {  // if/else if chain: only one ROM block generates hardware
    // 5200 OS: f000-ffff (4K, using 2K ROM)
    val rom4 = new Os5200
    rom4.io.clock   := io.clock
    rom4.io.address := io.romAddr(10 downto 0).asUInt
    io.romData := rom4.io.q
    romRequestNext := io.romRequest & ~io.romWrEnable
    io.romRequestComplete := romRequestReg
  } else if (internalRom == 3) {
    // d800-dfff (2K) + e000-ffff (8K)
    val rom2 = new Os2
    rom2.io.clock   := io.clock
    rom2.io.address := io.romAddr(10 downto 0).asUInt

    val rom10 = new Os8
    rom10.io.clock   := io.clock
    rom10.io.address := io.romAddr(12 downto 0).asUInt

    switch(io.romAddr(13 downto 11)) {
      is(B"011") { io.romData := rom2.io.q }
      is(B"100") { io.romData := rom10.io.q }
      is(B"101") { io.romData := rom10.io.q }
      is(B"110") { io.romData := rom10.io.q }
      is(B"111") { io.romData := rom10.io.q }
      default    { io.romData := B(0xFF, 8 bits) }
    }

    io.romRequestComplete := romRequestReg
    romRequestNext := io.romRequest & ~io.romWrEnable
  } else if (internalRom == 2) {
    // 16K OS loop variant
    val rom16a = new Os16Loop
    rom16a.io.clock   := io.clock
    rom16a.io.address := io.romAddr(13 downto 0).asUInt
    io.romData := rom16a.io.q
    io.romRequestComplete := romRequestReg
    romRequestNext := io.romRequest & ~io.romWrEnable
  } else if (internalRom == 1) {
    // 16K OS + 8K BASIC (writable for DMA loading)
    val rom16a = new Os16
    rom16a.io.clock   := io.clock
    rom16a.io.address := io.romAddr(13 downto 0).asUInt

    val basic1 = new Basic
    basic1.io.clock   := io.clock
    basic1.io.address := io.romAddr(12 downto 0).asUInt

    val romweTemp = io.romWrEnable & io.romRequest
    val osRomweTemp = Bool()
    val basicRomweTemp = Bool()

    // Default: OS ROM selected
    osRomweTemp := romweTemp
    basicRomweTemp := False
    io.romData := rom16a.io.q

    when(io.romAddr(15)) {
      // BASIC ROM selected
      io.romData := basic1.io.q
      osRomweTemp := False
      basicRomweTemp := romweTemp
    }

    rom16a.io.we   := osRomweTemp
    rom16a.io.data := io.romDataIn
    basic1.io.we   := basicRomweTemp
    basic1.io.data := io.romDataIn

    romRequestNext := io.romRequest & ~io.romWrEnable
    io.romRequestComplete := romweTemp | romRequestReg
  } else {  // internalRom == 0 or any other value
    io.romData := B(0, 8 bits)
    io.romRequestComplete := False
    romRequestNext := False
  }

  // =========================================================================
  // RAM section
  // =========================================================================
  if (internalRam > 0) {
    val ramweTemp = io.ramWrEnable & io.ramRequest

    val ramInt = new GenericRamInfer(ADDRESS_WIDTH = 19, SPACE = internalRam, DATA_WIDTH = 8)
    ramInt.io.address := io.ramAddr
    ramInt.io.data    := io.ramDataIn
    ramInt.io.we      := ramweTemp
    io.ramData := ramInt.io.q

    ramRequestNext := io.ramRequest & ~io.ramWrEnable
    io.ramRequestComplete := ramweTemp | ramRequestReg
  }
  // else: defaults (ramRequestComplete=True, ramData=0xFF) already set above
}
