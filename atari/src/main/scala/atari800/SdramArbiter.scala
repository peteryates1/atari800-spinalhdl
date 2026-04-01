package atari800

import spinal.core._

// SDRAM request port bundle — matches the Atari core's SDRAM interface signals
class SdramReqPort extends Bundle {
  val request         = Bool()
  val complete        = Bool()
  val readEnable      = Bool()
  val writeEnable     = Bool()
  val addr            = Bits(24 bits)  // byte address (bit 0 = byte lane)
  val dataIn          = Bits(32 bits)
  val dataOut         = Bits(32 bits)
  val byteAccess      = Bool()
  val wordAccess      = Bool()
  val longwordAccess   = Bool()
}

// Priority arbiter: two SDRAM request ports → one SdramStatemachine
// Port A (Atari) has priority and zero-latency combinational passthrough.
// Port B (JOP) is queued and served only when SDRAM is idle and A is not requesting.
//
// COMPLETE protocol (matches standalone SDRAM wiring):
//   Port A sees COMPLETE=True when it has no outstanding request (idle).
//   When A pulses REQUEST, COMPLETE drops to False (via SdramStatemachine's
//   `COMPLETE := ... & ~REQUEST` term) and stays False until the SDRAM
//   finishes A's request.  Critically, Port A MUST NOT see COMPLETE=False
//   just because Port B is using the SDRAM — otherwise the AddressDecoder
//   stalls thinking its own request is in-flight.
//
// Port B requests are captured into a pending register and forwarded only
// when the SDRAM is idle and A is quiet.
class SdramArbiter extends Component {
  val io = new Bundle {
    // Port A: Atari core (high priority, zero-latency passthrough)
    val a = new Bundle {
      val request         = in  Bool()
      val complete        = out Bool()
      val readEnable      = in  Bool()
      val writeEnable     = in  Bool()
      val addr            = in  Bits(24 bits)
      val dataIn          = in  Bits(32 bits)
      val dataOut         = out Bits(32 bits)
      val byteAccess      = in  Bool()
      val wordAccess      = in  Bool()
      val longwordAccess   = in  Bool()
      val refresh         = in  Bool()
    }

    // Port B: JOP (low priority, queued)
    val b = new Bundle {
      val request         = in  Bool()
      val complete        = out Bool()
      val readEnable      = in  Bool()
      val writeEnable     = in  Bool()
      val addr            = in  Bits(24 bits)
      val dataIn          = in  Bits(32 bits)
      val dataOut         = out Bits(32 bits)
      val byteAccess      = in  Bool()
      val wordAccess      = in  Bool()
      val longwordAccess   = in  Bool()
    }

    // Debug
    val bActive = out Bool()

    // To SdramStatemachine
    val sdram = new Bundle {
      val request         = out Bool()
      val complete        = in  Bool()
      val readEnable      = out Bool()
      val writeEnable     = out Bool()
      val addr            = out Bits(25 bits)  // ADDRESS_WIDTH+1 for SdramStatemachine
      val dataIn          = out Bits(32 bits)
      val dataOut         = in  Bits(32 bits)
      val byteAccess      = out Bool()
      val wordAccess      = out Bool()
      val longwordAccess   = out Bool()
      val refresh         = out Bool()
    }
  }

  // B state tracking
  val bActive  = RegInit(False)   // B's transaction in flight (owns SDRAM channel)
  val bPending = RegInit(False)   // B wants to request (queued)
  val aPending = RegInit(False)   // A was blocked by B, needs retry

  // Register SDRAM complete for Port B timing (JOP tolerates 1-cycle latency).
  val sdramCompleteReg = RegNext(io.sdram.complete) init False

  // Capture B requests (edge detect to avoid re-capture)
  val bReqPrev = RegNext(io.b.request) init False
  when(io.b.request && !bReqPrev) { bPending := True }

  // Read data broadcast
  io.a.dataOut := io.sdram.dataOut
  io.b.dataOut := io.sdram.dataOut

