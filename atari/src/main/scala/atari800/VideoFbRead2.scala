package atari800

import spinal.core._
import spinal.lib._

// Dual-clock framebuffer read/scaler for hardware:
//   * Output side (clkPixel, e.g. 74.25 MHz): 720p timing, H-DDA, cache READ, pix.
//   * Fetch side (clkFetch, e.g. 57.69 MHz = arbiter/SDRAM client): loads the
//     next output line's source row into the spare line cache via the SDRAM
//     read port, then hands the bank over.
// The line cache is a dual-clock BRAM (write=fetch, read=pixel). A per-line
// toggle handshake crosses the two domains (line rate ~45 kHz, so 2-FF
// synchronisers are plenty fast). Same nearest-neighbour scaling as VideoFbRead
// (single-clock, sim-proven); this adds the CDC for real clocks.
class VideoFbRead2(
    srcW: Int = 320, srcH: Int = 240, strideLog2: Int = 9, fbBase: Int = 0,
    hActive: Int = 1280, hFront: Int = 110, hSync: Int = 40, hBack: Int = 220,
    vActive: Int = 720,  vFront: Int = 5,   vSync: Int = 5,  vBack: Int = 20,
    addrWidth: Int = 25
) extends Component {
  val hTotal = hActive + hFront + hSync + hBack
  val vTotal = vActive + vFront + vSync + vBack

  val io = new Bundle {
    val clkPixel = in Bool()
    val clkFetch = in Bool()
    // output (pixel domain)
    val de  = out Bool()
    val hs  = out Bool()
    val vs  = out Bool()
    val pix = out Bits(8 bits)
    // SDRAM read-request port (fetch domain)
    val rdAddr     = out Bits(addrWidth bits)
    val rdReq      = out Bool()
    val rdComplete = in  Bool()
    val rdData     = in  Bits(8 bits)
  }

  val cache = Mem(Bits(8 bits), 2 * srcW)

  val pixCd   = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val fetchCd = ClockDomain(io.clkFetch, config = ClockDomainConfig(resetKind = BOOT))

  // cross-domain handshake registers
  val reqTgl   = Bool()   // pixel -> fetch: new line to load
  val ackTgl   = Bool()   // fetch -> pixel: load done
  val wantSrcY = UInt(log2Up(srcH) bits)
  val wantBank = Bool()

  // ---------------- Pixel domain: timing, scale, cache read ----------------
  val pix = new ClockingArea(pixCd) {
    val hc = Reg(UInt(log2Up(hTotal) bits)) init 0
    val vc = Reg(UInt(log2Up(vTotal) bits)) init 0
    val lineEnd = hc === (hTotal - 1)
    hc := Mux(lineEnd, U(0), hc + 1)
    when(lineEnd) { vc := Mux(vc === (vTotal - 1), U(0), vc + 1) }

    val deC = (hc < hActive) && (vc < vActive)
    val hsC = hc >= (hActive + hFront) && hc < (hActive + hFront + hSync)
    val vsC = vc >= (vActive + vFront) && vc < (vActive + vFront + vSync)

    def srcYof(oy: UInt): UInt = ((oy * srcH) / vActive).resize(log2Up(srcH))
    val nextVc   = Mux(vc === (vTotal - 1), U(0), vc + 1)
    val nextOutY = Mux(nextVc < vActive, nextVc.resize(log2Up(vActive)), U(0))
    val nextSrcY = srcYof(nextOutY)

    val dispBank = Reg(Bool()) init False

    // horizontal nearest-neighbour read
    val srcX = Reg(UInt(log2Up(srcW) bits)) init 0
    val hAcc = Reg(UInt(log2Up(hActive + srcW + 1) bits)) init 0
    when(hc === (hTotal - 1)) { srcX := 0; hAcc := 0 }
      .elsewhen(hc < hActive) {
        hAcc := hAcc + srcW
        when(hAcc + srcW >= hActive) { hAcc := hAcc + srcW - hActive; srcX := srcX + 1 }
      }
    io.pix := cache.readSync((dispBank ## srcX.resize(log2Up(srcW))).asUInt, clockCrossing = true)
    io.de  := RegNext(deC) init False
    io.hs  := RegNext(hsC) init False
    io.vs  := RegNext(vsC) init False

    // request a fetch of nextSrcY into the spare bank at the start of each line
    val reqTglR   = Reg(Bool()) init False
    val wantSrcYR = Reg(UInt(log2Up(srcH) bits)) init 0
    val wantBankR = Reg(Bool()) init True
    val ackSync   = BufferCC(ackTgl, False)
    val ackPrev   = RegNext(ackSync) init False
    val fetchReady = Reg(Bool()) init False
    when(ackSync =/= ackPrev) { fetchReady := True }   // fetch completed

    when(hc === 0) {
      wantSrcYR := nextSrcY
      wantBankR := !dispBank
      reqTglR   := !reqTglR       // kick the fetch side
    }
    when(lineEnd) {
      when(fetchReady) { dispBank := wantBankR; fetchReady := False }
    }

    reqTgl   := reqTglR
    wantSrcY := wantSrcYR
    wantBank := wantBankR
  }

  // ---------------- Fetch domain: SDRAM reads into the spare bank ----------------
  val fetch = new ClockingArea(fetchCd) {
    val reqSync = BufferCC(reqTgl, False)
    val reqPrev = RegNext(reqSync) init False
    // wantSrcY/wantBank are stable while a request is outstanding -> sync via 2FF
    val srcYSync = BufferCC(wantSrcY, U(0, log2Up(srcH) bits))
    val bankSync = BufferCC(wantBank, False)

    val busy   = Reg(Bool()) init False
    val fx     = Reg(UInt(log2Up(srcW + 1) bits)) init 0
    val inFl   = Reg(Bool()) init False
    val seen   = Reg(Bool()) init False
    val ackR   = Reg(Bool()) init False
    val ySnap  = Reg(UInt(log2Up(srcH) bits)) init 0
    val bSnap  = Reg(Bool()) init False

    when(reqSync =/= reqPrev && !busy) {
      busy := True; fx := 0; inFl := False; seen := False
      ySnap := srcYSync; bSnap := bankSync
    }

    io.rdReq  := busy && !inFl && (fx < srcW)
    io.rdAddr := (U(fbBase, addrWidth bits) + (ySnap << strideLog2) + fx).asBits.resize(addrWidth)
    when(io.rdReq) { inFl := True; seen := False }
    when(inFl) {
      when(!io.rdComplete) { seen := True }
      when(seen && io.rdComplete) {
        cache.write((bSnap ## fx.resize(log2Up(srcW))).asUInt, io.rdData)
        inFl := False
        when(fx === (srcW - 1)) { busy := False; ackR := !ackR } otherwise { fx := fx + 1 }
      }
    }
    ackTgl := ackR
  }
}
