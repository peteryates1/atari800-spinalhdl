package atari800

import spinal.core._

class CartLogic extends Component {
  val io = new Bundle {
    val clkEnable        = in  Bool()
    val cartMode         = in  Bits(6 bits)
    val a                = in  Bits(13 bits)
    val cctlN            = in  Bool()
    val dIn              = in  Bits(8 bits)
    val rw               = in  Bool()
    val s4N              = in  Bool()
    val s5N              = in  Bool()
    val s4NOut           = out Bool()
    val s5NOut           = out Bool()
    val rd4              = out Bool()
    val rd5              = out Bool()
    val cartAddress      = out Bits(21 bits)
    val cartAddressEnable = out Bool()
    val cctlDout         = out Bits(8 bits)
    val cctlDoutEnable   = out Bool()
  }

  // cart mode constants
  val CART_MODE_OFF          = B"000000"
  val CART_MODE_8K           = B"000001"
  val CART_MODE_ATARIMAX1    = B"000010"
  val CART_MODE_ATARIMAX8    = B"000011"
  val CART_MODE_OSS          = B"000100"
  val CART_MODE_SDX64        = B"001000"
  val CART_MODE_DIAMOND64    = B"001001"
  val CART_MODE_EXPRESS64    = B"001010"
  val CART_MODE_ATRAX_128    = B"001100"
  val CART_MODE_WILLIAMS_64  = B"001101"
  val CART_MODE_16K          = B"100001"
  val CART_MODE_MEGAMAX16    = B"100010"
  val CART_MODE_BLIZZARD_16  = B"100011"
  val CART_MODE_SIC          = B"100100"
  val CART_MODE_MEGA_16      = B"101000"
  val CART_MODE_MEGA_32      = B"101001"
  val CART_MODE_MEGA_64      = B"101010"
  val CART_MODE_MEGA_128     = B"101011"
  val CART_MODE_MEGA_256     = B"101100"
  val CART_MODE_MEGA_512     = B"101101"
  val CART_MODE_MEGA_1024    = B"101110"
  val CART_MODE_MEGA_2048    = B"101111"
  val CART_MODE_XEGS_32      = B"110000"
  val CART_MODE_XEGS_64      = B"110001"
  val CART_MODE_XEGS_128     = B"110010"
  val CART_MODE_XEGS_256     = B"110011"
  val CART_MODE_XEGS_512     = B"110100"
  val CART_MODE_XEGS_1024    = B"110101"
  val CART_MODE_SXEGS_32     = B"111000"
  val CART_MODE_SXEGS_64     = B"111001"
  val CART_MODE_SXEGS_128    = B"111010"
  val CART_MODE_SXEGS_256    = B"111011"
  val CART_MODE_SXEGS_512    = B"111100"
  val CART_MODE_SXEGS_1024   = B"111101"

  // config registers
  val cfgBankReg       = Reg(Bits(8 bits)) init B(0, 8 bits)  // maps to cfg_bank(20 downto 13)
  val cfgEnableReg     = Reg(Bool()) init True
  val ossBankReg       = Reg(Bits(2 bits)) init B"01"
  val sic8xxxEnableReg = Reg(Bool()) init False
  val sicAxxxEnableReg = Reg(Bool()) init True
  val cartModePrevReg  = Reg(Bits(6 bits))

  // remember previous mode
  cartModePrevReg := io.cartMode

  // reset generation
  val resetN = Bool()
  resetN := (io.cartMode === cartModePrevReg).asBits(0)

  // access signals
  val access8xxx = ~io.s4N
  val accessAxxx = ~io.s5N

  // pass through s4/s5 if cart mode is off
  when(io.cartMode === CART_MODE_OFF) {
    io.s4NOut := io.s4N
    io.s5NOut := io.s5N
  } otherwise {
    io.s4NOut := True
    io.s5NOut := True
  }

  // read back config registers
  io.cctlDoutEnable := False
  io.cctlDout := B"xFF"
  when(~io.cctlN) {
    when(io.rw & (io.cartMode === CART_MODE_SIC) & (io.a(7 downto 5) === B"000")) {
      io.cctlDoutEnable := True
      io.cctlDout := B"0" ## (~sicAxxxEnableReg).asBits ## sic8xxxEnableReg.asBits ## cfgBankReg(5 downto 1)
    }
  }

