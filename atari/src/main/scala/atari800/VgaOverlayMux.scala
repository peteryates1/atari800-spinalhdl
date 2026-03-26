package atari800

import spinal.core._

// Per-pixel compositor for Atari scandoubler video and JOP text overlay.
//
// When overlayActive is high, the overlay RGB replaces the Atari RGB.
// When overlayActive is low (background / transparent), Atari video passes through.
// Sync signals always come from the Atari scandoubler.
class VgaOverlayMux extends Component {
  val io = new Bundle {
    // Atari scandoubler input (8-bit per channel RGB)
    val atariR     = in Bits(8 bits)
    val atariG     = in Bits(8 bits)
    val atariB     = in Bits(8 bits)
    val atariHsync = in Bool()
    val atariVsync = in Bool()

    // JOP text overlay input (8-bit per channel RGB + active flag)
    val overlayR      = in Bits(8 bits)
    val overlayG      = in Bits(8 bits)
    val overlayB      = in Bits(8 bits)
    val overlayActive = in Bool()

    // Composited output
    val r     = out Bits(8 bits)
    val g     = out Bits(8 bits)
    val b     = out Bits(8 bits)
    val hsync = out Bool()
    val vsync = out Bool()
  }

  // Per-pixel mux: overlay foreground replaces Atari, background is transparent
  when(io.overlayActive) {
    io.r := io.overlayR
    io.g := io.overlayG
    io.b := io.overlayB
  }.otherwise {
    io.r := io.atariR
    io.g := io.atariG
    io.b := io.atariB
  }

  // Always use Atari scandoubler sync signals
  io.hsync := io.atariHsync
  io.vsync := io.atariVsync
}
