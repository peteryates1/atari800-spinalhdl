package atari800

import spinal.core._
import spinal.core.sim._

class Cpu65xx(
  enableJam: Boolean = true,
  pipelineOpcode: Boolean = false,
  pipelineAluMux: Boolean = false,
  pipelineAluOut: Boolean = false
) extends Component {
  val io = new Bundle {
    val enable     = in  Bool()
    val halt       = in  Bool() default False
    val reset      = in  Bool()
    val nmi_n      = in  Bool() default True
    val irq_n      = in  Bool() default True
    val so_n       = in  Bool() default True

    val d          = in  UInt(8 bits)
    val q          = out UInt(8 bits)
    val addr       = out UInt(16 bits)
    val we         = out Bool()

    val debugOpcode = out UInt(8 bits)
    val debugJam    = out Bool()
    val debugPc     = out UInt(16 bits)
    val debugA      = out UInt(8 bits)
    val debugX      = out UInt(8 bits)
    val debugY      = out UInt(8 bits)
    val debugS      = out UInt(8 bits)
    val debug_flags = out UInt(8 bits)
  }

  // -----------------------------------------------------------------------
  // State machine
  // -----------------------------------------------------------------------
  object CpuCycle extends SpinalEnum {
    val opcodeFetch, cycle2, cycle3,
        cyclePreIndirect, cycleIndirect,
        cycleBranchTaken, cycleBranchPage,
        cyclePreRead, cycleRead, cycleRead2,
        cycleRmw,
        cyclePreWrite, cycleWrite,
        cycleStack1, cycleStack2, cycleStack3, cycleStack4,
        cycleJump, cycleEnd = newElement()
  }
  import CpuCycle._

  val theCpuCycle  = Reg(CpuCycle()) init cycle2
  theCpuCycle.simPublic()
  val nextCpuCycle = CpuCycle()
  val updateRegisters = Bool()

  val processNmi = Reg(Bool()) init False
  val processIrq = Reg(Bool()) init False
  val processInt = Reg(Bool()) init False
  val nmiReg     = Reg(Bool())
  val nmiEdge    = Reg(Bool())
  val irqReg     = Reg(Bool())
  val so_reg     = Reg(Bool())

  // -----------------------------------------------------------------------
  // Opcode decoding constants
  // -----------------------------------------------------------------------
  val opcUpdateA    = 0
  val opcUpdateX    = 1
  val opcUpdateY    = 2
  val opcUpdateS    = 3
  val opcUpdateN    = 4
  val opcUpdateV    = 5
  val opcUpdateD    = 6
  val opcUpdateI    = 7
  val opcUpdateZ    = 8
  val opcUpdateC    = 9
  val opcSecondByte = 10
  val opcAbsolute   = 11
  val opcZeroPage   = 12
  val opcIndirect   = 13
  val opcStackAddr  = 14
  val opcStackData  = 15
  val opcJump       = 16
  val opcBranch     = 17
  val indexX        = 18
  val indexY        = 19
  val opcStackUp    = 20
  val opcWrite      = 21
  val opcRmw        = 22
  val opcIncrAfter  = 23
  val opcRti        = 24
  val opcIRQ        = 25
  val opcJAM        = 26
  val opcInA        = 27
  val opcInE        = 28
  val opcInX        = 29
  val opcInY        = 30
  val opcInS        = 31
  val opcInT        = 32
  val opcInH        = 33
  val opcInClear    = 34
  val aluMode1From  = 35
  val aluMode1To    = 38
  val aluMode2From  = 39
  val aluMode2To    = 41
  val opcInCmp      = 42
  val opcInCpx      = 43
  val opcInCpy      = 44

  // Opcode info table - 256 entries, 45 bits each
  // Each entry is a 45-bit value encoding the addressing mode, ALU operation, etc.
  def opcEntry(s: String): BigInt = {
    // Parse the string, treating '-' as '0'
    // Reverse so that string position i maps to bit i in the BigInt
    // (AXYS at string pos 0-3 maps to opcInfo bits 0-3, etc.)
    val clean = s.replace("-", "0")
    BigInt(clean.reverse, 2)
  }

  val opcodeInfoTable = Vec(UInt(45 bits), 256)

  // Build the table from the VHDL constants
  // Format: AXYS(4) + NVDIZC(6) + addr(17) + aluIn(8) + aluMode(10) = 45 bits
  val tableEntries = Array(
    //       AXYS   NVDIZC   addr                aluIn      aluMode
    // 00 BRK
    "0000" + "000100" + "10001110000000010" + "00000000" + "0001000---",
    // 01 ORA (zp,x)
    "1000" + "100010" + "10010000100000000" + "00000100" + "0000101---",
    // 02 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 03 iSLO (zp,x)
    "1000" + "100011" + "10010000100010000" + "00000100" + "1010101---",
    // 04 iNOP zp
    "0000" + "000000" + "10100000000000000" + "00000000" + "0000000000",
    // 05 ORA zp
    "1000" + "100010" + "10100000000000000" + "00000100" + "0000101---",
    // 06 ASL zp
    "0000" + "100011" + "10100000000010000" + "00000100" + "1010000---",
    // 07 iSLO zp
    "1000" + "100011" + "10100000000010000" + "00000100" + "1010101---",
    // 08 PHP
    "0000" + "000000" + "00000100000000000" + "00000000" + "0001000---",
    // 09 ORA imm
    "1000" + "100010" + "10000000000000000" + "00000100" + "0000101---",
    // 0A ASL accu
    "1000" + "100011" + "00000000000000000" + "10000000" + "1010000---",
    // 0B iANC imm
    "1000" + "100011" + "10000000000000000" + "00000100" + "1111100---",
    // 0C iNOP abs
    "0000" + "000000" + "11000000000000000" + "00000000" + "0000000000",
    // 0D ORA abs
    "1000" + "100010" + "11000000000000000" + "00000100" + "0000101---",
    // 0E ASL abs
    "0000" + "100011" + "11000000000010000" + "00000100" + "1010000---",
    // 0F iSLO abs
    "1000" + "100011" + "11000000000010000" + "00000100" + "1010101---",
    // 10 BPL
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // 11 ORA (zp),y
    "1000" + "100010" + "10010000010000000" + "00000100" + "0000101---",
    // 12 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 13 iSLO (zp),y
    "1000" + "100011" + "10010000010010000" + "00000100" + "1010101---",
    // 14 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // 15 ORA zp,x
    "1000" + "100010" + "10100000100000000" + "00000100" + "0000101---",
    // 16 ASL zp,x
    "0000" + "100011" + "10100000100010000" + "00000100" + "1010000---",
    // 17 iSLO zp,x
    "1000" + "100011" + "10100000100010000" + "00000100" + "1010101---",
    // 18 CLC
    "0000" + "000001" + "00000000000000000" + "00000001" + "0100000---",
    // 19 ORA abs,y
    "1000" + "100010" + "11000000010000000" + "00000100" + "0000101---",
    // 1A iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // 1B iSLO abs,y
    "1000" + "100011" + "11000000010010000" + "00000100" + "1010101---",
    // 1C iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // 1D ORA abs,x
    "1000" + "100010" + "11000000100000000" + "00000100" + "0000101---",
    // 1E ASL abs,x
    "0000" + "100011" + "11000000100010000" + "00000100" + "1010000---",
    // 1F iSLO abs,x
    "1000" + "100011" + "11000000100010000" + "00000100" + "1010101---",
    // 20 JSR
    "0000" + "000000" + "10001010000000000" + "00000000" + "0000000000",
    // 21 AND (zp,x)
    "1000" + "100010" + "10010000100000000" + "00000100" + "0000100---",
    // 22 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 23 iRLA (zp,x)
    "1000" + "100011" + "10010000100010000" + "00000100" + "1011100---",
    // 24 BIT zp
    "0000" + "110010" + "10100000000000000" + "00000100" + "0101100---",
    // 25 AND zp
    "1000" + "100010" + "10100000000000000" + "00000100" + "0000100---",
    // 26 ROL zp
    "0000" + "100011" + "10100000000010000" + "00000100" + "1011000---",
    // 27 iRLA zp
    "1000" + "100011" + "10100000000010000" + "00000100" + "1011100---",
    // 28 PLP
    "0000" + "111111" + "00000100001000000" + "00000100" + "0100000---",
    // 29 AND imm
    "1000" + "100010" + "10000000000000000" + "00000100" + "0000100---",
    // 2A ROL accu
    "1000" + "100011" + "00000000000000000" + "10000000" + "1011000---",
    // 2B iANC imm
    "1000" + "100011" + "10000000000000000" + "00000100" + "1111100---",
    // 2C BIT abs
    "0000" + "110010" + "11000000000000000" + "00000100" + "0101100---",
    // 2D AND abs
    "1000" + "100010" + "11000000000000000" + "00000100" + "0000100---",
    // 2E ROL abs
    "0000" + "100011" + "11000000000010000" + "00000100" + "1011000---",
    // 2F iRLA abs
    "1000" + "100011" + "11000000000010000" + "00000100" + "1011100---",
    // 30 BMI
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // 31 AND (zp),y
    "1000" + "100010" + "10010000010000000" + "00000100" + "0000100---",
    // 32 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 33 iRLA (zp),y
    "1000" + "100011" + "10010000010010000" + "00000100" + "1011100---",
    // 34 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // 35 AND zp,x
    "1000" + "100010" + "10100000100000000" + "00000100" + "0000100---",
    // 36 ROL zp,x
    "0000" + "100011" + "10100000100010000" + "00000100" + "1011000---",
    // 37 iRLA zp,x
    "1000" + "100011" + "10100000100010000" + "00000100" + "1011100---",
    // 38 SEC
    "0000" + "000001" + "00000000000000000" + "00000000" + "0100000---",
    // 39 AND abs,y
    "1000" + "100010" + "11000000010000000" + "00000100" + "0000100---",
    // 3A iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // 3B iRLA abs,y
    "1000" + "100011" + "11000000010010000" + "00000100" + "1011100---",
    // 3C iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // 3D AND abs,x
    "1000" + "100010" + "11000000100000000" + "00000100" + "0000100---",
    // 3E ROL abs,x
    "0000" + "100011" + "11000000100010000" + "00000100" + "1011000---",
    // 3F iRLA abs,x
    "1000" + "100011" + "11000000100010000" + "00000100" + "1011100---",
    // 40 RTI
    "0000" + "111111" + "00001110001000100" + "00000100" + "0100000---",
    // 41 EOR (zp,x)
    "1000" + "100010" + "10010000100000000" + "00000100" + "0000110---",
    // 42 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 43 iSRE (zp,x)
    "1000" + "100011" + "10010000100010000" + "00000100" + "1000110---",
    // 44 iNOP zp
    "0000" + "000000" + "10100000000000000" + "00000000" + "0000000000",
    // 45 EOR zp
    "1000" + "100010" + "10100000000000000" + "00000100" + "0000110---",
    // 46 LSR zp
    "0000" + "100011" + "10100000000010000" + "00000100" + "1000000---",
    // 47 iSRE zp
    "1000" + "100011" + "10100000000010000" + "00000100" + "1000110---",
    // 48 PHA
    "0000" + "000000" + "00000100000000000" + "10000000" + "0000000---",
    // 49 EOR imm
    "1000" + "100010" + "10000000000000000" + "00000100" + "0000110---",
    // 4A LSR accu
    "1000" + "100011" + "00000000000000000" + "10000000" + "1000000---",
    // 4B iALR imm
    "1000" + "100011" + "10000000000000000" + "10000100" + "1000000---",
    // 4C JMP abs
    "0000" + "000000" + "10000010000000000" + "00000000" + "0000000000",
    // 4D EOR abs
    "1000" + "100010" + "11000000000000000" + "00000100" + "0000110---",
    // 4E LSR abs
    "0000" + "100011" + "11000000000010000" + "00000100" + "1000000---",
    // 4F iSRE abs
    "1000" + "100011" + "11000000000010000" + "00000100" + "1000110---",
    // 50 BVC
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // 51 EOR (zp),y
    "1000" + "100010" + "10010000010000000" + "00000100" + "0000110---",
    // 52 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 53 iSRE (zp),y
    "1000" + "100011" + "10010000010010000" + "00000100" + "1000110---",
    // 54 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // 55 EOR zp,x
    "1000" + "100010" + "10100000100000000" + "00000100" + "0000110---",
    // 56 LSR zp,x
    "0000" + "100011" + "10100000100010000" + "00000100" + "1000000---",
    // 57 iSRE zp,x
    "1000" + "100011" + "10100000100010000" + "00000100" + "1000110---",
    // 58 CLI
    "0000" + "000100" + "00000000000000000" + "00000001" + "0000000000",
    // 59 EOR abs,y
    "1000" + "100010" + "11000000010000000" + "00000100" + "0000110---",
    // 5A iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // 5B iSRE abs,y
    "1000" + "100011" + "11000000010010000" + "00000100" + "1000110---",
    // 5C iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // 5D EOR abs,x
    "1000" + "100010" + "11000000100000000" + "00000100" + "0000110---",
    // 5E LSR abs,x
    "0000" + "100011" + "11000000100010000" + "00000100" + "1000000---",
    // 5F iSRE abs,x
    "1000" + "100011" + "11000000100010000" + "00000100" + "1000110---",
    // 60 RTS
    "0000" + "000000" + "00001010001001000" + "00000000" + "0000000000",
    // 61 ADC (zp,x)
    "1000" + "110011" + "10010000100000000" + "00000100" + "0000010---",
    // 62 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 63 iRRA (zp,x)
    "1000" + "110011" + "10010000100010000" + "00000100" + "1001010---",
    // 64 iNOP zp
    "0000" + "000000" + "10100000000000000" + "00000000" + "0000000000",
    // 65 ADC zp
    "1000" + "110011" + "10100000000000000" + "00000100" + "0000010---",
    // 66 ROR zp
    "0000" + "100011" + "10100000000010000" + "00000100" + "1001000---",
    // 67 iRRA zp
    "1000" + "110011" + "10100000000010000" + "00000100" + "1001010---",
    // 68 PLA
    "1000" + "100010" + "00000100001000000" + "00000100" + "0000000---",
    // 69 ADC imm
    "1000" + "110011" + "10000000000000000" + "00000100" + "0000010---",
    // 6A ROR accu
    "1000" + "100011" + "00000000000000000" + "10000000" + "1001000---",
    // 6B iARR imm
    "1000" + "110011" + "10000000000000000" + "10000100" + "1001111---",
    // 6C JMP indirect
    "0000" + "000000" + "11000010000000000" + "00000000" + "0000000000",
    // 6D ADC abs
    "1000" + "110011" + "11000000000000000" + "00000100" + "0000010---",
    // 6E ROR abs
    "0000" + "100011" + "11000000000010000" + "00000100" + "1001000---",
    // 6F iRRA abs
    "1000" + "110011" + "11000000000010000" + "00000100" + "1001010---",
    // 70 BVS
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // 71 ADC (zp),y
    "1000" + "110011" + "10010000010000000" + "00000100" + "0000010---",
    // 72 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 73 iRRA (zp),y
    "1000" + "110011" + "10010000010010000" + "00000100" + "1001010---",
    // 74 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // 75 ADC zp,x
    "1000" + "110011" + "10100000100000000" + "00000100" + "0000010---",
    // 76 ROR zp,x
    "0000" + "100011" + "10100000100010000" + "00000100" + "1001000---",
    // 77 iRRA zp,x
    "1000" + "110011" + "10100000100010000" + "00000100" + "1001010---",
    // 78 SEI
    "0000" + "000100" + "00000000000000000" + "00000000" + "0000000000",
    // 79 ADC abs,y
    "1000" + "110011" + "11000000010000000" + "00000100" + "0000010---",
    // 7A iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // 7B iRRA abs,y
    "1000" + "110011" + "11000000010010000" + "00000100" + "1001010---",
    // 7C iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // 7D ADC abs,x
    "1000" + "110011" + "11000000100000000" + "00000100" + "0000010---",
    // 7E ROR abs,x
    "0000" + "100011" + "11000000100010000" + "00000100" + "1001000---",
    // 7F iRRA abs,x
    "1000" + "110011" + "11000000100010000" + "00000100" + "1001010---",
    // 80 iNOP imm
    "0000" + "000000" + "10000000000000000" + "00000000" + "0000000000",
    // 81 STA (zp,x)
    "0000" + "000000" + "10010000100100000" + "10000000" + "0000000---",
    // 82 iNOP imm
    "0000" + "000000" + "10000000000000000" + "00000000" + "0000000000",
    // 83 iSAX (zp,x)
    "0000" + "000000" + "10010000100100000" + "10100000" + "0000000---",
    // 84 STY zp
    "0000" + "000000" + "10100000000100000" + "00010000" + "0000000---",
    // 85 STA zp
    "0000" + "000000" + "10100000000100000" + "10000000" + "0000000---",
    // 86 STX zp
    "0000" + "000000" + "10100000000100000" + "00100000" + "0000000---",
    // 87 iSAX zp
    "0000" + "000000" + "10100000000100000" + "10100000" + "0000000---",
    // 88 DEY
    "0010" + "100010" + "00000000000000000" + "00010000" + "0011000---",
    // 89 iNOP imm
    "0000" + "000000" + "10000000000000000" + "00000000" + "0000000000",
    // 8A TXA
    "1000" + "100010" + "00000000000000000" + "00100000" + "0000000---",
    // 8B iANE imm
    "1000" + "100010" + "10000000000000000" + "01100100" + "0000000---",
    // 8C STY abs
    "0000" + "000000" + "11000000000100000" + "00010000" + "0000000---",
    // 8D STA abs
    "0000" + "000000" + "11000000000100000" + "10000000" + "0000000---",
    // 8E STX abs
    "0000" + "000000" + "11000000000100000" + "00100000" + "0000000---",
    // 8F iSAX abs
    "0000" + "000000" + "11000000000100000" + "10100000" + "0000000---",
    // 90 BCC
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // 91 STA (zp),y
    "0000" + "000000" + "10010000010100000" + "10000000" + "0000000---",
    // 92 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // 93 iAHX (zp),y
    "0000" + "000000" + "10010000010100000" + "10100010" + "0000000---",
    // 94 STY zp,x
    "0000" + "000000" + "10100000100100000" + "00010000" + "0000000---",
    // 95 STA zp,x
    "0000" + "000000" + "10100000100100000" + "10000000" + "0000000---",
    // 96 STX zp,y
    "0000" + "000000" + "10100000010100000" + "00100000" + "0000000---",
    // 97 iSAX zp,y
    "0000" + "000000" + "10100000010100000" + "10100000" + "0000000---",
    // 98 TYA
    "1000" + "100010" + "00000000000000000" + "00010000" + "0000000---",
    // 99 STA abs,y
    "0000" + "000000" + "11000000010100000" + "10000000" + "0000000---",
    // 9A TXS
    "0001" + "000000" + "00000000000000000" + "00100000" + "0000000---",
    // 9B iSHS abs,y
    "0001" + "000000" + "11000000010100000" + "10100010" + "0000000---",
    // 9C iSHY abs,x
    "0000" + "000000" + "11000000100100000" + "00010010" + "0000000---",
    // 9D STA abs,x
    "0000" + "000000" + "11000000100100000" + "10000000" + "0000000---",
    // 9E iSHX abs,y
    "0000" + "000000" + "11000000010100000" + "00100010" + "0000000---",
    // 9F iAHX abs,y
    "0000" + "000000" + "11000000010100000" + "10100010" + "0000000---",
    // A0 LDY imm
    "0010" + "100010" + "10000000000000000" + "00000100" + "0000000---",
    // A1 LDA (zp,x)
    "1000" + "100010" + "10010000100000000" + "00000100" + "0000000---",
    // A2 LDX imm
    "0100" + "100010" + "10000000000000000" + "00000100" + "0000000---",
    // A3 LAX (zp,x)
    "1100" + "100010" + "10010000100000000" + "00000100" + "0000000---",
    // A4 LDY zp
    "0010" + "100010" + "10100000000000000" + "00000100" + "0000000---",
    // A5 LDA zp
    "1000" + "100010" + "10100000000000000" + "00000100" + "0000000---",
    // A6 LDX zp
    "0100" + "100010" + "10100000000000000" + "00000100" + "0000000---",
    // A7 iLAX zp
    "1100" + "100010" + "10100000000000000" + "00000100" + "0000000---",
    // A8 TAY
    "0010" + "100010" + "00000000000000000" + "10000000" + "0000000---",
    // A9 LDA imm
    "1000" + "100010" + "10000000000000000" + "00000100" + "0000000---",
    // AA TAX
    "0100" + "100010" + "00000000000000000" + "10000000" + "0000000---",
    // AB iLXA imm (MWW change for Atari800 CPU)
    "1100" + "100010" + "10000000000000000" + "01000100" + "0000100---",
    // AC LDY abs
    "0010" + "100010" + "11000000000000000" + "00000100" + "0000000---",
    // AD LDA abs
    "1000" + "100010" + "11000000000000000" + "00000100" + "0000000---",
    // AE LDX abs
    "0100" + "100010" + "11000000000000000" + "00000100" + "0000000---",
    // AF iLAX abs
    "1100" + "100010" + "11000000000000000" + "00000100" + "0000000---",
    // B0 BCS
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // B1 LDA (zp),y
    "1000" + "100010" + "10010000010000000" + "00000100" + "0000000---",
    // B2 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // B3 iLAX (zp),y
    "1100" + "100010" + "10010000010000000" + "00000100" + "0000000---",
    // B4 LDY zp,x
    "0010" + "100010" + "10100000100000000" + "00000100" + "0000000---",
    // B5 LDA zp,x
    "1000" + "100010" + "10100000100000000" + "00000100" + "0000000---",
    // B6 LDX zp,y
    "0100" + "100010" + "10100000010000000" + "00000100" + "0000000---",
    // B7 iLAX zp,y
    "1100" + "100010" + "10100000010000000" + "00000100" + "0000000---",
    // B8 CLV
    "0000" + "010000" + "00000000000000000" + "00000001" + "0100000---",
    // B9 LDA abs,y
    "1000" + "100010" + "11000000010000000" + "00000100" + "0000000---",
    // BA TSX
    "0100" + "100010" + "00000000000000000" + "00001000" + "0000000---",
    // BB iLAS abs,y
    "1101" + "100010" + "11000000010000000" + "00001100" + "0000000---",
    // BC LDY abs,x
    "0010" + "100010" + "11000000100000000" + "00000100" + "0000000---",
    // BD LDA abs,x
    "1000" + "100010" + "11000000100000000" + "00000100" + "0000000---",
    // BE LDX abs,y
    "0100" + "100010" + "11000000010000000" + "00000100" + "0000000---",
    // BF iLAX abs,y
    "1100" + "100010" + "11000000010000000" + "00000100" + "0000000---",
    // C0 CPY imm
    "0000" + "100011" + "10000000000000000" + "00000100" + "0000001001",
    // C1 CMP (zp,x)
    "0000" + "100011" + "10010000100000000" + "00000100" + "0000001100",
    // C2 iNOP imm
    "0000" + "000000" + "10000000000000000" + "00000000" + "0000000000",
    // C3 iDCP (zp,x)
    "0000" + "100011" + "10010000100010000" + "00000100" + "0011001100",
    // C4 CPY zp
    "0000" + "100011" + "10100000000000000" + "00000100" + "0000001001",
    // C5 CMP zp
    "0000" + "100011" + "10100000000000000" + "00000100" + "0000001100",
    // C6 DEC zp
    "0000" + "100010" + "10100000000010000" + "00000100" + "0011000---",
    // C7 iDCP zp
    "0000" + "100011" + "10100000000010000" + "00000100" + "0011001100",
    // C8 INY
    "0010" + "100010" + "00000000000000000" + "00010000" + "0010000---",
    // C9 CMP imm
    "0000" + "100011" + "10000000000000000" + "00000100" + "0000001100",
    // CA DEX
    "0100" + "100010" + "00000000000000000" + "00100000" + "0011000---",
    // CB SBX imm
    "0100" + "100011" + "10000000000000000" + "00000100" + "0000001110",
    // CC CPY abs
    "0000" + "100011" + "11000000000000000" + "00000100" + "0000001001",
    // CD CMP abs
    "0000" + "100011" + "11000000000000000" + "00000100" + "0000001100",
    // CE DEC abs
    "0000" + "100010" + "11000000000010000" + "00000100" + "0011000---",
    // CF iDCP abs
    "0000" + "100011" + "11000000000010000" + "00000100" + "0011001100",
    // D0 BNE
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // D1 CMP (zp),y
    "0000" + "100011" + "10010000010000000" + "00000100" + "0000001100",
    // D2 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // D3 iDCP (zp),y
    "0000" + "100011" + "10010000010010000" + "00000100" + "0011001100",
    // D4 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // D5 CMP zp,x
    "0000" + "100011" + "10100000100000000" + "00000100" + "0000001100",
    // D6 DEC zp,x
    "0000" + "100010" + "10100000100010000" + "00000100" + "0011000---",
    // D7 iDCP zp,x
    "0000" + "100011" + "10100000100010000" + "00000100" + "0011001100",
    // D8 CLD
    "0000" + "001000" + "00000000000000000" + "00000001" + "0000000000",
    // D9 CMP abs,y
    "0000" + "100011" + "11000000010000000" + "00000100" + "0000001100",
    // DA iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // DB iDCP abs,y
    "0000" + "100011" + "11000000010010000" + "00000100" + "0011001100",
    // DC iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // DD CMP abs,x
    "0000" + "100011" + "11000000100000000" + "00000100" + "0000001100",
    // DE DEC abs,x
    "0000" + "100010" + "11000000100010000" + "00000100" + "0011000---",
    // DF iDCP abs,x
    "0000" + "100011" + "11000000100010000" + "00000100" + "0011001100",
    // E0 CPX imm
    "0000" + "100011" + "10000000000000000" + "00000100" + "0000001010",
    // E1 SBC (zp,x)
    "1000" + "110011" + "10010000100000000" + "00000100" + "0000011---",
    // E2 iNOP imm
    "0000" + "000000" + "10000000000000000" + "00000000" + "0000000000",
    // E3 iISC (zp,x)
    "1000" + "110011" + "10010000100010000" + "00000100" + "0010011---",
    // E4 CPX zp
    "0000" + "100011" + "10100000000000000" + "00000100" + "0000001010",
    // E5 SBC zp
    "1000" + "110011" + "10100000000000000" + "00000100" + "0000011---",
    // E6 INC zp
    "0000" + "100010" + "10100000000010000" + "00000100" + "0010000---",
    // E7 iISC zp
    "1000" + "110011" + "10100000000010000" + "00000100" + "0010011---",
    // E8 INX
    "0100" + "100010" + "00000000000000000" + "00100000" + "0010000---",
    // E9 SBC imm
    "1000" + "110011" + "10000000000000000" + "00000100" + "0000011---",
    // EA NOP
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // EB SBC imm (illegal)
    "1000" + "110011" + "10000000000000000" + "00000100" + "0000011---",
    // EC CPX abs
    "0000" + "100011" + "11000000000000000" + "00000100" + "0000001010",
    // ED SBC abs
    "1000" + "110011" + "11000000000000000" + "00000100" + "0000011---",
    // EE INC abs
    "0000" + "100010" + "11000000000010000" + "00000100" + "0010000---",
    // EF iISC abs
    "1000" + "110011" + "11000000000010000" + "00000100" + "0010011---",
    // F0 BEQ
    "0000" + "000000" + "10000001000000000" + "00000000" + "0000000000",
    // F1 SBC (zp),y
    "1000" + "110011" + "10010000010000000" + "00000100" + "0000011---",
    // F2 JAM
    "0000" + "000000" + "00000000000000001" + "00000000" + "0000000000",
    // F3 iISC (zp),y
    "1000" + "110011" + "10010000010010000" + "00000100" + "0010011---",
    // F4 iNOP zp,x
    "0000" + "000000" + "10100000100000000" + "00000000" + "0000000000",
    // F5 SBC zp,x
    "1000" + "110011" + "10100000100000000" + "00000100" + "0000011---",
    // F6 INC zp,x
    "0000" + "100010" + "10100000100010000" + "00000100" + "0010000---",
    // F7 iISC zp,x
    "1000" + "110011" + "10100000100010000" + "00000100" + "0010011---",
    // F8 SED
    "0000" + "001000" + "00000000000000000" + "00000000" + "0000000000",
    // F9 SBC abs,y
    "1000" + "110011" + "11000000010000000" + "00000100" + "0000011---",
    // FA iNOP implied
    "0000" + "000000" + "00000000000000000" + "00000000" + "0000000000",
    // FB iISC abs,y
    "1000" + "110011" + "11000000010010000" + "00000100" + "0010011---",
    // FC iNOP abs,x
    "0000" + "000000" + "11000000100000000" + "00000000" + "0000000000",
    // FD SBC abs,x
    "1000" + "110011" + "11000000100000000" + "00000100" + "0000011---",
    // FE INC abs,x
    "0000" + "100010" + "11000000100010000" + "00000100" + "0010000---",
    // FF iISC abs,x
    "1000" + "110011" + "11000000100010000" + "00000100" + "0010011---"
  )

  for (i <- 0 until 256) {
    opcodeInfoTable(i) := U(opcEntry(tableEntries(i)), 45 bits)
  }

  val opcInfo        = Reg(UInt(45 bits))
  opcInfo.simPublic()
  val nextOpcInfo    = UInt(45 bits)
  val nextOpcInfoReg = Reg(UInt(45 bits))
  val theOpcode      = Reg(UInt(8 bits))
  val nextOpcode     = UInt(8 bits)
  nextOpcode.simPublic()

  // Program counter
  val PC = Reg(UInt(16 bits))

  // Address generation
  object NextAddrDef extends SpinalEnum {
    val nextAddrHold, nextAddrIncr, nextAddrIncrL, nextAddrIncrH, nextAddrDecrH,
        nextAddrPc, nextAddrIrq, nextAddrReset, nextAddrAbs, nextAddrAbsIndexed,
        nextAddrZeroPage, nextAddrZPIndexed, nextAddrStack, nextAddrRelative = newElement()
  }
  import NextAddrDef._

  val halt_dly   = Reg(Bool()) init False
  val nextAddr   = NextAddrDef()
  val myAddr     = Reg(UInt(16 bits))
  myAddr.simPublic()
  val myAddrIncr = UInt(16 bits)
  val myAddrIncrH = UInt(8 bits)
  val myAddrDecrH = UInt(8 bits)
  val theWe      = Reg(Bool())

  val irqActive  = Reg(Bool())

  // Output register
  val doReg = Reg(UInt(8 bits))

  // Buffer register
  val T = Reg(UInt(8 bits))
  T.simPublic()

  // General registers
  val A = Reg(UInt(8 bits))
  val X = Reg(UInt(8 bits))
  val Y = Reg(UInt(8 bits))
  val S = Reg(UInt(8 bits))

  // Status register
  val Creg = Reg(Bool())
  val Zreg = Reg(Bool())
  val Ireg = Reg(Bool())
  val Dreg = Reg(Bool())
  val Vreg = Reg(Bool())
  val Nreg = Reg(Bool())

  // ALU
  val aluInput    = UInt(8 bits)
  val aluCmpInput = UInt(8 bits)
  val aluRegisterOut = UInt(8 bits)
  val aluRmwOut   = UInt(8 bits)
  val aluC = Bool()
  val aluZ = Bool()
  val aluV = Bool()
  val aluN = Bool()
  // Pipeline registers
  val aluInputReg    = Reg(UInt(8 bits))
  val aluCmpInputReg = Reg(UInt(8 bits))
  val aluRmwReg      = Reg(UInt(8 bits))
  val aluNineReg     = Reg(UInt(8 bits))
  val aluCReg = Reg(Bool())
  val aluZReg = Reg(Bool())
  val aluVReg = Reg(Bool())
  val aluNReg = Reg(Bool())

  // Indexing
  val indexOut = UInt(9 bits)

  // JAM
  val jam_flag = Bool()

  // -----------------------------------------------------------------------
  // ALU Input
  // -----------------------------------------------------------------------
  val aluInputTemp = UInt(8 bits)
  aluInputTemp := (opcInfo(opcInA)     ? A             | U"11111111") &
                  (opcInfo(opcInE)     ? (A | U"xEE")  | U"11111111") &
                  (opcInfo(opcInX)     ? X             | U"11111111") &
                  (opcInfo(opcInY)     ? Y             | U"11111111") &
                  (opcInfo(opcInS)     ? S             | U"11111111") &
                  (opcInfo(opcInT)     ? T             | U"11111111")
  when(opcInfo(opcInClear)) { aluInputTemp := U(0, 8 bits) }

  aluInputReg := aluInputTemp
  aluInput := aluInputTemp
  if (pipelineAluMux) { aluInput := aluInputReg }

  // -----------------------------------------------------------------------
  // CMP Input
  // -----------------------------------------------------------------------
  val cmpTemp = UInt(8 bits)
  cmpTemp := (opcInfo(opcInCmp) ? A | U"11111111") &
             (opcInfo(opcInCpx) ? X | U"11111111") &
             (opcInfo(opcInCpy) ? Y | U"11111111")

  aluCmpInputReg := cmpTemp
  aluCmpInput := cmpTemp
  if (pipelineAluMux) { aluCmpInput := aluCmpInputReg }

  // -----------------------------------------------------------------------
  // ALU
  // -----------------------------------------------------------------------
  // With .reverse in opcEntry, string position N maps directly to bit N.
  // mode1 = opcInfo bits 35..38, mode2 = opcInfo bits 39..41
  val mode1 = UInt(4 bits)
  mode1(3) := opcInfo(aluMode1From)
  mode1(2) := opcInfo(aluMode1From + 1)
  mode1(1) := opcInfo(aluMode1From + 2)
  mode1(0) := opcInfo(aluMode1From + 3)

  val mode2 = UInt(3 bits)
  mode2(2) := opcInfo(aluMode2From)
  mode2(1) := opcInfo(aluMode2From + 1)
  mode2(0) := opcInfo(aluMode2From + 2)

  val rmwBits  = UInt(9 bits)
  val nineBits = UInt(9 bits)
  val lowBits  = UInt(6 bits)
  val varC = Bool()
  val varZ = Bool()
  val varV = Bool()
  val varN = Bool()

  varV := aluInput(6) // Default for BIT / PLP / RTI

  // Shift unit
  rmwBits := Creg.asUInt.resize(1) @@ aluInput // default
  switch(mode1) {
    is(U"0000") { // aluModeInp
      rmwBits := Creg.asUInt.resize(1) @@ aluInput
    }
    is(U"0001") { // aluModeP
      rmwBits := Creg.asUInt.resize(1) @@ Nreg.asUInt.resize(1) @@ Vreg.asUInt.resize(1) @@ U(1, 1 bits) @@ (~irqActive).asUInt.resize(1) @@ Dreg.asUInt.resize(1) @@ Ireg.asUInt.resize(1) @@ Zreg.asUInt.resize(1) @@ Creg.asUInt.resize(1)
    }
    is(U"0010") { // aluModeInc
      rmwBits := Creg.asUInt.resize(1) @@ (aluInput + 1)
    }
    is(U"0011") { // aluModeDec
      rmwBits := Creg.asUInt.resize(1) @@ (aluInput - 1)
    }
    is(U"1010") { // aluModeAsl
      rmwBits := aluInput @@ U(0, 1 bits)
    }
    is(U"0100") { // aluModeFlg
      rmwBits := aluInput(0).asUInt.resize(1) @@ aluInput
    }
    is(U"1000") { // aluModeLsr
      rmwBits := aluInput(0).asUInt.resize(1) @@ U(0, 1 bits) @@ aluInput(7 downto 1)
    }
    is(U"1011") { // aluModeRol
      rmwBits := aluInput @@ Creg.asUInt.resize(1)
    }
    is(U"1001") { // aluModeRor
      rmwBits := aluInput(0).asUInt.resize(1) @@ Creg.asUInt.resize(1) @@ aluInput(7 downto 1)
    }
    is(U"1111") { // aluModeAnc
      rmwBits := (aluInput(7) & A(7)).asUInt.resize(1) @@ aluInput
    }
  }

  // ALU
  lowBits  := U(0, 6 bits)
  nineBits := rmwBits // default (aluModePss)
  switch(mode2) {
    is(U"010") { // aluModeAdc
      lowBits  := (U(0, 1 bits) @@ A(3 downto 0) @@ rmwBits(8).asUInt.resize(1)) + (U(0, 1 bits) @@ rmwBits(3 downto 0) @@ U(1, 1 bits))
      nineBits := (U(0, 1 bits) @@ A) + (U(0, 1 bits) @@ rmwBits(7 downto 0)) + (U(0, 8 bits) @@ rmwBits(8).asUInt.resize(1))
    }
    is(U"011") { // aluModeSbc
      lowBits  := (U(0, 1 bits) @@ A(3 downto 0) @@ rmwBits(8).asUInt.resize(1)) + (U(0, 1 bits) @@ ~rmwBits(3 downto 0) @@ U(1, 1 bits))
      nineBits := (U(0, 1 bits) @@ A) + (U(0, 1 bits) @@ ~rmwBits(7 downto 0)) + (U(0, 8 bits) @@ rmwBits(8).asUInt.resize(1))
    }
    is(U"001") { // aluModeCmp
      nineBits := (U(0, 1 bits) @@ aluCmpInput) + (U(0, 1 bits) @@ ~rmwBits(7 downto 0)) + U(1, 9 bits)
    }
    is(U"100") { // aluModeAnd
      nineBits := rmwBits(8).asUInt.resize(1) @@ (A & rmwBits(7 downto 0))
    }
    is(U"110") { // aluModeEor
      nineBits := rmwBits(8).asUInt.resize(1) @@ (A ^ rmwBits(7 downto 0))
    }
    is(U"101") { // aluModeOra
      nineBits := rmwBits(8).asUInt.resize(1) @@ (A | rmwBits(7 downto 0))
    }
    is(U"111") { // aluModeArr - pass through like Pss initially, adjusted later
      nineBits := rmwBits
    }
  }

  // Z flag
  when(mode1 === U"0100") { // aluModeFlg
    varZ := rmwBits(1)
  } elsewhen(nineBits(7 downto 0) === U(0, 8 bits)) {
    varZ := True
  } otherwise {
    varZ := False
  }

  // Decimal mode low bits correction for ADC
  val nineBitsAdj = UInt(9 bits)
  nineBitsAdj := nineBits
  when(mode2 === U"010") { // aluModeAdc
    when(Dreg) {
      when(lowBits(5 downto 1) > 9) {
        nineBitsAdj(3 downto 0) := nineBits(3 downto 0) + 6
        when(~lowBits(5)) {
          nineBitsAdj(8 downto 4) := nineBits(8 downto 4) + 1
        }
      }
    }
  }

  // N flag
  when(mode1 === U"0101" || mode1 === U"0100") { // aluModeBit or aluModeFlg
    varN := rmwBits(7)
  } otherwise {
    varN := nineBitsAdj(7)
  }

  varC := nineBitsAdj(8)

  when(mode2 === U"111") { // aluModeArr
    varC := aluInput(7)
    varV := (aluInput(7) ^ aluInput(6))
  }

  // Final corrections
  val nineBitsFinal = UInt(9 bits)
  nineBitsFinal := nineBitsAdj
  switch(mode2) {
    is(U"010") { // aluModeAdc - high bits correction
      varV := ((A(7) ^ nineBitsAdj(7)) & (rmwBits(7) ^ nineBitsAdj(7)))
      when(Dreg) {
        when(nineBitsAdj(8 downto 4) > 9) {
          nineBitsFinal(8 downto 4) := nineBitsAdj(8 downto 4) + 6
          varC := True
        }
      }
    }
    is(U"011") { // aluModeSbc
      varV := ((A(7) ^ nineBitsAdj(7)) & (~rmwBits(7) ^ nineBitsAdj(7)))
      when(Dreg) {
        when(~lowBits(5)) {
          nineBitsFinal(3 downto 0) := nineBitsAdj(3 downto 0) - 6
        }
        when(~nineBitsAdj(8)) {
          nineBitsFinal(8 downto 4) := nineBitsAdj(8 downto 4) - 6
        }
      }
    }
    is(U"111") { // aluModeArr
      when(Dreg) {
        when((U(0, 1 bits) @@ aluInput(3 downto 0)) + (U(0, 4 bits) @@ aluInput(0).asUInt.resize(1)) > 5) {
          nineBitsFinal(3 downto 0) := nineBitsAdj(3 downto 0) + 6
        }
        when((U(0, 1 bits) @@ aluInput(7 downto 4)) + (U(0, 4 bits) @@ aluInput(4).asUInt.resize(1)) > 5) {
          nineBitsFinal(8 downto 4) := nineBitsAdj(8 downto 4) + 6
          varC := True
        } otherwise {
          varC := False
        }
      }
    }
    default {}
  }

  aluRmwReg  := rmwBits(7 downto 0)
  aluNineReg := nineBitsFinal(7 downto 0)
  aluCReg := varC
  aluZReg := varZ
  aluVReg := varV
  aluNReg := varN

  aluRmwOut      := rmwBits(7 downto 0)
  aluRegisterOut := nineBitsFinal(7 downto 0)
  aluC := varC
  aluZ := varZ
  aluV := varV
  aluN := varN
  if (pipelineAluOut) {
    aluRmwOut      := aluRmwReg
    aluRegisterOut := aluNineReg
    aluC := aluCReg
    aluZ := aluZReg
    aluV := aluVReg
    aluN := aluNReg
  }

  // -----------------------------------------------------------------------
  // Interrupt calculation
  // -----------------------------------------------------------------------
  when(io.enable) {
    irqReg  := io.irq_n
    nmiEdge := io.nmi_n
    when(nmiEdge && ~io.nmi_n) {
      nmiReg := False
    }
    when(theCpuCycle === cycleStack4 || io.reset) {
      nmiReg := True
    }
    when(~io.halt) {
      when(theCpuCycle =/= cycleBranchTaken) {
        processNmi := ~(nmiReg | opcInfo(opcIRQ))
        processIrq := ~(irqReg | opcInfo(opcIRQ))
      }
    }
    processInt := processNmi | (processIrq & ~Ireg)
  }

  // -----------------------------------------------------------------------
  // Next opcode calculation
  // -----------------------------------------------------------------------
  val myNextOpcode = UInt(8 bits)
  myNextOpcode := io.d
  when(io.reset) {
    myNextOpcode := U"x4C"
  } elsewhen(processInt) {
    myNextOpcode := U"x00"
  }
  nextOpcode := myNextOpcode

  nextOpcInfo := opcodeInfoTable(nextOpcode.resize(8))

  nextOpcInfoReg := nextOpcInfo

  // -----------------------------------------------------------------------
  // opcInfo register
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    when(io.reset || (theCpuCycle === opcodeFetch)) {
      opcInfo := nextOpcInfo
      if (pipelineOpcode) { opcInfo := nextOpcInfoReg }
    }
  }

  // -----------------------------------------------------------------------
  // theOpcode register
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    when(theCpuCycle === opcodeFetch) {
      irqActive := False
      when(processInt) { irqActive := True }
      theOpcode := nextOpcode
    }
  }

  // -----------------------------------------------------------------------
  // Update registers flag
  // -----------------------------------------------------------------------
  updateRegisters := False
  when(io.enable && ~io.halt) {
    when(opcInfo(opcRti)) {
      when(theCpuCycle === cycleRead) { updateRegisters := True }
    } elsewhen(theCpuCycle === opcodeFetch) {
      updateRegisters := True
    }
  }

  // -----------------------------------------------------------------------
  // State machine advance
  // -----------------------------------------------------------------------
  io.debugOpcode := theOpcode
  io.debugJam := False
  when(io.enable && ~io.halt) {
    theCpuCycle := nextCpuCycle
    io.debugJam := jam_flag
  }
  when(io.reset) {
    theCpuCycle := cycle2
    io.debugJam := False
  }

  // -----------------------------------------------------------------------
  // Next CPU cycle calculation
  // -----------------------------------------------------------------------
  jam_flag     := False
  nextCpuCycle := opcodeFetch

  switch(theCpuCycle) {
    is(opcodeFetch) {
      nextCpuCycle := cycle2
    }
    is(cycle2) {
      if (enableJam) {
        when(opcInfo(opcJAM)) {
          nextCpuCycle := cycle2
          jam_flag     := True
        }
      }
      when(opcInfo(opcBranch)) {
        when(
          (Nreg === theOpcode(5) && theOpcode(7 downto 6) === U"00") ||
          (Vreg === theOpcode(5) && theOpcode(7 downto 6) === U"01") ||
          (Creg === theOpcode(5) && theOpcode(7 downto 6) === U"10") ||
          (Zreg === theOpcode(5) && theOpcode(7 downto 6) === U"11")
        ) {
          nextCpuCycle := cycleBranchTaken
        }
      } elsewhen(opcInfo(opcStackUp)) {
        nextCpuCycle := cycleStack1
      } elsewhen(opcInfo(opcStackAddr) && opcInfo(opcStackData)) {
        nextCpuCycle := cycleStack2
      } elsewhen(opcInfo(opcStackAddr)) {
        nextCpuCycle := cycleStack1
      } elsewhen(opcInfo(opcStackData)) {
        nextCpuCycle := cycleWrite
      } elsewhen(opcInfo(opcAbsolute)) {
        nextCpuCycle := cycle3
      } elsewhen(opcInfo(opcIndirect)) {
        when(opcInfo(indexX)) {
          nextCpuCycle := cyclePreIndirect
        } otherwise {
          nextCpuCycle := cycleIndirect
        }
      } elsewhen(opcInfo(opcZeroPage)) {
        when(opcInfo(opcWrite)) {
          when(opcInfo(indexX) || opcInfo(indexY)) {
            nextCpuCycle := cyclePreWrite
          } otherwise {
            nextCpuCycle := cycleWrite
          }
        } otherwise {
          when(opcInfo(indexX) || opcInfo(indexY)) {
            nextCpuCycle := cyclePreRead
          } otherwise {
            nextCpuCycle := cycleRead2
          }
        }
      } elsewhen(opcInfo(opcJump)) {
        nextCpuCycle := cycleJump
      }
    }
    is(cycle3) {
      nextCpuCycle := cycleRead
      when(opcInfo(opcWrite)) {
        when(opcInfo(indexX) || opcInfo(indexY)) {
          nextCpuCycle := cyclePreWrite
        } otherwise {
          nextCpuCycle := cycleWrite
        }
      }
      when(opcInfo(opcIndirect) && opcInfo(indexX)) {
        when(opcInfo(opcWrite)) {
          nextCpuCycle := cycleWrite
        } otherwise {
          nextCpuCycle := cycleRead2
        }
      }
    }
    is(cyclePreIndirect) {
      nextCpuCycle := cycleIndirect
    }
    is(cycleIndirect) {
      nextCpuCycle := cycle3
    }
    is(cycleBranchTaken) {
      when(indexOut(8) =/= T(7)) {
        nextCpuCycle := cycleBranchPage
      }
    }
    is(cyclePreRead) {
      when(opcInfo(opcZeroPage)) {
        nextCpuCycle := cycleRead2
      }
    }
    is(cycleRead) {
      when(opcInfo(opcJump)) {
        nextCpuCycle := cycleJump
      } elsewhen(indexOut(8)) {
        nextCpuCycle := cycleRead2
      } elsewhen(opcInfo(opcRmw)) {
        nextCpuCycle := cycleRmw
        when(opcInfo(indexX) || opcInfo(indexY)) {
          nextCpuCycle := cycleRead2
        }
      }
    }
    is(cycleRead2) {
      when(opcInfo(opcRmw)) {
        nextCpuCycle := cycleRmw
      }
    }
    is(cycleRmw) {
      nextCpuCycle := cycleWrite
    }
    is(cyclePreWrite) {
      nextCpuCycle := cycleWrite
    }
    is(cycleStack1) {
      nextCpuCycle := cycleRead
      when(opcInfo(opcStackAddr)) {
        nextCpuCycle := cycleStack2
      }
    }
    is(cycleStack2) {
      nextCpuCycle := cycleStack3
      when(opcInfo(opcRti)) {
        nextCpuCycle := cycleRead
      }
      when(~opcInfo(opcStackData) && opcInfo(opcStackUp)) {
        nextCpuCycle := cycleJump
      }
    }
    is(cycleStack3) {
      nextCpuCycle := cycleRead
      when(~opcInfo(opcStackData) || opcInfo(opcStackUp)) {
        nextCpuCycle := cycleJump
      } elsewhen(opcInfo(opcStackAddr)) {
        nextCpuCycle := cycleStack4
      }
    }
    is(cycleStack4) {
      nextCpuCycle := cycleRead
    }
    is(cycleJump) {
      when(opcInfo(opcIncrAfter)) {
        nextCpuCycle := cycleEnd
      }
    }
    default {}
  }

  // -----------------------------------------------------------------------
  // T register
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    switch(theCpuCycle) {
      is(cycle2) { T := io.d }
      is(cycleStack1, cycleStack2) {
        when(opcInfo(opcStackUp)) { T := io.d }
      }
      is(cycleIndirect, cycleRead, cycleRead2) { T := io.d }
      default {}
    }
  }

  // -----------------------------------------------------------------------
  // A register
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateA)) { A := aluRegisterOut }

  // -----------------------------------------------------------------------
  // X register
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateX)) { X := aluRegisterOut }

  // -----------------------------------------------------------------------
  // Y register
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateY)) { Y := aluRegisterOut }

  // -----------------------------------------------------------------------
  // C flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateC)) { Creg := aluC }

  // -----------------------------------------------------------------------
  // Z flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateZ)) { Zreg := aluZ }

  // -----------------------------------------------------------------------
  // I flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateI)) { Ireg := aluInput(2) }
  when(io.enable && (theCpuCycle === cycle2) && io.halt) {
    when(theOpcode === U"x58") { Ireg := False }
    when(theOpcode === U"x78") { Ireg := True }
  }
  when(io.enable && io.reset) { Ireg := True }

  // -----------------------------------------------------------------------
  // D flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateD)) { Dreg := aluInput(3) }

  // -----------------------------------------------------------------------
  // V flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateV)) { Vreg := aluV }
  when(so_reg && ~io.so_n) { Vreg := True }
  so_reg := io.so_n

  // -----------------------------------------------------------------------
  // N flag
  // -----------------------------------------------------------------------
  when(updateRegisters && opcInfo(opcUpdateN)) { Nreg := aluN }

  // -----------------------------------------------------------------------
  // Stack pointer
  // -----------------------------------------------------------------------
  when(io.reset) {
    S := U"xFF"
  }

  val sIncDec = UInt(8 bits)
  sIncDec := Mux(opcInfo(opcStackUp), S + 1, S - 1)

  when(io.enable && ~io.halt) {
    val spUpdateFlag = Bool()
    spUpdateFlag := False
    switch(nextCpuCycle) {
      is(cycleStack1) {
        when(opcInfo(opcStackUp) || opcInfo(opcStackData)) { spUpdateFlag := True }
      }
      is(cycleStack2) { spUpdateFlag := True }
      is(cycleStack3) { spUpdateFlag := True }
      is(cycleStack4) { spUpdateFlag := True }
      is(cycleRead)   { when(opcInfo(opcRti)) { spUpdateFlag := True } }
      is(cycleWrite)  { when(opcInfo(opcStackData)) { spUpdateFlag := True } }
      default {}
    }
    when(spUpdateFlag) { S := sIncDec }
  }
  when(updateRegisters && opcInfo(opcUpdateS)) { S := aluRegisterOut }

  // -----------------------------------------------------------------------
  // Data out
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    doReg := aluRmwOut
    when(opcInfo(opcInH) && ~halt_dly) {
      doReg := aluRmwOut & myAddrIncrH
    }
    switch(nextCpuCycle) {
      is(cycleStack2) {
        when(opcInfo(opcIRQ) && ~irqActive) {
          doReg := myAddrIncr(15 downto 8)
        } otherwise {
          doReg := PC(15 downto 8)
        }
      }
      is(cycleStack3) { doReg := PC(7 downto 0) }
      is(cycleRmw)    { doReg := io.d }
      default {}
    }
  }
  io.q := doReg

  // -----------------------------------------------------------------------
  // Write enable
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    theWe := False
    switch(nextCpuCycle) {
      is(cycleStack1) {
        when(~opcInfo(opcStackUp) && (~opcInfo(opcStackAddr) || opcInfo(opcStackData))) {
          theWe := True
        }
      }
      is(cycleStack2, cycleStack3, cycleStack4) {
        when(~opcInfo(opcStackUp)) { theWe := True }
      }
      is(cycleRmw)   { theWe := True }
      is(cycleWrite)  { theWe := True }
      default {}
    }
    when(io.reset) { theWe := False }
  }
  io.we := theWe

  // -----------------------------------------------------------------------
  // Program counter
  // -----------------------------------------------------------------------
  when(io.enable && ~io.halt) {
    switch(theCpuCycle) {
      is(opcodeFetch) { PC := myAddr }
      is(cycle2) {
        when(~irqActive) {
          when(opcInfo(opcSecondByte)) { PC := myAddrIncr } otherwise { PC := myAddr }
        }
      }
      is(cycle3) { when(opcInfo(opcAbsolute)) { PC := myAddrIncr } }
      default {}
    }
  }
  io.debugPc := PC

  // -----------------------------------------------------------------------
  // Address generation - next address calculation
  // -----------------------------------------------------------------------
  nextAddr := nextAddrIncr
  switch(theCpuCycle) {
    is(opcodeFetch) { when(processInt) { nextAddr := nextAddrHold } }
    is(cycle2) {
      when(opcInfo(opcStackAddr) || opcInfo(opcStackData)) { nextAddr := nextAddrStack
      } elsewhen(opcInfo(opcAbsolute)) { nextAddr := nextAddrIncr
      } elsewhen(opcInfo(opcZeroPage)) { nextAddr := nextAddrZeroPage
      } elsewhen(opcInfo(opcIndirect)) { nextAddr := nextAddrZeroPage
      } elsewhen(opcInfo(opcSecondByte)) { nextAddr := nextAddrIncr
      } otherwise { nextAddr := nextAddrHold }
    }
    is(cycle3) {
      when(opcInfo(opcIndirect) && opcInfo(indexX)) { nextAddr := nextAddrAbs
      } otherwise { nextAddr := nextAddrAbsIndexed }
    }
    is(cyclePreIndirect) { nextAddr := nextAddrZPIndexed }
    is(cycleIndirect) { nextAddr := nextAddrIncrL }
    is(cycleBranchTaken) { nextAddr := nextAddrRelative }
    is(cycleBranchPage) {
      when(~T(7)) { nextAddr := nextAddrIncrH
      } otherwise { nextAddr := nextAddrDecrH }
    }
    is(cyclePreRead) { nextAddr := nextAddrZPIndexed }
    is(cycleRead) {
      nextAddr := nextAddrPc
      when(opcInfo(opcJump)) { nextAddr := nextAddrIncrL
      } elsewhen(indexOut(8)) { nextAddr := nextAddrIncrH
      } elsewhen(opcInfo(opcRmw)) { nextAddr := nextAddrHold }
    }
    is(cycleRead2) {
      nextAddr := nextAddrPc
      when(opcInfo(opcRmw)) { nextAddr := nextAddrHold }
    }
    is(cycleRmw)      { nextAddr := nextAddrHold }
    is(cyclePreWrite) {
      nextAddr := nextAddrHold
      when(opcInfo(opcZeroPage)) { nextAddr := nextAddrZPIndexed
      } elsewhen(indexOut(8)) { nextAddr := nextAddrIncrH }
    }
    is(cycleWrite)  { nextAddr := nextAddrPc }
    is(cycleStack1) { nextAddr := nextAddrStack }
    is(cycleStack2) { nextAddr := nextAddrStack }
    is(cycleStack3) {
      nextAddr := nextAddrStack
      when(~opcInfo(opcStackData)) { nextAddr := nextAddrPc }
    }
    is(cycleStack4) { nextAddr := nextAddrIrq }
    is(cycleJump)   { nextAddr := nextAddrAbs }
    default {}
  }
  when(io.reset) { nextAddr := nextAddrReset }

  // -----------------------------------------------------------------------
  // Index ALU
  // -----------------------------------------------------------------------
  when(opcInfo(indexX)) {
    indexOut := (U(0, 1 bits) @@ T) + (U(0, 1 bits) @@ X)
  } elsewhen(opcInfo(indexY)) {
    indexOut := (U(0, 1 bits) @@ T) + (U(0, 1 bits) @@ Y)
  } elsewhen(opcInfo(opcBranch)) {
    indexOut := (U(0, 1 bits) @@ T) + (U(0, 1 bits) @@ myAddr(7 downto 0))
  } otherwise {
    indexOut := U(0, 1 bits) @@ T
  }

  // -----------------------------------------------------------------------
  // Address register
  // -----------------------------------------------------------------------
  when(io.enable) { halt_dly := io.halt }
  when(io.enable && ~io.halt) {
    switch(nextAddr) {
      is(nextAddrIncr)       { myAddr := myAddrIncr }
      is(nextAddrIncrL)      { myAddr(7 downto 0) := myAddrIncr(7 downto 0) }
      is(nextAddrDecrH)      { myAddr(15 downto 8) := myAddrDecrH }
      is(nextAddrPc)         { myAddr := PC }
      is(nextAddrIrq)        {
        myAddr := U"xFFFE"
        when(~nmiReg) { myAddr := U"xFFFA" }
      }
      is(nextAddrReset)      { myAddr := U"xFFFC" }
      is(nextAddrAbs)        { myAddr := io.d @@ T }
      is(nextAddrAbsIndexed) { myAddr := io.d @@ indexOut(7 downto 0) }
      is(nextAddrZeroPage)   { myAddr := U(0, 8 bits) @@ io.d }
      is(nextAddrZPIndexed)  { myAddr := U(0, 8 bits) @@ indexOut(7 downto 0) }
      is(nextAddrStack)      { myAddr := U(1, 8 bits) @@ S }
      is(nextAddrRelative)   { myAddr(7 downto 0) := indexOut(7 downto 0) }
      default {}
    }
  }
  when(io.enable && ~halt_dly) {
    when(nextAddr === nextAddrIncrH) { myAddr(15 downto 8) := myAddrIncrH }
  }

  myAddrIncr  := myAddr + 1
  myAddrIncrH := Mux(
    (opcInfo(opcInH) & (opcInfo(opcInY) | opcInfo(opcInX))),
    aluRmwOut & (myAddr(15 downto 8) + 1),
    myAddr(15 downto 8) + 1
  )
  myAddrDecrH := myAddr(15 downto 8) - 1

  io.addr := myAddr

  io.debugA      := A
  io.debugX      := X
  io.debugY      := Y
  io.debugS      := S
  io.debug_flags := Nreg.asUInt.resize(1) @@ Vreg.asUInt.resize(1) @@ U(1, 1 bits) @@ U(0, 1 bits) @@ Dreg.asUInt.resize(1) @@ Ireg.asUInt.resize(1) @@ Zreg.asUInt.resize(1) @@ Creg.asUInt.resize(1)
}
