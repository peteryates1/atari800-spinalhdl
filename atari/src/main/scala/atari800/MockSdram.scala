package atari800

import spinal.core._

// Faithful RTL model of SdramStatemachine's CLIENT interface for simulation.
// Replicates the exact toggle handshake (COMPLETE = (reply==req) & ~REQUEST,
// req toggles on each REQUEST cycle) with a fixed-latency behavioral byte
// memory instead of the real SDRAM FSM. Because it's RTL (compiled with the
// DUT), there's no sim-delta skew — unlike a TB-side behavioral model.
// Byte-access only (sufficient for arbiter verification).
class MockSdram(latency: Int = 3, memBits: Int = 13) extends Component {
  val io = new Bundle {
    val REQUEST         = in  Bool()
    val COMPLETE        = out Bool()
    val READ_EN         = in  Bool()
    val WRITE_EN        = in  Bool()
    val ADDRESS_IN      = in  Bits(25 bits)
    val DATA_IN         = in  Bits(32 bits)
    val DATA_OUT        = out Bits(32 bits)
    val BYTE_ACCESS     = in  Bool()
    val WORD_ACCESS     = in  Bool()
    val LONGWORD_ACCESS = in  Bool()
    val WIDE_ACCESS     = in  Bool() default(False)
    val WIDE_IN         = in  Bits(256 bits) default(B(0, 256 bits))
    val WIDE_OUT        = out Bits(256 bits)
    val REFRESH         = in  Bool()
  }

  val mem = Mem(Bits(8 bits), 1 << memBits)

  val reqReg   = Reg(Bool()) init False
  val replyReg = Reg(Bool()) init False
  reqReg := reqReg ^ io.REQUEST                 // toggle on each REQUEST cycle

  // capture the operation on the REQUEST cycle
  val capAddr = Reg(UInt(memBits bits)) init 0
  val capData = Reg(Bits(32 bits)) init 0
  val capWide = Reg(Bits(256 bits)) init 0
  val capWe   = Reg(Bool()) init False
  val capLong = Reg(Bool()) init False
  val capIsWide = Reg(Bool()) init False
  when(io.REQUEST) {
    capAddr := io.ADDRESS_IN.asUInt.resize(memBits)
    capData := io.DATA_IN
    capWide := io.WIDE_IN
    capWe   := io.WRITE_EN
    capLong := io.LONGWORD_ACCESS
    capIsWide := io.WIDE_ACCESS
  }

  val pending = reqReg =/= replyReg
  val latCnt  = Reg(UInt(log2Up(latency + 1) bits)) init 0
  val dataOutReg = Reg(Bits(32 bits)) init 0

  // little-endian like SdramStatemachine: byte k of DATA lives at addr+k
  val base = Mux(capLong, capAddr & ~U(3, memBits bits), capAddr)
  val wbase = capAddr & ~U(31, memBits bits)
  val wideOutReg = Reg(Bits(256 bits)) init 0
  when(pending) {
    when(latCnt =/= latency) { latCnt := latCnt + 1 }
      .otherwise {
        when(capIsWide) {
          when(capWe) {
            for (k <- 0 until 32) { mem.write(wbase | k, capWide(k * 8 + 7 downto k * 8)) }
          }
          // Cat(Seq) puts the FIRST element at the LSB: index ascending = byte 0 low
          wideOutReg := Cat((0 until 32).map(k => mem.readAsync(wbase | k)))
        } otherwise {
          when(capWe) {
            mem.write(base, capData(7 downto 0))
            when(capLong) {
              mem.write(base | 1, capData(15 downto 8))
              mem.write(base | 2, capData(23 downto 16))
              mem.write(base | 3, capData(31 downto 24))
            }
          }
          dataOutReg := mem.readAsync(base | 3) ## mem.readAsync(base | 2) ##
                        mem.readAsync(base | 1) ## mem.readAsync(base)
        }
        replyReg := reqReg
        latCnt := 0
      }
  }
  io.WIDE_OUT := wideOutReg

  io.COMPLETE  := (replyReg === reqReg) & ~io.REQUEST
  io.DATA_OUT  := dataOutReg
}

