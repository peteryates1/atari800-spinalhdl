package retro.machines.atari
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._
import retro.link._

import spinal.core._
import java.nio.file.{Files, Paths}

// Internal ROM/RAM wrapper
// Loads OS and BASIC ROMs from .rom binary files at elaboration time.
// romDir: directory containing .rom files (default "roms")
// cartridgeRom: path to 8K/16K ROM file for cartridge slot (empty = none)
//
// BRAM budget: for internalRom=3 (Atari 800), cart ROM replaces the top of RAM
// so total BRAM stays constant.  Caller passes internalRam = total user address
// space (e.g. 49152 for 48K), and the RAM block is reduced by the cart file size.
class InternalRomRam(internalRom: Int = 1, internalRam: Int = 16384, cartridgeRom: String = "",
                     withBasic: Boolean = true, romDir: String = "roms") extends Component {
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

  // =========================================================================
  // ROM section
  // =========================================================================
  if (internalRom == 4) {
    // 5200 OS: f000-ffff (2K ROM)
    val rom4 = new FileRom(s"$romDir/atari5200.rom", 2048)
    rom4.io.clock   := io.clock
    rom4.io.address := io.romAddr(10 downto 0).asUInt
    rom4.io.we      := False
    rom4.io.data    := io.romDataIn
    io.romData := rom4.io.q
    romRequestNext := io.romRequest & ~io.romWrEnable
    io.romRequestComplete := romRequestReg
  } else if (internalRom == 3) {
    // Atari 800 OS: D800-DFFF (2K) + E000-FFFF (8K) + optional cartridge
    // Cart ROM replaces upper RAM — no BRAM wasted on double-allocation.
    val rom2 = new FileRom(s"$romDir/atarios2.rom", 2048)
    rom2.io.clock   := io.clock
    rom2.io.address := io.romAddr(10 downto 0).asUInt
    rom2.io.we      := False
    rom2.io.data    := io.romDataIn

    val rom10 = new FileRom(s"$romDir/atariosb.rom", 8192)
    rom10.io.clock   := io.clock
    rom10.io.address := io.romAddr(12 downto 0).asUInt
    rom10.io.we      := False
    rom10.io.data    := io.romDataIn

    // Default: open bus
    io.romData := B(0xFF, 8 bits)

    // Cartridge: only allocate BRAM when a ROM file is provided.
    // AddressDecoder routes A000-BFFF to ROM bus when cartRd5=0 (cart present),
    // or to RAM bus when cartRd5=1 (no cart) — so no placeholder needed.
    if (cartridgeRom.nonEmpty) {
      val cartBytes = Files.readAllBytes(Paths.get(cartridgeRom))
      println(s"[InternalRomRam] Cart ROM: $cartridgeRom (${cartBytes.length} bytes), " +
              s"RAM reduced from ${internalRam} to ${internalRam - cartBytes.length}")
      val cartData = cartBytes.map(b => B((b.toInt & 0xFF), 8 bits)).toSeq
      val cartRom = Mem(Bits(8 bits), initialContent = cartData)
      val cartAddr = io.romAddr(12 downto 0).asUInt
      val cartQ = cartRom.readSync(cartAddr)

      when(io.romAddr(15)) {
        io.romData := cartQ
      }
    } else {
      println(s"[InternalRomRam] No cartridge — full ${internalRam} bytes RAM")
    }

    when(~io.romAddr(15)) {
      switch(io.romAddr(13 downto 11)) {
        is(B"011") { io.romData := rom2.io.q }
        is(B"100") { io.romData := rom10.io.q }
        is(B"101") { io.romData := rom10.io.q }
        is(B"110") { io.romData := rom10.io.q }
        is(B"111") { io.romData := rom10.io.q }
        default    { io.romData := B(0xFF, 8 bits) }
      }
    }

    io.romRequestComplete := romRequestReg
    romRequestNext := io.romRequest & ~io.romWrEnable
  } else if (internalRom == 5) {
    // Atari 800 OS in BLANK, WRITABLE BRAM. Same D800-FFFF layout as rom=3, but
    // the supervisor loads it from SD at boot (via romWrEnable) instead of the
    // ROM being embedded from a file - so nothing proprietary enters the .sof.
    // rom-space address (from AddressDecoder / load port): bit15=0 selects OS,
    // bits(13:11) select the block, bits(12:0) the offset.
    val rom2  = new GenericRamInfer(ADDRESS_WIDTH = 11, SPACE = 2048, DATA_WIDTH = 8)  // D800-DFFF
    val rom10 = new GenericRamInfer(ADDRESS_WIDTH = 13, SPACE = 8192, DATA_WIDTH = 8)  // E000-FFFF
    rom2.io.address  := io.romAddr(10 downto 0)
    rom10.io.address := io.romAddr(12 downto 0)
    rom2.io.data  := io.romDataIn
    rom10.io.data := io.romDataIn

    val romweTemp = io.romWrEnable & io.romRequest
    val we2  = Bool(); val we10 = Bool()
    we2 := False; we10 := False
    io.romData := B(0xFF, 8 bits)
    when(~io.romAddr(15)) {
      switch(io.romAddr(13 downto 11)) {
        is(B"011")                         { io.romData := rom2.io.q;  we2  := romweTemp }
        is(B"100", B"101", B"110", B"111") { io.romData := rom10.io.q; we10 := romweTemp }
        default { }
      }
    }
    rom2.io.we  := we2
    rom10.io.we := we10

    // Reads complete 1 cycle later (romRequestReg); writes immediately. The
    // streaming loader's busy->done handshake is generated in the core load path.
    io.romRequestComplete := romweTemp | romRequestReg
    romRequestNext := io.romRequest & ~io.romWrEnable
  } else if (internalRom == 2) {
    // 16K OS loop variant
    val rom16a = new FileRom(s"$romDir/atarixl_loop.rom", 16384, writable = true)
    rom16a.io.clock   := io.clock
    rom16a.io.address := io.romAddr(13 downto 0).asUInt
    rom16a.io.we      := False
    rom16a.io.data    := io.romDataIn
    io.romData := rom16a.io.q
    io.romRequestComplete := romRequestReg
    romRequestNext := io.romRequest & ~io.romWrEnable
  } else if (internalRom == 1) {
    // 16K OS (writable for DMA loading) + optionally 8K BASIC
    val rom16a = new FileRom(s"$romDir/atarixl.rom", 16384, writable = true)
    rom16a.io.clock   := io.clock
    rom16a.io.address := io.romAddr(13 downto 0).asUInt

    val romweTemp = io.romWrEnable & io.romRequest

    io.romData := rom16a.io.q

    if (withBasic) {
      val basic1 = new FileRom(s"$romDir/ataribas.rom", 8192, writable = true)
      basic1.io.clock   := io.clock
      basic1.io.address := io.romAddr(12 downto 0).asUInt

      val osRomweTemp = Bool()
      val basicRomweTemp = Bool()
      osRomweTemp := romweTemp
      basicRomweTemp := False

      when(io.romAddr(15)) {
        io.romData := basic1.io.q
        osRomweTemp := False
        basicRomweTemp := romweTemp
      }

      rom16a.io.we   := osRomweTemp
      rom16a.io.data := io.romDataIn
      basic1.io.we   := basicRomweTemp
      basic1.io.data := io.romDataIn
    } else {
      // No BASIC in internal ROM: AddressDecoder routes A000-BFFF to SDRAM via basicFromSdram
      rom16a.io.we   := romweTemp & ~io.romAddr(15)
      rom16a.io.data := io.romDataIn
    }

    romRequestNext := io.romRequest & ~io.romWrEnable
    io.romRequestComplete := romweTemp | romRequestReg
  } else {  // internalRom == 0 or any other value
    io.romData := B(0, 8 bits)
    io.romRequestComplete := False
    romRequestNext := False
  }

  // =========================================================================
  // RAM section — cart ROM (if any) replaces upper RAM, keeping total constant
  // =========================================================================
  private val cartFileSize = if (internalRom == 3 && cartridgeRom.nonEmpty)
    Files.readAllBytes(Paths.get(cartridgeRom)).length else 0
  private val effectiveRam = internalRam - cartFileSize

  val ramInt = if (effectiveRam > 0) {
    val ramweTemp = io.ramWrEnable & io.ramRequest

    val r = new GenericRamInfer(ADDRESS_WIDTH = 19, SPACE = effectiveRam, DATA_WIDTH = 8)
    r.io.address := io.ramAddr
    r.io.data    := io.ramDataIn
    r.io.we      := ramweTemp
    io.ramData := r.io.q

    ramRequestNext := io.ramRequest & ~io.ramWrEnable
    io.ramRequestComplete := ramweTemp | ramRequestReg
    Some(r)
  } else {
    io.ramData := B(0xFF, 8 bits)
    io.ramRequestComplete := True
    ramRequestNext := False
    None
  }
}
