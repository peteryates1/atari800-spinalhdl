package atari800

import spinal.core._

/**
 * Standard register-bus interface for a peripheral (originally the JOP I/O bus
 * contract; copied here so SioBridge no longer depends on the jop-spinalhdl
 * submodule). A device mixes this in and implements the accessors:
 *   - 4-bit sub-address, read/write strobes, 32-bit read/write data.
 */
trait HasBusIo { self: Component =>
  /** 4-bit sub-address input */
  def busAddr: UInt
  /** Read strobe (active for one cycle) */
  def busRd: Bool
  /** Write strobe (active for one cycle) */
  def busWr: Bool
  /** 32-bit write data */
  def busWrData: Bits
  /** 32-bit read data */
  def busRdData: Bits
  /** Interrupt outputs (default: none) */
  def busInterrupts: Seq[Bool] = Seq.empty
  /** Pipeline busy/stall signal (default: none) */
  def busBusy: Option[Bool] = None
  /** External pin bundle for auto-passthrough (default: none) */
  def busExternalIo: Option[Bundle] = None
}
