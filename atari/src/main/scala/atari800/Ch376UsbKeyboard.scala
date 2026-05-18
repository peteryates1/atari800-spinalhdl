package atari800

import spinal.core._
import spinal.lib._

// CH376T USB keyboard bridge — autonomous FSM that initializes CH376T in USB
// host mode, enumerates a low-speed USB keyboard, polls HID boot protocol
// reports, and presents the result as an Atari 800 keyboard matrix.
//
// SPI interface directly drives CH376T.  No CPU needed.
//
// References:
//   CH376DS2.PDF — auxiliary commands + USB host transactions
//   tools/ch376_keyboard.py — working Python keyboard test
//   FPGC6 BCC/BDOS/lib/usbkeyboard.c — working C implementation
//   Ps2ToAtari800.scala — Atari keyboard matrix positions
class Ch376UsbKeyboard(sysClkHz: Int = 50000000) extends Component {
  val io = new Bundle {
    // CH376T SPI
    val spiSck   = out Bool()
    val spiMosi  = out Bool()
    val spiMiso  = in  Bool()
    val spiCsN   = out Bool()
    val intN     = in  Bool()
    val rstOut   = out Bool()
    val spiModeN = out Bool()

    // Atari keyboard matrix interface (active low response)
    val keyboardScan     = in  Bits(6 bits)
    val keyboardResponse = out Bits(2 bits)

    // Console keys directly from USB keyboard
    val consolStart  = out Bool()
    val consolSelect = out Bool()
    val consolOption = out Bool()

    // Status
    val connected = out Bool()
    val dbgState  = out Bits(6 bits)

    // POKEY feedback (from Atari core, for debug logging)
    val pokeyKeyIrq    = in Bool()   // pulse when POKEY registers a keypress
    val pokeyKeyHeld   = in Bool()   // POKEY sees key held
    val kbIrqEnabled   = in Bool()   // IRQEN bit 6 — keyboard IRQ enabled by software
    val kbIrqPending   = in Bool()   // keyboard IRQ pending (not yet acknowledged)
    val kbIrqAck       = in Bool()   // pulse when keyboard IRQ is acknowledged by CPU

    // Debug UART TX (optional — directly from keyboard bridge)
    val uartTx = out Bool()
  }

  io.spiModeN := False // always SPI mode

  // ---- Input synchronizers ----
  val misoSync = Reg(Bool()) init True
  val misoS2   = Reg(Bool()) init True
  misoS2   := io.spiMiso
  misoSync := misoS2
  val miso = misoSync

  val intSync = Reg(Bool()) init True
  val intS2   = Reg(Bool()) init True
  intS2   := io.intN
  intSync := intS2
  val intPin = intSync

  // ---- SPI byte engine (Mode 0: CPOL=0, CPHA=0, MSB first) ----
  val spiDiv    = Reg(UInt(16 bits)) init U(sysClkHz / (2 * 50000) - 1, 16 bits)
  val spiCnt    = Reg(UInt(16 bits)) init 0
  val spiBit    = Reg(UInt(4 bits))  init 0
  val spiTxSr   = Reg(Bits(8 bits))  init 0xFF
  val spiRxSr   = Reg(Bits(8 bits))  init 0
  val spiRxData = Reg(Bits(8 bits))  init 0
  val spiClk    = Reg(Bool())        init False
  val spiBusy   = Reg(Bool())        init False
  val spiDone   = Reg(Bool())        init False

  io.spiSck  := spiClk
  io.spiMosi := spiTxSr(7)

  // Start an SPI byte transfer (directly driven by FSM)
  val spiGo   = Bool()
  val spiTxIn = Bits(8 bits)
  spiGo   := False
  spiTxIn := 0

  spiDone := False
  when(!spiBusy) {
    spiClk := False
    when(spiGo) {
      spiTxSr := spiTxIn
      spiBusy := True
      spiBit  := 0
      spiCnt  := 0
    }
  } otherwise {
    when(spiCnt === spiDiv) {
      spiCnt := 0
      spiClk := ~spiClk
      when(!spiClk) {
        // Rising edge — sample MISO
        spiRxSr := spiRxSr(6 downto 0) ## miso
      } otherwise {
        // Falling edge — advance
        when(spiBit === 7) {
          spiBusy   := False
          spiClk    := False
          spiRxData := spiRxSr
          spiDone   := True
        } otherwise {
          spiTxSr := spiTxSr(6 downto 0) ## True
          spiBit  := spiBit + 1
        }
      }
    } otherwise {
      spiCnt := spiCnt + 1
    }
  }

  // ---- CS register ----
  val csReg = Reg(Bool()) init True
  io.spiCsN := csReg

  // ---- Delay counter ----
  val delayCnt  = Reg(UInt(32 bits)) init 0
  val delayDone = delayCnt === 0
  when(!delayDone) { delayCnt := delayCnt - 1 }

  // Helpers
  def usToClocks(us: Int): Int = (us.toLong * sysClkHz / 1000000).toInt
  def msToClocks(ms: Int): Int = (ms.toLong * sysClkHz / 1000).toInt

  // ---- Boot reset (hold RSTI high ~84ms so SPI# pin is sampled) ----
  val bootCnt  = Reg(UInt(23 bits)) init 0
  val bootDone = bootCnt(22)
  when(!bootDone) { bootCnt := bootCnt + 1 }
  io.rstOut := !bootDone

