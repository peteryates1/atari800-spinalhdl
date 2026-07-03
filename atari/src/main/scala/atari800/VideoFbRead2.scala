package atari800

import spinal.core._
import spinal.core.sim._
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
    // Hold LOW until the arbiter/SDRAM (sys domain) are out of reset. The
    // pixel/fetch domains are BOOT-reset and run within microseconds of FPGA
    // config; without this gate the very first read request fires while the
    // arbiter is still held in reset and is silently swallowed — the fetch
    // then waits forever (found on hardware via the logic analyzer).
    val enable   = in Bool()
    // output (pixel domain)
    val de  = out Bool()
    val hs  = out Bool()
    val vs  = out Bool()
    val pix = out Bits(8 bits)
    // SDRAM read-request port (fetch domain). Reads are 32-bit (the controller
    // always bursts 2x16 anyway): 4 pixels per transaction, addr 4-aligned.
    val rdAddr     = out Bits(addrWidth bits)
    val rdReq      = out Bool()
    val rdComplete = in  Bool()
    val rdData     = in  Bits(32 bits)
    val dbgBeat    = out Bool()   // toggles/blinks while reads are completing
    val dbgBusy    = out Bool()   // fetch state-machine busy
    val dbgReq     = out Bool()   // pixel->fetch request toggle (reqTgl)
    val dbgInFl    = out Bool()   // fetch read in-flight
    val dbgFxMax   = out Bool()   // fx has reached srcW (no more reads this line)
    val dbgStickyRdReq = out Bool()   // rdReqR was ever high (one-shot latch)
    val dbgLateTgl     = out Bool()   // toggles on each displayed line whose bank
                                      // held the WRONG source row (a visible artifact line)
    val dbgFrameTgl    = out Bool()   // toggles once per frame (rate reference)
  }

  // 4-bank ring line cache: display consumes banks in ring order while the
  // fetch prefetches rows sequentially up to 3 rows ahead — the cushion rides
  // out transient SDRAM contention spikes (Atari + write traffic) that a
  // 2-bank ping-pong cannot (visible as residual vertical jitter on HW).
  // Addressed as (bank ## srcX) = bank * 2^log2Up(srcW) + srcX, so the depth
  // must cover the full concat stride: for non-power-of-two srcW (e.g. the
  // real 384) sizing it numBanks*srcW makes upper banks read past the end.
  val numBanks = 4
  val cache = Mem(Bits(8 bits), numBanks << log2Up(srcW))

  val pixCd   = ClockDomain(io.clkPixel, config = ClockDomainConfig(resetKind = BOOT))
  val fetchCd = ClockDomain(io.clkFetch, config = ClockDomainConfig(resetKind = BOOT))

  // cross-domain handshake registers
  val reqTgl   = Bool()   // pixel -> fetch: new line to load
  val ackTgl   = Bool()   // fetch -> pixel: load done
  val wantSrcY = UInt(log2Up(srcH) bits)
  val wantBank = UInt(log2Up(numBanks) bits)

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

    // Vertical nearest-neighbour via a DDA (no divider -> meets 74 MHz timing).
    // curSrcY tracks floor(vc*srcH/vActive) for the current line; nextSrcY looks
    // one line ahead for the prefetch.
    val vAcc    = Reg(UInt(log2Up(vActive + srcH) bits)) init 0
    val curSrcY = Reg(UInt(log2Up(srcH) bits)) init 0
    when(lineEnd) {
      when(vc === (vTotal - 1)) { vAcc := 0; curSrcY := 0 }
        .elsewhen((vc + 1) < vActive) {
          val acc2 = vAcc + srcH
          when(acc2 >= vActive) { vAcc := (acc2 - vActive).resized; curSrcY := curSrcY + 1 }
            .otherwise          { vAcc := acc2.resized }
        }
    }
    val nextSrcY = Mux(vc === (vTotal - 1), U(0, log2Up(srcH) bits),
                    Mux((vc + 1) < vActive,
                        (curSrcY + ((vAcc + srcH) >= vActive).asUInt).resize(log2Up(srcH)),
                        U(0, log2Up(srcH) bits)))
    // Fetch bandwidth assumption: each source row spans >=2 output lines, so
    // one row-fetch per line keeps the prefetch ring ahead of the display.
    require(vActive >= 2 * srcH, "VideoFbRead2 needs >=2x vertical upscale")

    val dispBank = Reg(UInt(log2Up(numBanks) bits)) init 0
    dispBank.simPublic()

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
    // Active-LOW sync (low only during the sync pulse) — matches the proven
    // disp_driver/dvi_encoder convention; active-high did not lock the monitor.
    io.hs  := RegNext(!hsC) init False
    io.vs  := RegNext(!vsC) init False

    // Ring prefetch scheduling: rows are consumed strictly in order (the DDA is
    // monotonic), so the fetch simply prefetches rows sequentially (fY) into
    // ring banks (wrB), staying up to numBanks-1 rows ahead of the display.
    val reqTglR   = Reg(Bool()) init False
    val wantSrcYR = Reg(UInt(log2Up(srcH) bits)) init 0
    val wantBankR = Reg(UInt(log2Up(numBanks) bits)) init 0
    val ackSync   = BufferCC(ackTgl, False)
    val ackPrev   = RegNext(ackSync) init False
    val fetchBusy = Reg(Bool()) init False           // request outstanding
    val bankY     = Vec(Reg(UInt(log2Up(srcH) bits)) init 0, numBanks)
    val bankOk    = Vec(RegInit(False), numBanks)
    bankY.foreach(_.simPublic()); bankOk.foreach(_.simPublic())
    val fY        = Reg(UInt(log2Up(srcH) bits)) init 0        // next row to prefetch
    val wrB       = Reg(UInt(log2Up(numBanks) bits)) init 0    // ring bank to fill next
    Seq(fY, wrB).foreach(_.simPublic())
    when(ackSync =/= ackPrev) {                      // fetch completed
      bankY(wantBankR)  := wantSrcYR
      bankOk(wantBankR) := True
      fetchBusy := False
    }

    val enSync = BufferCC(io.enable, False)
    val nxt = dispBank + 1
    val dispHasWant = bankOk(dispBank) && bankY(dispBank) === nextSrcY
    val nxtHasWant  = bankOk(nxt)      && bankY(nxt)      === nextSrcY
    // The prefetch free-runs: a new fetch is kicked as soon as the previous one
    // acks and the ring has a free bank. (Gating kicks to once per output line
    // caps production at 1 row / 2 lines — exactly the consumption rate of
    // span-2 rows at 2.5x — so the cushion never built and the display ran
    // chronically a row behind: ~half of all lines wrong, measured on HW.)
    val ringFull = wrB === dispBank && bankOk(wrB)
    when(enSync && !fetchBusy) {
      when(!dispHasWant && !nxtHasWant) {
        // cold start / desync: restart the ring at the row needed right now,
        // fetching it straight into the display bank (once per line)
        when(hc === 0) {
          bankOk.foreach(_ := False)
          wantSrcYR := nextSrcY
          wantBankR := dispBank
          reqTglR   := !reqTglR
          fetchBusy := True
          fY  := Mux(nextSrcY === (srcH - 1), U(0), nextSrcY + 1)
          wrB := dispBank + 1
        }
      }.elsewhen(!ringFull) {
        // ring not full: prefetch the next row in display order
        wantSrcYR := fY
        wantBankR := wrB
        reqTglR   := !reqTglR
        fetchBusy := True
        fY  := Mux(fY === (srcH - 1), U(0), fY + 1)
        wrB := wrB + 1
      }
    }
    // nextSrcY is stable across the whole line (vAcc/curSrcY update at lineEnd,
    // and this reads the pre-update values), so the compare here matches hc==0.
    when(lineEnd) {
      when(nxtHasWant && !dispHasWant) { dispBank := nxt }
    }

    // Abort on disable: forget the outstanding request and all bank tags so the
    // desync branch does a clean ring restart when enable returns.
    when(!enSync) {
      fetchBusy := False
      bankOk.foreach(_ := False)
    }

    // Artifact meter: any active line displayed from a bank whose tag does not
    // match the DDA's required row (curSrcY) was visibly wrong. Toggle per event.
    val lateTgl  = Reg(Bool()) init False
    val frameTgl = Reg(Bool()) init False
    when(lineEnd && vc < vActive && enSync &&
         !(bankOk(dispBank) && bankY(dispBank) === curSrcY)) { lateTgl := !lateTgl }
    when(lineEnd && vc === (vTotal - 1)) { frameTgl := !frameTgl }
    io.dbgLateTgl  := lateTgl
    io.dbgFrameTgl := frameTgl

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
    val bankSync = BufferCC(wantBank, U(0, log2Up(numBanks) bits))

    val busy   = Reg(Bool()) init False
    val fx     = Reg(UInt(log2Up(srcW + 1) bits)) init 0
    val inFl   = Reg(Bool()) init False
    val seen   = Reg(Bool()) init False
    val ackR   = Reg(Bool()) init False
    val ySnap  = Reg(UInt(log2Up(srcH) bits)) init 0
    val bSnap  = Reg(UInt(log2Up(numBanks) bits)) init 0
    Seq(ySnap, bSnap).foreach(_.simPublic())
    val beat   = Reg(UInt(24 bits)) init 0   // read-completion counter (debug)

    // Registered read-request FSM, 32-bit reads: issue (1-cycle rdReq pulse) ->
    // wait complete -> unload the 4 pixels into the cache -> next. rdReq is
    // registered so it is a clean one-cycle pulse per transaction.
    require(srcW % 4 == 0, "srcW must be a multiple of 4 (32-bit fetches)")
    val rdReqR = Reg(Bool()) init False
    val dataR  = Reg(Bits(32 bits)) init 0
    val unload = Reg(UInt(3 bits)) init 0
    unload.simPublic(); dataR.simPublic(); fx.simPublic()   // 1..4 = writing byte (unload-1), 0 = idle
    io.rdReq  := rdReqR
    io.rdAddr := (U(fbBase, addrWidth bits) + (ySnap << strideLog2) + fx).asBits.resize(addrWidth)
    // wantSrcY/wantBank change on the SAME pixel cycle as the reqTgl toggle, so
    // when the synchronised edge is first seen the synchronised bus may still be
    // mid-transition (bits can straddle the async sampling edge on silicon —
    // a stale bit 5 fetches a row 32 away). Wait 2 cycles for it to settle
    // before snapping. Not visible in RTL sim; found on hardware.
    val pend   = Reg(Bool()) init False
    val settle = Reg(UInt(2 bits)) init 0
    when(reqSync =/= reqPrev) { pend := True; settle := 2 }
    when(!busy && pend) {
      when(settle =/= 0) { settle := settle - 1 } otherwise {
        pend := False
        busy := True; fx := 0; rdReqR := True; inFl := False; seen := False; unload := 0
        ySnap := srcYSync; bSnap := bankSync
      }
    }
    when(busy) {
      when(rdReqR) { rdReqR := False; inFl := True; seen := False }
      when(inFl) {
        when(!io.rdComplete) { seen := True }
        when(seen && io.rdComplete) {
          dataR := io.rdData
          inFl := False
          unload := 1
          beat := beat + 1
        }
      }
      when(unload =/= 0) {
        val byteIdx = (unload - 1).resize(2)
        cache.write((bSnap ## (fx + byteIdx).resize(log2Up(srcW))).asUInt,
                    dataR.subdivideIn(8 bits)(byteIdx))
        when(unload === 4) {
          unload := 0
          when(fx + 4 >= srcW) { busy := False; ackR := !ackR }
            .otherwise { fx := fx + 4; rdReqR := True }
        } otherwise { unload := unload + 1 }
      }
    }
    ackTgl := ackR
    io.dbgBeat := beat(20)
    io.dbgBusy := busy
    io.dbgInFl := inFl
    io.dbgFxMax := !(fx < srcW)
    val stickyRdReq = RegInit(False) setWhen rdReqR
    io.dbgStickyRdReq := stickyRdReq

    // Abort on disable (e.g. console reset resets the arbiter mid-transaction:
    // the in-flight completion never arrives and busy would strand forever).
    // Swallow any pending request edges so re-enable starts clean.
    val enFetch = BufferCC(io.enable, False)
    when(!enFetch) {
      busy := False; inFl := False; rdReqR := False; unload := 0
      pend := False; settle := 0
      reqPrev := reqSync
    }
  }
  io.dbgReq := reqTgl
}