  // Refresh: always enabled so SDRAM stays refreshed even when Atari is in reset.
  io.sdram.refresh := True

  // Default: Port A signals on SDRAM bus
  io.sdram.request        := False
  io.sdram.addr           := B"0" ## io.a.addr
  io.sdram.dataIn         := io.a.dataIn
  io.sdram.readEnable     := io.a.readEnable
  io.sdram.writeEnable    := io.a.writeEnable
  io.sdram.byteAccess     := io.a.byteAccess
  io.sdram.wordAccess     := io.a.wordAccess
  io.sdram.longwordAccess := io.a.longwordAccess

  // Port A COMPLETE: mirrors the SDRAM protocol (COMPLETE = idle & ~REQUEST).
  // Must be False when A has a request active or pending, even when B owns SDRAM.
  // Without the ~io.a.request term, A's AddressDecoder sees instant false-complete
  // on the same cycle it asserts REQUEST (aPending is still False), reads garbage
  // from dataOut, and re-requests every cycle — starving Port B.
  io.a.complete := !aPending & !io.a.request
  io.b.complete := False

  when(bActive) {
    // ------------------------------------------------------------------
    // B owns the SDRAM channel — route B's signals, block A
    // ------------------------------------------------------------------
    io.sdram.addr           := B"0" ## io.b.addr
    io.sdram.dataIn         := io.b.dataIn
    io.sdram.readEnable     := io.b.readEnable
    io.sdram.writeEnable    := io.b.writeEnable
    io.sdram.byteAccess     := io.b.byteAccess
    io.sdram.wordAccess     := io.b.wordAccess
    io.sdram.longwordAccess := io.b.longwordAccess

    // Block A — capture its request for later
    when(io.a.request) { aPending := True }

    // A's COMPLETE: False when A has any request active or deferred
    io.a.complete := !aPending & !io.a.request

    // Wait for B's transaction to complete
    when(sdramCompleteReg) {
      io.b.complete := True
      bActive := False
    }
  }.otherwise {
    // ------------------------------------------------------------------
    // A has the channel — combinational passthrough
    // ------------------------------------------------------------------

    // Forward A's request directly (or replay aPending)
    io.sdram.request := io.a.request | aPending
    when(aPending && !io.a.request) { aPending := False }

    // A gets COMPLETE directly from SDRAM (zero added latency)
    io.a.complete := io.sdram.complete

    // Serve B when: pending, A not requesting, no aPending, SDRAM idle.
    // The SDRAM idle check (sdramCompleteReg) is critical: SdramStatemachine
    // uses a toggle-based CDC protocol. If we fire B's request while A's is
    // still in flight, the double-toggle cancels out — the SDRAM processes
    // two transactions but the second uses A's captured address (snapshot
    // condition fails), corrupting B's data and breaking COMPLETE timing.
    // Using sdramCompleteReg (registered) instead of io.sdram.complete
    // (combinational) to break the REQUEST↔COMPLETE combinatorial loop.
    when(bPending && !io.a.request && !aPending && sdramCompleteReg) {
      // Switch to B — override SDRAM signals for this cycle
      io.sdram.request        := True
      io.sdram.addr           := B"0" ## io.b.addr
      io.sdram.dataIn         := io.b.dataIn
      io.sdram.readEnable     := io.b.readEnable
      io.sdram.writeEnable    := io.b.writeEnable
      io.sdram.byteAccess     := io.b.byteAccess
      io.sdram.wordAccess     := io.b.wordAccess
      io.sdram.longwordAccess := io.b.longwordAccess
      bPending := False
      bActive  := True
      // On this cycle, io.sdram.request=True for B → SDRAM COMPLETE forced False.
      // A has no outstanding request (bPending only fires when !aPending),
      // so A should still see True. Override the .otherwise COMPLETE:
      io.a.complete := True
    }
  }

  io.bActive := bActive
}