  // ---- Command executor ----
  // Generic CH376 SPI command: CS low, cmd byte, 3µs delay, N write bytes,
  // M read bytes, CS high.
  val cmdCode   = Reg(Bits(8 bits))  init 0
  val cmdWrBuf  = Vec(Reg(Bits(8 bits)) init 0, 10)
  val cmdWrLen  = Reg(UInt(4 bits))  init 0  // 0..9 write bytes
  val cmdWrIdx  = Reg(UInt(4 bits))  init 0
  val cmdRdLen  = Reg(UInt(4 bits))  init 0
  val cmdRd     = Vec(Reg(Bits(8 bits)) init 0, 10)
  val cmdRdIdx  = Reg(UInt(4 bits))  init 0
  val cmdStep   = Reg(UInt(4 bits))  init 0
  val cmdBusy   = Reg(Bool())        init False
  val cmdGo     = Bool()
  cmdGo := False

  when(cmdGo && !cmdBusy) {
    cmdBusy := True
    cmdStep := 0
  }

  when(cmdBusy) {
    switch(cmdStep) {
      is(0) { csReg := False; cmdStep := 1 }
      is(1) { spiGo := True; spiTxIn := cmdCode; cmdStep := 2 }
      is(2) { when(spiDone) { delayCnt := U(usToClocks(3), 32 bits); cmdStep := 3 } }
      is(3) { when(delayDone) {
        when(cmdWrLen =/= 0)      { cmdWrIdx := 0; cmdStep := 4 }
        .elsewhen(cmdRdLen =/= 0) { cmdRdIdx := 0; cmdStep := 7 }
        .otherwise                { csReg := True; cmdBusy := False }
      }}
      // Write bytes loop
      is(4) { spiGo := True; spiTxIn := cmdWrBuf(cmdWrIdx); cmdStep := 5 }
      is(5) { when(spiDone) {
        when(cmdWrIdx === cmdWrLen - 1) {
          // All bytes written
          when(cmdRdLen =/= 0) { cmdRdIdx := 0; cmdStep := 7 }
          .otherwise           { csReg := True; cmdBusy := False }
        } .otherwise {
          cmdWrIdx := cmdWrIdx + 1
          cmdStep := 4
        }
      }}
      // Read bytes loop
      is(7) { spiGo := True; spiTxIn := B(0xFF); cmdStep := 8 }
      is(8) { when(spiDone) {
        cmdRd(cmdRdIdx) := spiRxData
        when(cmdRdIdx === cmdRdLen - 1) {
          csReg := True; cmdBusy := False
        } otherwise {
          cmdRdIdx := cmdRdIdx + 1
          cmdStep := 7
        }
      }}
      default { cmdBusy := False }
    }
  }

  // ---- Keyboard state (live — read by POKEY scan) ----
  val atariKeys    = Reg(Bits(64 bits)) init 0
  val shiftPressed = Reg(Bool()) init False
  val ctrlPressed  = Reg(Bool()) init False
  val breakKey     = Reg(Bool()) init False
  val connectedReg = Reg(Bool()) init False
  val startKey     = Reg(Bool()) init False
  val selectKey    = Reg(Bool()) init False
  val optionKey    = Reg(Bool()) init False

  // ---- Shadow regs (built during HID parse, committed atomically) ----
  val nextKeys   = Reg(Bits(64 bits)) init 0
  val nextShift  = Reg(Bool()) init False
  val nextCtrl   = Reg(Bool()) init False
  val nextBreak  = Reg(Bool()) init False
  val nextStart  = Reg(Bool()) init False
  val nextSelect = Reg(Bool()) init False
  val nextOption = Reg(Bool()) init False

  io.connected    := connectedReg
  io.consolStart  := startKey
  io.consolSelect := selectKey
  io.consolOption := optionKey

  // ---- KEYBOARD_RESPONSE generation (matches Ps2ToAtari800) ----
  io.keyboardResponse := B"11"
  val scanIdx = (~io.keyboardScan).asUInt
  when(atariKeys(scanIdx)) {
    io.keyboardResponse(0) := False
  }
  when(io.keyboardScan(5 downto 4) === B"00" && breakKey) {
    io.keyboardResponse(1) := False
  }
  when(io.keyboardScan(5 downto 4) === B"10" && shiftPressed) {
    io.keyboardResponse(1) := False
  }
  when(io.keyboardScan(5 downto 4) === B"11" && ctrlPressed) {
    io.keyboardResponse(1) := False
  }

  // ---- HID keycode to Atari matrix position lookup ----
  // Returns 7 bits: bit 6 = valid, bits 5:0 = matrix position
  // Build init content as a mutable array, then freeze
  val hidMapInit = Array.fill(128)(0) // 0 = unmapped (bit 6 = 0)

  // Letters: HID 0x04..0x1D -> Atari matrix positions
  val letterMap = Seq(
    63, 21, 18, 58, 42, 56, 61, 57,  // a-h
    13,  1,  5,  0, 37, 35,  8, 10,  // i-p
    47, 40, 62, 45, 11, 16, 46, 22,  // q-x
    43, 23                            // y-z
  )
  for (i <- letterMap.indices)
    hidMapInit(i + 0x04) = 0x40 | letterMap(i)

  // Digits: HID 0x1E..0x27
  val digitMap = Seq(31, 30, 26, 24, 29, 27, 51, 53, 48, 50)  // 1-9, 0
  for (i <- digitMap.indices)
    hidMapInit(i + 0x1E) = 0x40 | digitMap(i)