  // set_config process
  when(~resetN) {
    cfgBankReg := B(0, 8 bits)
    cfgEnableReg := True
    ossBankReg := B"01"
    sic8xxxEnableReg := False
    sicAxxxEnableReg := True

    // cart specific initialization
    when(io.cartMode === CART_MODE_ATARIMAX8) {
      cfgBankReg := B"x7F"
    }
  } otherwise {
    when(io.clkEnable & ~io.cctlN) {
      when(~io.rw) {
        // (s)xegs bank select
        when(io.cartMode(5 downto 4) === CART_MODE_XEGS_32(5 downto 4)) {
          when(io.cartMode(3)) {
            cfgEnableReg := ~io.dIn(7)
          }
          switch(io.cartMode) {
            is(CART_MODE_XEGS_32, CART_MODE_SXEGS_32) {
              cfgBankReg(1 downto 0) := io.dIn(1 downto 0)
            }
            is(CART_MODE_XEGS_64, CART_MODE_SXEGS_64) {
              cfgBankReg(2 downto 0) := io.dIn(2 downto 0)
            }
            is(CART_MODE_XEGS_128, CART_MODE_SXEGS_128) {
              cfgBankReg(3 downto 0) := io.dIn(3 downto 0)
            }
            is(CART_MODE_XEGS_256, CART_MODE_SXEGS_256) {
              cfgBankReg(4 downto 0) := io.dIn(4 downto 0)
            }
            is(CART_MODE_XEGS_512, CART_MODE_SXEGS_512) {
              cfgBankReg(5 downto 0) := io.dIn(5 downto 0)
            }
            is(CART_MODE_XEGS_1024, CART_MODE_SXEGS_1024) {
              cfgBankReg(6 downto 0) := io.dIn(6 downto 0)
            }
          }
        }
        // megacart bank select
        when(io.cartMode(5 downto 3) === CART_MODE_MEGA_32(5 downto 3)) {
          cfgEnableReg := ~io.dIn(7)
          switch(io.cartMode) {
            is(CART_MODE_MEGA_32) {
              cfgBankReg(1 downto 1) := io.dIn(0 downto 0)
            }
            is(CART_MODE_MEGA_64) {
              cfgBankReg(2 downto 1) := io.dIn(1 downto 0)
            }
            is(CART_MODE_MEGA_128) {
              cfgBankReg(3 downto 1) := io.dIn(2 downto 0)
            }
            is(CART_MODE_MEGA_256) {
              cfgBankReg(4 downto 1) := io.dIn(3 downto 0)
            }
            is(CART_MODE_MEGA_512) {
              cfgBankReg(5 downto 1) := io.dIn(4 downto 0)
            }
            is(CART_MODE_MEGA_1024) {
              cfgBankReg(6 downto 1) := io.dIn(5 downto 0)
            }
            is(CART_MODE_MEGA_2048) {
              cfgBankReg(7 downto 1) := io.dIn(6 downto 0)
            }
          }
        }
        // atrax 128
        when(io.cartMode === CART_MODE_ATRAX_128) {
          cfgEnableReg := ~io.dIn(7)
          cfgBankReg(3 downto 0) := io.dIn(3 downto 0)
        }
        // blizzard
        when(io.cartMode === CART_MODE_BLIZZARD_16) {
          cfgEnableReg := False
        }
        // sic
        when((io.cartMode === CART_MODE_SIC) & (io.a(7 downto 5) === B"000")) {
          sic8xxxEnableReg := io.dIn(5)
          sicAxxxEnableReg := ~io.dIn(6)
          cfgBankReg(5 downto 1) := io.dIn(4 downto 0)
        }
      } // rw = 0

      // cart config using addresses, ignore read/write
      switch(io.cartMode) {
        is(CART_MODE_OSS) {
          ossBankReg := io.a(0) ## ~io.a(3)
        }
        is(CART_MODE_ATARIMAX1) {
          when(io.a(7 downto 4) === B"x0") {
            cfgBankReg(3 downto 0) := io.a(3 downto 0)
            cfgEnableReg := True
          }
          when(io.a(7 downto 4) === B"x1") {
            cfgEnableReg := False
          }
        }
        is(CART_MODE_ATARIMAX8) {
          when(~io.a(7)) {
            cfgBankReg(6 downto 0) := io.a(6 downto 0)
            cfgEnableReg := True
          } otherwise {
            cfgEnableReg := False
          }
        }
        is(CART_MODE_MEGAMAX16) {
          when(~io.a(7)) {
            cfgBankReg(7 downto 1) := io.a(6 downto 0)
            cfgEnableReg := True
          } otherwise {
            cfgEnableReg := False
          }
        }
        is(CART_MODE_WILLIAMS_64) {
          when(io.a(7 downto 4) === B"x0") {
            cfgEnableReg := ~io.a(3)
            cfgBankReg(2 downto 0) := io.a(2 downto 0)
          }
        }
        is(CART_MODE_SDX64) {
          when(io.a(7 downto 4) === B"xE") {
            cfgEnableReg := ~io.a(3)
            cfgBankReg(2 downto 0) := ~io.a(2 downto 0)
          }
        }
        is(CART_MODE_DIAMOND64) {
          when(io.a(7 downto 4) === B"xD") {
            cfgEnableReg := ~io.a(3)
            cfgBankReg(2 downto 0) := ~io.a(2 downto 0)
          }
        }
        is(CART_MODE_EXPRESS64) {
          when(io.a(7 downto 4) === B"x7") {
            cfgEnableReg := ~io.a(3)
            cfgBankReg(2 downto 0) := ~io.a(2 downto 0)
          }
        }
      }
    }
  }

