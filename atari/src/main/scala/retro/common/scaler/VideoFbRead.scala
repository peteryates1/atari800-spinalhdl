package retro.common.scaler
import retro.common.util._
import retro.common.video._
import retro.common.scaler._
import retro.common.sdram._

import spinal.core._
import spinal.lib._

// Stage 2 (read path): read the SDRAM framebuffer and scale it to the output
// raster (e.g. 320x240 source -> 1280x720) with nearest-neighbour H/V upscale.
//
// Two BRAM line caches (ping-pong): while the active line is served from one
// cache, the fetch engine loads the *next* output line's source row into the
// other over a whole output-line time, then swaps at end of line. So SDRAM sees
// ~one source-row read per output line (~source-res * vScale ≈ a few MB/s), not
// the full 720p pixel rate.
//
// Single clock domain (output pixel clock; SDRAM read on the same clock) to
// validate scaling + cache logic in simulation. Defaults are tiny for sim; real
// use passes 320x240 source + 720p timing.
class VideoFbRead(
    srcW:       Int = 320,
    srcH:       Int = 240,
    strideLog2: Int = 9,
    fbBase:     Int = 0,
    hActive: Int = 16, hFront: Int = 2, hSync: Int = 4, hBack: Int = 6,
    vActive: Int = 12, vFront: Int = 2, vSync: Int = 2, vBack: Int = 4,
    addrWidth: Int = 25
) extends Component {
  val hTotal = hActive + hFront + hSync + hBack
  val vTotal = vActive + vFront + vSync + vBack

  val io = new Bundle {
    val de   = out Bool()
    val hs   = out Bool()
    val vs   = out Bool()
    val pix  = out Bits(8 bits)          // colour index (palette applied downstream)
    val rdAddr     = out Bits(addrWidth bits)
    val rdReq      = out Bool()
    val rdComplete = in  Bool()
    val rdData     = in  Bits(8 bits)
  }

  // ---- Output timing ----
  val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
  val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
  val lineEnd = hc === (hTotal - 1)
  hc := Mux(lineEnd, U(0), hc + 1)
  when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

  val deC = (hc < hActive) && (vc < vActive)
  val hsC = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)
  val vsC = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)

  // ---- source-row selection (nearest neighbour, per-line division) ----
  def srcYof(oy: UInt): UInt = ((oy * srcH) / vActive).resize(log2Up(srcH))
  val nextVc   = Mux(vc === (vTotal - 1), U(0), vc + 1)
  val nextOutY = Mux(nextVc < vActive, nextVc.resize(log2Up(vActive)), U(0))
  val nextSrcY = srcYof(nextOutY)      // row to prefetch this line, shown next line

  // ---- two line caches ----
  val cache    = Mem(Bits(8 bits), 2 * srcW)
  val dispBank = Reg(Bool()) init False
  val fetchBank = ~dispBank

  // ---- horizontal nearest-neighbour read from display bank ----
  val srcX = Reg(UInt(log2Up(srcW) bits)) init 0
  val hAcc = Reg(UInt(log2Up(hActive + srcW + 1) bits)) init 0
  when(hc === (hTotal - 1)) { srcX := 0; hAcc := 0 }   // prep before line starts
    .elsewhen(hc < hActive) {
      hAcc := hAcc + srcW
      when(hAcc + srcW >= hActive) { hAcc := hAcc + srcW - hActive; srcX := srcX + 1 }
    }
  val pixData = cache.readSync((dispBank ## srcX.resize(log2Up(srcW))).asUInt)

  // outputs registered to align with the 1-cycle cache read latency
  io.de  := RegNext(deC) init False
  io.hs  := RegNext(hsC) init False
  io.vs  := RegNext(vsC) init False
  io.pix := pixData

  // ---- fetch engine: load nextSrcY into fetchBank over this line ----
  val fetchX   = Reg(UInt(log2Up(srcW + 1) bits)) init 0
  val fetching = Reg(Bool()) init False
  val inFlight = Reg(Bool()) init False
  val busySeen = Reg(Bool()) init False

  when(hc === 0) { fetching := True; fetchX := 0; inFlight := False; busySeen := False }

  io.rdReq  := fetching && !inFlight && (fetchX < srcW)
  io.rdAddr := (U(fbBase, addrWidth bits) + (nextSrcY << strideLog2) + fetchX).asBits.resize(addrWidth)
  when(io.rdReq) { inFlight := True; busySeen := False }
  when(inFlight) {
    when(!io.rdComplete) { busySeen := True }
    when(busySeen && io.rdComplete) {
      cache.write((fetchBank ## fetchX.resize(log2Up(srcW))).asUInt, io.rdData)
      inFlight := False
      when(fetchX === (srcW - 1)) { fetching := False } otherwise { fetchX := fetchX + 1 }
    }
  }

  // swap banks at end of every line: the just-fetched row becomes the display row
  when(lineEnd) { dispBank := ~dispBank }
}