  // Special keys
  val specialMap = Seq(
    (0x28, 12),  // Return
    (0x29, 28),  // Escape
    (0x2A, 52),  // Backspace
    (0x2B, 44),  // Tab
    (0x2C, 33),  // Space
    (0x2D, 14),  // Minus
    (0x2E, 15),  // Equals
    (0x2F,  6),  // [ -> Atari +
    (0x30,  7),  // ] -> Atari *
    (0x31, 54),  // \ -> Atari <
    (0x33,  2),  // ; -> Atari ;
    (0x34, 55),  // ' -> Atari >
    (0x35, 39),  // ` -> Atari INVERSE
    (0x36, 32),  // , -> Atari ,
    (0x37, 34),  // . -> Atari .
    (0x38, 38),  // / -> Atari /
    (0x39, 60),  // Caps Lock -> CAPS
    (0x3A, 17),  // F1 -> HELP
    (0x3B,  3),  // F2 -> 1200XL F1
    (0x3C,  4),  // F3 -> 1200XL F2
    (0x3D, 19),  // F4 -> 1200XL F3
  )
  for ((hid, atari) <- specialMap)
    hidMapInit(hid) = 0x40 | atari

  val hidMap = Mem(Bits(7 bits), hidMapInit.map(v => B(v, 7 bits)))

  // ---- Debug logging flags (used by FSM, must be declared before it) ----
  val dbgLogSuccess  = Reg(Bool()) init False
  val dbgLogError    = Reg(Bool()) init False
  val dbgNakCount    = Reg(UInt(8 bits)) init 0
  val dbgPokeyIrqSeen = Reg(Bool()) init False  // latched: POKEY IRQ fired since last log
  // Latch POKEY key IRQ pulses between log messages
  when(io.pokeyKeyIrq) { dbgPokeyIrqSeen := True }

  // ---- Main FSM ----
  val state      = Reg(UInt(7 bits))  init 0
  val waitInt    = Reg(Bool())        init False
  val intTimeout = Reg(UInt(32 bits)) init 0
  val dataToggle = Reg(Bool())        init False
  val usbSpeed    = Reg(Bits(8 bits))  init B(0x02) // 0x00=full, 0x02=low; toggles on enum fail
  val retryCount  = Reg(UInt(4 bits))  init 0
  val parseIdx    = Reg(UInt(3 bits))  init 0
  val kbdEndpoint = Reg(UInt(4 bits))  init 1     // endpoint to poll (1 for most, 2 for some composite)
  val kbdIface    = Reg(UInt(8 bits))  init 0     // interface number for SET_PROTOCOL
  val stallCount  = Reg(UInt(12 bits)) init 0     // STALL/error counter for endpoint fallback

  // Enumeration event logging — main FSM writes, message builder reads
  val enumLogLabel   = Reg(Bits(8 bits)) init 0
  val enumLogVal     = Reg(Bits(8 bits)) init 0
  val enumLogTrigger = Reg(Bool()) init False

  io.dbgState := state.asBits.resized

  // INT# wait logic with timeout
  when(waitInt) {
    when(!intPin) {
      waitInt := False
    }
    when(intTimeout =/= 0) {
      intTimeout := intTimeout - 1
    } otherwise {
      waitInt := False  // timeout
    }
  }

  // FSM runs when: no command in progress, no INT# wait, no delay pending
  val fsmReady = !cmdBusy && !waitInt && delayDone && !spiDone

