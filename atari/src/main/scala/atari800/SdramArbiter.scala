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
// The Atari core's AddressDecoder uses a pipelined protocol:
//   - SDRAM_REQUEST is a single-cycle pulse during STATE_IDLE
//   - SDRAM_REQUEST_COMPLETE must arrive with zero added latency for the
//     AddressDecoder to complete fetches within the colour clock window
//   - REQUEST_COMPLETE = True means "idle/previous request done + data valid"
//
// Port A passes straight through (combinational); this matches the standalone
// behavioral-model wiring.  Port B requests are captured into a pending register
// and forwarded only when the SDRAM is idle and A is quiet.
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

  // Capture B requests (edge detect to avoid re-capture)
  val bReqPrev = RegNext(io.b.request) init False
  when(io.b.request && !bReqPrev) { bPending := True }

  // Read data broadcast
  io.a.dataOut := io.sdram.dataOut
  io.b.dataOut := io.sdram.dataOut

  // Refresh pass-through from Atari
  io.sdram.refresh := io.a.refresh

  // Default: Port A signals on SDRAM bus
  io.sdram.request        := False
  io.sdram.addr           := B"0" ## io.a.addr
  io.sdram.dataIn         := io.a.dataIn
  io.sdram.readEnable     := io.a.readEnable
  io.sdram.writeEnable    := io.a.writeEnable
  io.sdram.byteAccess     := io.a.byteAccess
  io.sdram.wordAccess     := io.a.wordAccess
  io.sdram.longwordAccess := io.a.longwordAccess

  io.a.complete := False
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

    // Wait for B's transaction to complete
    when(io.sdram.complete) {
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

    // A gets COMPLETE directly from SDRAM
    io.a.complete := io.sdram.complete

    // Serve B only when: pending, A not requesting, no aPending, SDRAM idle
    when(bPending && !io.a.request && !aPending && io.sdram.complete) {
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
      // A doesn't get complete this cycle (channel switching to B)
      io.a.complete := False
    }
  }
}
