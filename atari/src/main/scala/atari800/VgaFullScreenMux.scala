package atari800

import spinal.core._

// Full-screen switch between Atari scandoubler video and JOP VGA text output.
// Used by builds where JOP VGA text has its own 25 MHz pixel clock domain.
// When osdEnable is high, JOP text is shown; otherwise Atari video.
class VgaFullScreenMux extends Component {
  val io = new Bundle {
    val osdEnable = in Bool()

    // Atari scandoubler input
    val atariR     = in Bits(8 bits)
    val atariG     = in Bits(8 bits)
    val atariB     = in Bits(8 bits)
    val atariHsync = in Bool()
    val atariVsync = in Bool()

    // JOP VGA text input (RGB565)
    val jopR     = in Bits(5 bits)
    val jopG     = in Bits(6 bits)
    val jopB     = in Bits(5 bits)
    val jopHsync = in Bool()
    val jopVsync = in Bool()

    // Muxed output
    val r     = out Bits(8 bits)
    val g     = out Bits(8 bits)
    val b     = out Bits(8 bits)
    val hsync = out Bool()
    val vsync = out Bool()
  }

  when(io.osdEnable) {
    io.r     := io.jopR ## io.jopR(4 downto 2)
    io.g     := io.jopG ## io.jopG(5 downto 4)
    io.b     := io.jopB ## io.jopB(4 downto 2)
    io.hsync := io.jopHsync
    io.vsync := io.jopVsync
  }.otherwise {
    io.r     := io.atariR
    io.g     := io.atariG
    io.b     := io.atariB
    io.hsync := io.atariHsync
    io.vsync := io.atariVsync
  }
}