  when(fsmReady) {
    switch(state) {

      // ---- Boot ----
      is(0) { // Wait for boot reset counter
        when(bootDone) {
          delayCnt := U(msToClocks(10), 32 bits)
          state := 1
        }
      }

      // ---- CHECK_EXIST (50 kHz SPI) ----
      is(1) { // Start CHECK_EXIST
        cmdCode := B(0x06); cmdWrBuf(0) := B(0xA5); cmdWrLen := 1; cmdRdLen := 1
        cmdGo := True; state := 2
      }
      is(2) { // Check result
        when(cmdRd(0) === B(0x5A)) {
          // RESET_ALL
          cmdCode := B(0x05); cmdWrLen := 0; cmdRdLen := 0
          cmdGo := True; state := 3
        } otherwise {
          retryCount := retryCount + 1
          when(retryCount >= 10) { delayCnt := U(msToClocks(1000), 32 bits); state := 0 }
          .otherwise { delayCnt := U(msToClocks(100), 32 bits); state := 1 }
        }
      }

      // ---- RESET + verify ----
      is(3) { // RESET_ALL sent, wait 100ms
        delayCnt := U(msToClocks(100), 32 bits); state := 4
      }
      is(4) { // CHECK_EXIST after reset
        cmdCode := B(0x06); cmdWrBuf(0) := B(0xA5); cmdWrLen := 1; cmdRdLen := 1
        cmdGo := True; state := 5
      }
      is(5) { // Verify, speed up SPI
        when(cmdRd(0) === B(0x5A)) {
          spiDiv := U(sysClkHz / (2 * 2000000) - 1, 16 bits)  // 2 MHz
          state := 6
        } otherwise {
          delayCnt := U(msToClocks(500), 32 bits); state := 0
        }
      }

      // ---- USB host mode ----
      is(6) { // SET_USB_MODE(0x05) — host, no SOF
        cmdCode := B(0x15); cmdWrBuf(0) := B(0x05); cmdWrLen := 1; cmdRdLen := 1
        cmdGo := True; state := 7
      }
      is(7) { // Check mode result (0x51 = OK), wait for USB connect
        waitInt := True; intTimeout := U(msToClocks(5000), 32 bits)
        connectedReg := False; state := 8
      }
      is(8) { // INT# fired or timeout — GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 9
      }
      is(9) { // Check for USB_INT_CONNECT (0x15)
        // Log: W:XX — what GET_STATUS returned while waiting for connect
        enumLogLabel := B(0x57, 8 bits)  // 'W'
        enumLogVal   := cmdRd(0)
        enumLogTrigger := True
        when(cmdRd(0) === B(0x15)) {
          state := 10  // connected!
        } otherwise {
          // Keep waiting (might be spurious event)
          waitInt := True; intTimeout := U(msToClocks(5000), 32 bits)
          state := 8
        }
      }

      // ---- Bus reset ----
      is(10) { // SET_USB_MODE(0x07) — bus reset (SE0)
        cmdCode := B(0x15); cmdWrBuf(0) := B(0x07); cmdWrLen := 1; cmdRdLen := 1
        cmdGo := True; state := 11
      }
      is(11) { // Wait 20ms for bus reset
        delayCnt := U(msToClocks(20), 32 bits); state := 12
      }
      is(12) { // SET_USB_MODE(0x06) — host with SOF
        cmdCode := B(0x15); cmdWrBuf(0) := B(0x06); cmdWrLen := 1; cmdRdLen := 1
        cmdGo := True; state := 13
      }
      is(13) { // Wait 50ms + drain events
        delayCnt := U(msToClocks(50), 32 bits); state := 14
      }

      // ---- Drain pending interrupts ----
      is(14) { // Check INT# pin
        when(!intPin) {
          cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
          cmdGo := True; state := 15
        } otherwise {
          state := 16  // no more events
        }
      }
      is(15) { // Got status, delay, drain again
        delayCnt := U(msToClocks(10), 32 bits); state := 14
      }

      // ---- Set low-speed + address ----
      is(16) { // SET_USB_ADDR(0x00)
        cmdCode := B(0x13); cmdWrBuf(0) := B(0x00); cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 17
      }
      is(17) { // SET_USB_SPEED — auto: try current usbSpeed setting
        cmdCode := B(0x04); cmdWrBuf(0) := usbSpeed; cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 18
      }
      is(18) { // Wait 10ms, drain after speed change
        delayCnt := U(msToClocks(10), 32 bits); state := 19
      }
      is(19) { // Drain events after speed change
        when(!intPin) {
          cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
          cmdGo := True; state := 20
        } otherwise {
          state := 60   // → GET_DESCR before SET_ADDRESS (V1.1 chip needs this)
        }
      }
      is(20) { // Drained, loop
        delayCnt := U(msToClocks(10), 32 bits); state := 19
      }

      // ---- GET_DESCR(DEVICE) before SET_ADDRESS ----
      // The CH376T high-level CMD_SET_ADDRESS (0x45) fails on V1.1's chip rev
      // (IC_VER 0x45) if the device hasn't first responded to a control IN.
      // FPGC's reference enumeration (ch376.c) reads the device descriptor
      // first then sets the address. We do the same — issue GET_DESCR(DEVICE)
      // and consume the result so the chip's buffer state is clean.
      is(60) { // GET_DESCR — type 0x01 = DEVICE
        cmdCode := B(0x46); cmdWrBuf(0) := B(0x01); cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 61
      }
      is(61) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(1000), 32 bits)
        state := 62
      }
      is(62) { // GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 63
      }
      is(63) { // Check result — 'D' label
        enumLogLabel := B(0x44, 8 bits)  // 'D' — GET_DESCR result
        enumLogVal   := cmdRd(0)
        enumLogTrigger := True
        when(cmdRd(0) === B(0x14)) {
          // Success — drain the descriptor bytes (don't care about contents,
          // just clear the chip's USB buffer).
          cmdCode := B(0x27); cmdWrLen := 0; cmdRdLen := 8  // RD_USB_DATA0, first 8 bytes
          cmdGo := True; state := 64
        } otherwise {
          // GET_DESCR failed — retry enumeration with toggled speed
          usbSpeed := Mux(usbSpeed === B(0x02, 8 bits), B(0x00, 8 bits), B(0x02, 8 bits))
          retryCount := retryCount + 1
          when(retryCount >= 5) { delayCnt := U(msToClocks(2000), 32 bits); state := 0 }
          .otherwise { delayCnt := U(msToClocks(200), 32 bits); state := 10 }
        }
      }
      is(64) { // Descriptor drained, proceed to SET_ADDRESS
        delayCnt := U(msToClocks(5), 32 bits)
        state := 21
      }