  // access_cart_data process
  io.cartAddress := cfgBankReg ## io.a(12 downto 0)

  val boolRd4 = Bool()
  val boolRd5 = Bool()
  boolRd4 := False
  boolRd5 := cfgEnableReg
  io.cartAddressEnable := cfgEnableReg & accessAxxx

  when(io.cartMode(5)) { // default for 16k carts
    boolRd4 := cfgEnableReg
    io.cartAddressEnable := cfgEnableReg & (access8xxx | accessAxxx)
  }

  switch(io.cartMode) {
    is(CART_MODE_8K, CART_MODE_ATARIMAX1, CART_MODE_ATARIMAX8,
       CART_MODE_ATRAX_128, CART_MODE_WILLIAMS_64,
       CART_MODE_SDX64, CART_MODE_DIAMOND64, CART_MODE_EXPRESS64) {
      // null - defaults
    }
    is(CART_MODE_16K, CART_MODE_MEGAMAX16, CART_MODE_BLIZZARD_16) {
      when(access8xxx) {
        io.cartAddress(13) := False
      } otherwise {
        io.cartAddress(13) := True
      }
    }
    is(CART_MODE_OSS) {
      when(ossBankReg === B"00") {
        boolRd5 := False
        io.cartAddressEnable := False
      } otherwise {
        io.cartAddress(13) := ossBankReg(1) & ~io.a(12)
        io.cartAddress(12) := ossBankReg(0) & ~io.a(12)
      }
    }
    is(CART_MODE_XEGS_32, CART_MODE_SXEGS_32) {
      when(accessAxxx) {
        io.cartAddress(14 downto 13) := B"11"
      }
    }
    is(CART_MODE_XEGS_64, CART_MODE_SXEGS_64) {
      when(accessAxxx) {
        io.cartAddress(15 downto 13) := B"111"
      }
    }
    is(CART_MODE_XEGS_128, CART_MODE_SXEGS_128) {
      when(accessAxxx) {
        io.cartAddress(16 downto 13) := B"1111"
      }
    }
    is(CART_MODE_XEGS_256, CART_MODE_SXEGS_256) {
      when(accessAxxx) {
        io.cartAddress(17 downto 13) := B"11111"
      }
    }
    is(CART_MODE_XEGS_512, CART_MODE_SXEGS_512) {
      when(accessAxxx) {
        io.cartAddress(18 downto 13) := B"111111"
      }
    }
    is(CART_MODE_XEGS_1024, CART_MODE_SXEGS_1024) {
      when(accessAxxx) {
        io.cartAddress(19 downto 13) := B"1111111"
      }
    }
    is(CART_MODE_MEGA_16, CART_MODE_MEGA_32, CART_MODE_MEGA_64, CART_MODE_MEGA_128,
       CART_MODE_MEGA_256, CART_MODE_MEGA_512, CART_MODE_MEGA_1024, CART_MODE_MEGA_2048) {
      when(access8xxx) {
        io.cartAddress(13) := False
      } otherwise {
        io.cartAddress(13) := True
      }
    }
    is(CART_MODE_SIC) {
      boolRd4 := cfgEnableReg & sic8xxxEnableReg
      boolRd5 := cfgEnableReg & sicAxxxEnableReg
      io.cartAddressEnable := (access8xxx & cfgEnableReg & sic8xxxEnableReg) |
                              (accessAxxx & cfgEnableReg & sicAxxxEnableReg)
      when(access8xxx) {
        io.cartAddress(13) := False
      } otherwise {
        io.cartAddress(13) := True
      }
    }
    default {
      boolRd4 := False
      boolRd5 := False
      io.cartAddressEnable := False
    }
  }

  io.rd4 := boolRd4
  io.rd5 := boolRd5

  // disable writes
  when(~io.rw) {
    io.cartAddressEnable := False
  }
}
