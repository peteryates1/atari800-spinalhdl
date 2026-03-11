package atari800

import spinal.core._
import spinal.lib._

// SpinalHDL equivalent of fifo_receive.vhd
// Synchronous FIFO, 256 deep, 15 bits wide, show-ahead mode
case class FifoReceive() extends Component {
  val io = new Bundle {
    val data  = in Bits(15 bits)
    val rdreq = in Bool()
    val wrreq = in Bool()
    val empty = out Bool()
    val full  = out Bool()
    val q     = out Bits(15 bits)
    val usedw = out Bits(8 bits)
  }

  val fifo = new StreamFifo(
    dataType = Bits(15 bits),
    depth = 256
  )

  // Write side
  fifo.io.push.valid := io.wrreq
  fifo.io.push.payload := io.data

  // Read side - show-ahead mode: data appears before rdreq
  // StreamFifo pops on fire (valid && ready)
  fifo.io.pop.ready := io.rdreq

  io.q := fifo.io.pop.payload
  io.empty := !fifo.io.pop.valid
  io.full := !fifo.io.push.ready
  io.usedw := fifo.io.occupancy.asBits.resize(8)
}