      // ---- Enumerate: SET_ADDRESS ----
      is(21) { // CMD_SET_ADDRESS(0x01) — high-level command
        cmdCode := B(0x45); cmdWrBuf(0) := B(0x01); cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 22
      }
      is(22) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(2000), 32 bits)
        state := 23
      }
      is(23) { // GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 24
      }
      is(24) { // Check USB_INT_SUCCESS (0x14)
        // Log: A:XX — SET_ADDRESS result
        enumLogLabel := B(0x41, 8 bits)  // 'A'
        enumLogVal   := cmdRd(0)
        enumLogTrigger := True
        when(cmdRd(0) === B(0x14)) {
          state := 25  // success
        } otherwise {
          // Retry enumeration — toggle speed between full (0x00) and low (0x02)
          usbSpeed := Mux(usbSpeed === B(0x02, 8 bits), B(0x00, 8 bits), B(0x02, 8 bits))
          retryCount := retryCount + 1
          when(retryCount >= 5) { delayCnt := U(msToClocks(2000), 32 bits); state := 0 }
          .otherwise { delayCnt := U(msToClocks(200), 32 bits); state := 10 }
        }
      }

      // ---- SET_USB_ADDR + SET_CONFIG ----
      is(25) { // SET_USB_ADDR(0x01) — tell CH376 to use address 1
        cmdCode := B(0x13); cmdWrBuf(0) := B(0x01); cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 26
      }
      is(26) { // CMD_SET_CONFIG(0x01) — high-level command
        cmdCode := B(0x49); cmdWrBuf(0) := B(0x01); cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 27
      }
      is(27) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(2000), 32 bits)
        state := 28
      }
      is(28) { // GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 29
      }
      is(29) { // Check success
        // Log: E:XX — SET_CONFIG result
        enumLogLabel := B(0x45, 8 bits)  // 'E'
        enumLogVal   := cmdRd(0)
        enumLogTrigger := True
        when(cmdRd(0) === B(0x14)) {
          connectedReg := True
          retryCount := 0
          dataToggle := False
          kbdEndpoint := 1
          kbdIface := 0
          stallCount := 0
          state := 30  // skip SET_PROTOCOL for now — test polling directly
        } otherwise {
          delayCnt := U(msToClocks(1000), 32 bits); state := 0
        }
      }

      // ==== SET_PROTOCOL(boot) control transfer ====
      // Sends HID SET_PROTOCOL(0) to force boot protocol mode.
      // Required for composite keyboards; harmless for simple ones.
      // Setup packet: bmRequestType=0x21, bRequest=0x0B, wValue=0x0000,
      //               wIndex=interface, wLength=0x0000

      is(40) { // WR_HOST_DATA — write 8-byte setup packet
        cmdCode := B(0x2C)  // WR_HOST_DATA
        cmdWrBuf(0) := B(0x08)  // length = 8
        cmdWrBuf(1) := B(0x21)  // bmRequestType: host-to-device, class, interface
        cmdWrBuf(2) := B(0x0B)  // bRequest: SET_PROTOCOL
        cmdWrBuf(3) := B(0x00)  // wValue low: 0 = boot protocol
        cmdWrBuf(4) := B(0x00)  // wValue high
        cmdWrBuf(5) := kbdIface.asBits.resize(8)  // wIndex low: interface number
        cmdWrBuf(6) := B(0x00)  // wIndex high
        cmdWrBuf(7) := B(0x00)  // wLength low
        cmdWrBuf(8) := B(0x00)  // wLength high
        cmdWrLen := 9; cmdRdLen := 0
        cmdGo := True; state := 41
      }
      is(41) { // SET_ENDP7 — OUT toggle DATA0 for SETUP phase
        cmdCode := B(0x1D); cmdWrBuf(0) := B(0x80)  // sync DATA0
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 42
      }
      is(42) { // ISSUE_TOKEN — SETUP to endpoint 0
        cmdCode := B(0x4F); cmdWrBuf(0) := B(0x0D)  // (EP0 << 4) | PID_SETUP(0x0D)
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 43
      }
      is(43) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(500), 32 bits)
        state := 44
      }
      is(44) { // GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 45
      }
      is(45) { // Check SETUP result
        // Log: S:XX — SET_PROTOCOL SETUP phase result
        enumLogLabel := B(0x53, 8 bits)  // 'S'
        enumLogVal   := cmdRd(0)
        enumLogTrigger := True
        when(cmdRd(0) === B(0x14)) {
          // Success — now send IN for zero-length status phase
          state := 46
        } otherwise {
          // STALL or error — skip SET_PROTOCOL, go straight to polling
          // (simple keyboards may not support it)
          dataToggle := False
          state := 30
        }
      }
      is(46) { // SET_ENDP6 — IN toggle DATA1 for status phase
        cmdCode := B(0x1C); cmdWrBuf(0) := B(0xC0)  // sync DATA1
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 47
      }
      is(47) { // ISSUE_TOKEN — IN from endpoint 0 (status phase)
        cmdCode := B(0x4F); cmdWrBuf(0) := B(0x09)  // (EP0 << 4) | PID_IN(0x09)
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 48
      }
      is(48) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(500), 32 bits)
        state := 49
      }
      is(49) { // GET_STATUS and proceed to polling
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 50
      }
      is(50) { // SET_PROTOCOL done — start polling regardless of status result
        dataToggle := False
        delayCnt := U(msToClocks(10), 32 bits)  // brief settle after SET_PROTOCOL
        state := 30
      }

      // ==== Polling loop ====

      is(30) { // SET_ENDP6 — set IN data toggle
        cmdCode := B(0x1C)
        cmdWrBuf(0) := Mux(dataToggle, B(0xC0), B(0x80))
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 31
      }
      is(31) { // ISSUE_TOKEN — IN from keyboard endpoint
        cmdCode := B(0x4F)
        cmdWrBuf(0) := (kbdEndpoint.asBits ## B(0x9, 4 bits))  // (ep << 4) | PID_IN
        cmdWrLen := 1; cmdRdLen := 0
        cmdGo := True; state := 32
      }
      is(32) { // Wait for INT#
        waitInt := True; intTimeout := U(msToClocks(500), 32 bits)
        state := 33
      }
      is(33) { // GET_STATUS
        cmdCode := B(0x22); cmdWrLen := 0; cmdRdLen := 1
        cmdGo := True; state := 34
      }
      is(34) { // Branch on status
        when(cmdRd(0) === B(0x14)) {
          // USB_INT_SUCCESS — got data
          dataToggle := ~dataToggle
          stallCount := 0  // reset error counter on success
          // Read HID report: RD_USB_DATA -> length + 8 bytes
          cmdCode := B(0x28); cmdWrLen := 0; cmdRdLen := 9
          cmdGo := True; state := 35
        } .elsewhen(cmdRd(0) === B(0x2A)) {
          // NAK — no new data, retry immediately
          dbgNakCount := dbgNakCount + 1
          // Log first NAK only (to confirm we're actually polling)
          when(dbgNakCount === 0) {
            enumLogLabel := B(0x4E, 8 bits)  // 'N' — first NAK
            enumLogVal   := B(0x2A)
            enumLogTrigger := True
          }
          state := 30
        } .elsewhen(cmdRd(0) === B(0x15) || cmdRd(0) === B(0x16)) {
          // Connect/disconnect — re-enumerate
          connectedReg := False
          delayCnt := U(msToClocks(500), 32 bits); state := 0
        } .elsewhen(cmdRd(0) === B(0x2E)) {
          // STALL — endpoint doesn't support this request
          enumLogLabel := B(0x58, 8 bits)  // 'X' — STALL/error
          enumLogVal   := cmdRd(0)
          enumLogTrigger := True
          stallCount := stallCount + 1
          when(stallCount >= 3 && kbdEndpoint === 1) {
            // EP1 stalled — try EP2 with interface 1
            kbdEndpoint := 2
            kbdIface := 1
            stallCount := 0
            dataToggle := False
            state := 40  // SET_PROTOCOL on interface 1, then poll EP2
          } otherwise {
            delayCnt := U(msToClocks(100), 32 bits); state := 30
          }
        } .otherwise {
          // Other error — retry poll after short delay
          enumLogLabel := B(0x58, 8 bits)  // 'X' — error
          enumLogVal   := cmdRd(0)
          enumLogTrigger := True
          stallCount := stallCount + 1
          when(stallCount >= 50 && kbdEndpoint === 1) {
            // Too many errors on EP1 — try EP2
            kbdEndpoint := 2
            kbdIface := 1
            stallCount := 0
            dataToggle := False
            state := 40
          } otherwise {
            delayCnt := U(msToClocks(10), 32 bits); state := 30
          }
        }
      }

      // ---- Parse HID report ----
      is(35) { // Start parsing — extract modifiers, build into shadow regs
        // cmdRd(0)=length, cmdRd(1)=modifiers, cmdRd(2)=reserved, cmdRd(3..8)=keys
        nextShift := cmdRd(1)(1) | cmdRd(1)(5) // L-Shift | R-Shift
        nextCtrl  := cmdRd(1)(0) | cmdRd(1)(4) // L-Ctrl | R-Ctrl
        nextKeys  := B(0, 64 bits)
        nextStart  := False
        nextSelect := False
        nextOption := False
        nextBreak  := False
        parseIdx := 0
        state := 36
      }
      is(36) { // Process one key slot per cycle (6 slots: cmdRd(3)..cmdRd(8))
        val keyCode = cmdRd(parseIdx.resize(4) + 3).asUInt
        when(keyCode =/= 0) {
          // Check for console/special keys
          when(keyCode === 0x3E) { nextStart  := True }  // F5 -> Start
          when(keyCode === 0x3F) { nextSelect := True }  // F6 -> Select
          when(keyCode === 0x40) { nextOption := True }  // F7 -> Option
          when(keyCode === 0x48) { nextBreak  := True }  // Pause -> Break

          // Look up Atari matrix position
          when(keyCode < 128) {
            val mapped = hidMap(keyCode.resize(7))
            when(mapped(6)) { // valid mapping
              nextKeys(mapped(5 downto 0).asUInt) := True
            }
          }
        }

        when(parseIdx === 5) {
          // Done — commit shadow regs directly. USB 10ms poll interval
          // already exceeds POKEY's 8ms debounce requirement.
          atariKeys    := nextKeys
          shiftPressed := nextShift
          ctrlPressed  := nextCtrl
          startKey     := nextStart
          selectKey    := nextSelect
          optionKey    := nextOption
          breakKey     := nextBreak
          delayCnt := U(msToClocks(2), 32 bits)  // short poll delay
          state := 30
        } otherwise {
          parseIdx := parseIdx + 1
        }
      }

      default {
        state := 0
      }
    }
  }

  // =========================================================================
  // Debug UART TX — logs USB poll results at 115200 baud
  // Format per successful HID read:
  //   "S MM K0 K1 K2 K3 K4 K5\r\n"  (hex, ~22 chars)
  // NAK:  ".\r\n" (suppressed — too many)
  // Error: "E XX\r\n"
  // =========================================================================
  val uartDiv     = (sysClkHz / 115200) - 1   // baud divider
  val uartCnt     = Reg(UInt(16 bits))  init 0
  val uartSr      = Reg(Bits(10 bits))  init B"1111111111" // idle
  val uartBit     = Reg(UInt(4 bits))   init 0
  val uartBusy    = Reg(Bool())         init False

  io.uartTx := uartSr(0)

  // Shift register UART TX
  when(uartBusy) {
    when(uartCnt === uartDiv) {
      uartCnt := 0
      uartSr  := True ## uartSr(9 downto 1)  // shift right, fill with stop bit
      when(uartBit === 9) {
        uartBusy := False
      } otherwise {
        uartBit := uartBit + 1
      }
    } otherwise {
      uartCnt := uartCnt + 1
    }
  }

  // Start sending one byte
  val uartSend  = Bool()
  val uartData  = Bits(8 bits)
  uartSend := False
  uartData := 0
  when(uartSend && !uartBusy) {
    uartSr   := B"1" ## uartData ## B"0"  // stop + data + start
    uartBit  := 0
    uartCnt  := 0
    uartBusy := True
  }

  // Debug message FIFO (32 bytes circular)
  val dbgFifo  = Mem(Bits(8 bits), 32)
  val dbgWr    = Reg(UInt(5 bits)) init 0
  val dbgRd    = Reg(UInt(5 bits)) init 0
  val dbgEmpty = dbgWr === dbgRd
  val dbgFull  = (dbgWr + 1)(4 downto 0) === dbgRd

  // Push byte into debug FIFO
  val dbgPush     = Bool()
  val dbgPushData = Bits(8 bits)
  dbgPush     := False
  dbgPushData := 0
  when(dbgPush && !dbgFull) {
    dbgFifo(dbgWr) := dbgPushData
    dbgWr := dbgWr + 1
  }

  // Drain FIFO into UART
  when(!uartBusy && !dbgEmpty) {
    uartSend := True
    uartData := dbgFifo(dbgRd)
    dbgRd    := dbgRd + 1
  }

  // Hex nibble helper — widen to 8 bits BEFORE adding to avoid 4-bit wrap
  def hexNibble(v: UInt): Bits = {
    val nibble = v(3 downto 0).resize(8)
    Mux(nibble < 10, (nibble + 0x30).asBits, (nibble + 0x37).asBits)
  }

  // ---- Key press/miss tracking ----
  // Detect key-down edge: atariKeys transitions from no keys to some key(s)
  val prevHasKey  = Reg(Bool()) init False
  val currHasKey  = atariKeys =/= 0
  val keyDownEdge = currHasKey && !prevHasKey
  val keyUpEdge   = !currHasKey && prevHasKey
  prevHasKey := currHasKey

  // Track whether POKEY registered the current keypress
  val waitingForIrq = Reg(Bool()) init False
  when(keyDownEdge) { waitingForIrq := True }
  when(io.pokeyKeyIrq && waitingForIrq) { waitingForIrq := False }

  // Counters (16-bit, wraps at 65535)
  val cntPress = Reg(UInt(16 bits)) init 0  // key-down edges from USB
  val cntHit   = Reg(UInt(16 bits)) init 0  // POKEY IRQ fires (key registered)
  val cntMiss  = Reg(UInt(16 bits)) init 0  // key released without POKEY IRQ
  val cntAck   = Reg(UInt(16 bits)) init 0  // CPU acknowledged keyboard IRQ

  when(keyDownEdge) { cntPress := cntPress + 1 }
  when(io.pokeyKeyIrq) { cntHit := cntHit + 1 }
  when(io.kbIrqAck) { cntAck := cntAck + 1 }
  when(keyUpEdge && waitingForIrq) {
    cntMiss := cntMiss + 1
    waitingForIrq := False
  }

  // Snapshot counters for reporting (freeze values while message is being sent)
  val snapPress      = Reg(UInt(16 bits)) init 0
  val snapHit        = Reg(UInt(16 bits)) init 0
  val snapAck        = Reg(UInt(16 bits)) init 0
  val snapMiss       = Reg(UInt(16 bits)) init 0
  val snapState      = Reg(UInt(16 bits)) init 0

  // ---- Periodic report every ~2 seconds ----
  val reportTimer = Reg(UInt(28 bits)) init 0
  val reportTrigger = Reg(Bool()) init False
  reportTimer := reportTimer + 1
  when(reportTimer === U(msToClocks(2000), 28 bits)) {
    reportTimer    := 0
    reportTrigger  := True
    snapPress      := cntPress
    snapHit        := cntHit
    snapAck        := cntAck
    snapMiss       := cntMiss
    snapState      := state.resize(16)
  }

  // Also log immediately on each miss
  val missLogTrigger = Reg(Bool()) init False
  when(keyUpEdge && waitingForIrq) { missLogTrigger := True }

  // ---- Message builder FSM ----
  // Periodic: "P:XXXX H:XXXX A:XXXX M:XXXX F:XXXX\r\n"  (5 counters)
  // Enum event: "X:YY\r\n"  (label + hex byte)
  // Miss:     "MISS\r\n"
  val dbgMsgState = Reg(UInt(5 bits)) init 0
  val dbgMsgVal   = Reg(UInt(16 bits)) init 0  // value being printed
  val dbgMsgPhase = Reg(UInt(3 bits)) init 0   // which counter (0=P, 1=H, 2=A, 3=M, 4=F)

  // Priority: enum event > miss log > periodic report
  when(dbgMsgState === 0) {
    when(enumLogTrigger) {
      enumLogTrigger := False
      dbgMsgState := 26  // enum event message
    } .elsewhen(missLogTrigger) {
      missLogTrigger := False
      dbgMsgState := 20  // miss message
    } .elsewhen(reportTrigger) {
      reportTrigger := False
      dbgMsgPhase := 0
      dbgMsgState := 1   // periodic report
    }
  }

  when(dbgMsgState =/= 0 && !dbgFull) {
    switch(dbgMsgState) {
      // ---- Periodic report: "P:XXXX H:XXXX A:XXXX M:XXXX F:XXXX\r\n" ----
      is(1)  { // Print label and load value
        when(dbgMsgPhase === 0) { dbgPush := True; dbgPushData := B(0x50, 8 bits); dbgMsgVal := snapPress }  // 'P'
        .elsewhen(dbgMsgPhase === 1) { dbgPush := True; dbgPushData := B(0x48, 8 bits); dbgMsgVal := snapHit }  // 'H'
        .elsewhen(dbgMsgPhase === 2) { dbgPush := True; dbgPushData := B(0x41, 8 bits); dbgMsgVal := snapAck }  // 'A'
        .elsewhen(dbgMsgPhase === 3) { dbgPush := True; dbgPushData := B(0x4D, 8 bits); dbgMsgVal := snapMiss }  // 'M'
        .otherwise { dbgPush := True; dbgPushData := B(0x46, 8 bits); dbgMsgVal := snapState }                   // 'F'
        dbgMsgState := 2
      }
      is(2)  { dbgPush := True; dbgPushData := B(0x3A, 8 bits); dbgMsgState := 3 }  // ':'
      is(3)  { dbgPush := True; dbgPushData := hexNibble(dbgMsgVal |>> 12); dbgMsgState := 4 }
      is(4)  { dbgPush := True; dbgPushData := hexNibble(dbgMsgVal |>> 8);  dbgMsgState := 5 }
      is(5)  { dbgPush := True; dbgPushData := hexNibble(dbgMsgVal |>> 4);  dbgMsgState := 6 }
      is(6)  { dbgPush := True; dbgPushData := hexNibble(dbgMsgVal);        dbgMsgState := 7 }
      is(7)  {
        when(dbgMsgPhase === 4) {
          dbgMsgState := 8  // done, CRLF
        } otherwise {
          dbgPush := True; dbgPushData := B(0x20, 8 bits)  // ' '
          dbgMsgPhase := dbgMsgPhase + 1
          dbgMsgState := 1  // next counter
        }
      }
      is(8)  { dbgPush := True; dbgPushData := B(0x0D, 8 bits); dbgMsgState := 9 }   // CR
      is(9)  { dbgPush := True; dbgPushData := B(0x0A, 8 bits); dbgMsgState := 0 }    // LF

      // ---- Miss event: "MISS\r\n" ----
      is(20) { dbgPush := True; dbgPushData := B(0x4D, 8 bits); dbgMsgState := 21 }  // 'M'
      is(21) { dbgPush := True; dbgPushData := B(0x49, 8 bits); dbgMsgState := 22 }  // 'I'
      is(22) { dbgPush := True; dbgPushData := B(0x53, 8 bits); dbgMsgState := 23 }  // 'S'
      is(23) { dbgPush := True; dbgPushData := B(0x53, 8 bits); dbgMsgState := 24 }  // 'S'
      is(24) { dbgPush := True; dbgPushData := B(0x0D, 8 bits); dbgMsgState := 25 }
      is(25) { dbgPush := True; dbgPushData := B(0x0A, 8 bits); dbgMsgState := 0 }

      // ---- Enum event: "X:YY\r\n" ----
      is(26) { dbgPush := True; dbgPushData := enumLogLabel; dbgMsgState := 27 }
      is(27) { dbgPush := True; dbgPushData := B(0x3A, 8 bits); dbgMsgState := 28 }  // ':'
      is(28) { dbgPush := True; dbgPushData := hexNibble(enumLogVal.asUInt |>> 4); dbgMsgState := 29 }
      is(29) { dbgPush := True; dbgPushData := hexNibble(enumLogVal.asUInt); dbgMsgState := 30 }
      is(30) { dbgPush := True; dbgPushData := B(0x0D, 8 bits); dbgMsgState := 31 }
      is(31) { dbgPush := True; dbgPushData := B(0x0A, 8 bits); dbgMsgState := 0 }

      default { dbgMsgState := 0 }
    }
  }
}

