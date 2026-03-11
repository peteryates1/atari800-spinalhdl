package atari800

import spinal.core._

class GtiaPlayer extends Component {
  val io = new Bundle {
    val colourEnable   = in  Bool()
    val livePosition   = in  Bits(8 bits)
    val playerPosition = in  Bits(8 bits)
    val size           = in  Bits(2 bits)
    val bitmap         = in  Bits(8 bits)
    val output         = out Bool()
  }

  val shiftReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val countReg = Reg(Bits(2 bits)) init B(0, 2 bits)

  val shiftNext = Bits(8 bits)
  val countNext = Bits(2 bits)

  shiftReg := shiftNext
  countReg := countNext

  shiftNext := shiftReg
  countNext := countReg

  io.output := shiftNext(7)

  when(io.colourEnable) {
    switch(io.size) {
      is(B"00") {  // normal size
        countNext := B"00"
        shiftNext := shiftReg(6 downto 0) ## B"0"
      }
      is(B"10") {  // normal size (with bug)
        switch(countReg) {
          is(B"00", B"11") {
            countNext := B"00"
            shiftNext := shiftReg(6 downto 0) ## B"0"
          }
          is(B"01", B"10") {
            countNext := B"10"
          }
        }
      }
      is(B"01") {
        switch(countReg) {
          is(B"01", B"11") {
            countNext := B"00"
            shiftNext := shiftReg(6 downto 0) ## B"0"
          }
          is(B"00", B"10") {
            countNext := B"01"
          }
        }
      }
      is(B"11") {
        switch(countReg) {
          is(B"00") {
            countNext := B"01"
          }
          is(B"01") {
            countNext := B"10"
          }
          is(B"10") {
            countNext := B"11"
          }
          is(B"11") {
            shiftNext := shiftReg(6 downto 0) ## B"0"
            countNext := B"00"
          }
        }
      }
    }

    when(io.livePosition === io.playerPosition) {
      shiftNext := (shiftReg(6 downto 0) ## B"0") | io.bitmap
      countNext := B(0, 2 bits)
    }
  }
}
