package atari800

import spinal.core._

// Stage 2 integration harness (single clock): the full framebuffer data path,
//   video-in -> VideoFbWrite -> SdramArbiter3(portB) -> MockSdram
//                              SdramArbiter3(portC) <- VideoFbRead -> video-out
// Port A (Atari) is tied off here — this verifies the fb write/read path through
// the arbiter + SDRAM in one clock domain. (Multi-clock split of the 720p output
// vs SDRAM domain is a later step; this proves the data path & arbiter wiring.)
class FbPipeline(
    srcW: Int = 8, srcH: Int = 4, strideLog2: Int = 9, fbBase: Int = 0x400,
    // read-side output raster (small for sim)
    hActive: Int = 16, hFront: Int = 2, hSync: Int = 4, hBack: Int = 48,
    vActive: Int = 12, vFront: Int = 2, vSync: Int = 2, vBack: Int = 4
) extends Component {
  val io = new Bundle {
    // write-side video input
    val pixStrobe = in Bool()
    val colour    = in Bits(8 bits)
    val hsyncIn   = in Bool()
    val vsyncIn   = in Bool()
    val blank     = in Bool()
    val overflow  = out Bool()
    // read-side video output
    val de   = out Bool()
    val hs   = out Bool()
    val vs   = out Bool()
    val pix  = out Bits(8 bits)
  }

  val wr  = new VideoFbWrite(fbBase = fbBase, width = srcW, strideLog2 = strideLog2, height = srcH, addrWidth = 24, fifoDepth = 64)
  val rd  = new VideoFbRead(srcW = srcW, srcH = srcH, strideLog2 = strideLog2, fbBase = fbBase,
              hActive = hActive, hFront = hFront, hSync = hSync, hBack = hBack,
              vActive = vActive, vFront = vFront, vSync = vSync, vBack = vBack, addrWidth = 24)
  val arb = new SdramArbiter3
  val mock = new MockSdram(latency = 3)

  // write-side stimulus
  wr.io.pixStrobe := io.pixStrobe
  wr.io.colour    := io.colour
  wr.io.hsync     := io.hsyncIn
  wr.io.vsync     := io.vsyncIn
  wr.io.blank     := io.blank
  io.overflow     := wr.io.overflow

  // read-side output
  io.de  := rd.io.de
  io.hs  := rd.io.hs
  io.vs  := rd.io.vs
  io.pix := rd.io.pix

  // Port A: unused
  arb.io.a.request := False; arb.io.a.readEnable := False; arb.io.a.writeEnable := False
  arb.io.a.addr := 0; arb.io.a.dataIn := 0; arb.io.a.byteAccess := True
  arb.io.a.wordAccess := False; arb.io.a.longwordAccess := False; arb.io.a.refresh := False

  // Port B: framebuffer write
  arb.io.b.request     := wr.io.wrReq
  wr.io.wrComplete     := arb.io.b.complete
  arb.io.b.writeEnable := True
  arb.io.b.readEnable  := False
  arb.io.b.addr        := wr.io.wrAddr.resized
  arb.io.b.dataIn      := wr.io.wrData
  arb.io.b.byteAccess  := wr.io.wrByte
  arb.io.b.wordAccess  := False
  arb.io.b.longwordAccess := False

  // Port C: framebuffer read
  arb.io.c.request     := rd.io.rdReq
  rd.io.rdComplete     := arb.io.c.complete
  arb.io.c.readEnable  := True
  arb.io.c.writeEnable := False
  arb.io.c.addr        := rd.io.rdAddr.resized
  arb.io.c.dataIn      := 0
  arb.io.c.byteAccess  := True
  arb.io.c.wordAccess  := False
  arb.io.c.longwordAccess := False
  rd.io.rdData         := arb.io.c.dataOut(7 downto 0)

  // Arbiter <-> MockSdram
  mock.io.REQUEST := arb.io.sdram.request; arb.io.sdram.complete := mock.io.COMPLETE
  mock.io.READ_EN := arb.io.sdram.readEnable; mock.io.WRITE_EN := arb.io.sdram.writeEnable
  mock.io.ADDRESS_IN := arb.io.sdram.addr; mock.io.DATA_IN := arb.io.sdram.dataIn
  arb.io.sdram.dataOut := mock.io.DATA_OUT
  mock.io.BYTE_ACCESS := arb.io.sdram.byteAccess; mock.io.WORD_ACCESS := arb.io.sdram.wordAccess
  mock.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess; mock.io.REFRESH := arb.io.sdram.refresh
}