// Standalone test top: Ch376UsbKeyboard + debug LEDs + heartbeat
// For ch376_test.qsf — verifies keyboard bridge independently
class Ch376KeyboardTestTop extends Component {
  val io = new Bundle {
    val clk_in    = in  Bool()
    val uart_tx   = out Bool()
    val uart_rx   = in  Bool()
    val ch376_sck  = out Bool()
    val ch376_mosi = out Bool()
    val ch376_miso = in  Bool()
    val ch376_cs   = out Bool()
    val ch376_int  = in  Bool()
    val ch376_rst  = out Bool()
    val ch376_spi_n = out Bool()
    val led        = out Bits(2 bits)
  }

  val clkDomain = ClockDomain(
    clock = io.clk_in,
    config = ClockDomainConfig(resetKind = BOOT)
  )

  val area = new ClockingArea(clkDomain) {
    val kbd = new Ch376UsbKeyboard(sysClkHz = 50000000)

    kbd.io.spiMiso := io.ch376_miso
    kbd.io.intN    := io.ch376_int
    io.ch376_sck   := kbd.io.spiSck
    io.ch376_mosi  := kbd.io.spiMosi
    io.ch376_cs    := kbd.io.spiCsN
    io.ch376_rst   := kbd.io.rstOut
    io.ch376_spi_n := kbd.io.spiModeN

    // Keyboard scan not used in standalone test — tie off
    kbd.io.keyboardScan := B"000000"
    kbd.io.pokeyKeyIrq  := False
    kbd.io.pokeyKeyHeld := False
    kbd.io.kbIrqEnabled := False
    kbd.io.kbIrqPending := False
    kbd.io.kbIrqAck     := False

    // LED[0] = heartbeat, LED[1] = connected
    val hbCnt = Reg(UInt(25 bits)) init 0
    hbCnt := hbCnt + 1
    io.led(0) := hbCnt(24)
    io.led(1) := kbd.io.connected

    // UART TX — send state + any-key indicator as simple debug
    // For now just tie off; use SignalTap or LEDs
    io.uart_tx := True // idle high
  }
}

object Ch376KeyboardTestSv extends App {
  SpinalConfig(
    mode = SystemVerilog,
    targetDirectory = "boards/atari800-lg-v1/qmtech-ep4cgx150/ch376_test"
  ).generate(new Ch376KeyboardTestTop)
}