// Test harness: 3-port arbiter feeding the faithful MockSdram. Exposes the A/B/C
// client ports for the simulation to drive.
class Arb3Harness extends Component {
  val io = new Bundle {
    val a = new Bundle {
      val request = in Bool(); val complete = out Bool()
      val readEnable = in Bool(); val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool(); val wordAccess = in Bool(); val longwordAccess = in Bool()
    }
    val b = new Bundle {
      val request = in Bool(); val complete = out Bool()
      val readEnable = in Bool(); val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool(); val wordAccess = in Bool(); val longwordAccess = in Bool()
    }
    val c = new Bundle {
      val request = in Bool(); val complete = out Bool()
      val readEnable = in Bool(); val writeEnable = in Bool()
      val addr = in Bits(24 bits); val dataIn = in Bits(32 bits); val dataOut = out Bits(32 bits)
      val byteAccess = in Bool(); val wordAccess = in Bool(); val longwordAccess = in Bool()
    }
  }
  val arb  = new SdramArbiter3
  val mock = new MockSdram(latency = 3)

  arb.io.a.request := io.a.request; io.a.complete := arb.io.a.complete
  arb.io.a.readEnable := io.a.readEnable; arb.io.a.writeEnable := io.a.writeEnable
  arb.io.a.addr := io.a.addr; arb.io.a.dataIn := io.a.dataIn; io.a.dataOut := arb.io.a.dataOut
  arb.io.a.byteAccess := io.a.byteAccess; arb.io.a.wordAccess := io.a.wordAccess; arb.io.a.longwordAccess := io.a.longwordAccess
  arb.io.a.refresh := False

  arb.io.b.wideAccess := False; arb.io.b.wideIn := 0
  arb.io.c.wideAccess := False
  arb.io.b.request := io.b.request; io.b.complete := arb.io.b.complete
  arb.io.b.readEnable := io.b.readEnable; arb.io.b.writeEnable := io.b.writeEnable
  arb.io.b.addr := io.b.addr; arb.io.b.dataIn := io.b.dataIn; io.b.dataOut := arb.io.b.dataOut
  arb.io.b.byteAccess := io.b.byteAccess; arb.io.b.wordAccess := io.b.wordAccess; arb.io.b.longwordAccess := io.b.longwordAccess

  arb.io.c.request := io.c.request; io.c.complete := arb.io.c.complete
  arb.io.c.readEnable := io.c.readEnable; arb.io.c.writeEnable := io.c.writeEnable
  arb.io.c.addr := io.c.addr; arb.io.c.dataIn := io.c.dataIn; io.c.dataOut := arb.io.c.dataOut
  arb.io.c.byteAccess := io.c.byteAccess; arb.io.c.wordAccess := io.c.wordAccess; arb.io.c.longwordAccess := io.c.longwordAccess

  arb.io.d.request := False; arb.io.d.readEnable := False; arb.io.d.writeEnable := False
  arb.io.d.addr := 0; arb.io.d.dataIn := 0; arb.io.d.byteAccess := False
  arb.io.d.wordAccess := False; arb.io.d.longwordAccess := False

  mock.io.REQUEST := arb.io.sdram.request;  arb.io.sdram.complete := mock.io.COMPLETE
  mock.io.READ_EN := arb.io.sdram.readEnable; mock.io.WRITE_EN := arb.io.sdram.writeEnable
  mock.io.ADDRESS_IN := arb.io.sdram.addr; mock.io.DATA_IN := arb.io.sdram.dataIn
  arb.io.sdram.dataOut := mock.io.DATA_OUT
  mock.io.BYTE_ACCESS := arb.io.sdram.byteAccess; mock.io.WORD_ACCESS := arb.io.sdram.wordAccess
  mock.io.LONGWORD_ACCESS := arb.io.sdram.longwordAccess; mock.io.REFRESH := arb.io.sdram.refresh
  mock.io.WIDE_ACCESS := arb.io.sdram.wideAccess; mock.io.WIDE_IN := arb.io.sdram.wideIn
  arb.io.sdram.wideOut := mock.io.WIDE_OUT
}
