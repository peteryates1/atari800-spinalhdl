// Generator : SpinalHDL v1.12.2    git head : f25edbcee624ef41548345cfb91c42060e33313f
// Component : Ch376KeyboardTestTop
// Git hash  : 7ccfa2c5aac75f04c865d527368db64ddbde9c25

`timescale 1ns/1ps

module Ch376KeyboardTestTop (
  input  wire          io_clk_in,
  output wire          io_uart_tx,
  input  wire          io_uart_rx,
  output wire          io_ch376_sck,
  output wire          io_ch376_mosi,
  input  wire          io_ch376_miso,
  output wire          io_ch376_cs,
  input  wire          io_ch376_int,
  output wire          io_ch376_rst,
  output wire          io_ch376_spi_n,
  output reg  [1:0]    io_led
);

  wire                area_kbd_io_spiSck;
  wire                area_kbd_io_spiMosi;
  wire                area_kbd_io_spiCsN;
  wire                area_kbd_io_rstOut;
  wire                area_kbd_io_spiModeN;
  wire       [1:0]    area_kbd_io_keyboardResponse;
  wire                area_kbd_io_consolStart;
  wire                area_kbd_io_consolSelect;
  wire                area_kbd_io_consolOption;
  wire                area_kbd_io_connected;
  wire       [5:0]    area_kbd_io_dbgState;
  reg        [24:0]   area_hbCnt;

  Ch376UsbKeyboard area_kbd (
    .io_spiSck           (area_kbd_io_spiSck               ), //o
    .io_spiMosi          (area_kbd_io_spiMosi              ), //o
    .io_spiMiso          (io_ch376_miso                    ), //i
    .io_spiCsN           (area_kbd_io_spiCsN               ), //o
    .io_intN             (io_ch376_int                     ), //i
    .io_rstOut           (area_kbd_io_rstOut               ), //o
    .io_spiModeN         (area_kbd_io_spiModeN             ), //o
    .io_keyboardScan     (6'h0                             ), //i
    .io_keyboardResponse (area_kbd_io_keyboardResponse[1:0]), //o
    .io_consolStart      (area_kbd_io_consolStart          ), //o
    .io_consolSelect     (area_kbd_io_consolSelect         ), //o
    .io_consolOption     (area_kbd_io_consolOption         ), //o
    .io_connected        (area_kbd_io_connected            ), //o
    .io_dbgState         (area_kbd_io_dbgState[5:0]        ), //o
    .io_clk_in           (io_clk_in                        )  //i
  );
  initial begin
    area_hbCnt = 25'h0;
  end

  assign io_ch376_sck = area_kbd_io_spiSck;
  assign io_ch376_mosi = area_kbd_io_spiMosi;
  assign io_ch376_cs = area_kbd_io_spiCsN;
  assign io_ch376_rst = area_kbd_io_rstOut;
  assign io_ch376_spi_n = area_kbd_io_spiModeN;
  always @(*) begin
    io_led[0] = area_hbCnt[24];
    io_led[1] = area_kbd_io_connected;
  end

  assign io_uart_tx = 1'b1;
  always @(posedge io_clk_in) begin
    area_hbCnt <= (area_hbCnt + 25'h0000001);
  end


endmodule

module Ch376UsbKeyboard (
  output wire          io_spiSck,
  output wire          io_spiMosi,
  input  wire          io_spiMiso,
  output wire          io_spiCsN,
  input  wire          io_intN,
  output wire          io_rstOut,
  output wire          io_spiModeN,
  input  wire [5:0]    io_keyboardScan,
  output reg  [1:0]    io_keyboardResponse,
  output wire          io_consolStart,
  output wire          io_consolSelect,
  output wire          io_consolOption,
  output wire          io_connected,
  output wire [5:0]    io_dbgState,
  input  wire          io_clk_in
);

  wire       [6:0]    hidMap_spinal_port0;
  wire       [3:0]    _zz_when_Ch376UsbKeyboard_l176;
  reg        [7:0]    _zz__zz_when_Ch376UsbKeyboard_l521;
  wire       [3:0]    _zz__zz_when_Ch376UsbKeyboard_l521_1;
  wire       [3:0]    _zz__zz_when_Ch376UsbKeyboard_l521_2;
  reg                 misoSync;
  reg                 misoS2;
  reg                 intSync;
  reg                 intS2;
  reg        [15:0]   spiDiv;
  reg        [15:0]   spiCnt;
  reg        [3:0]    spiBit;
  reg        [7:0]    spiTxSr;
  reg        [7:0]    spiRxSr;
  reg        [7:0]    spiRxData;
  reg                 spiClk;
  reg                 spiBusy;
  reg                 spiDone;
  reg                 spiGo;
  reg        [7:0]    spiTxIn;
  wire                when_Ch376UsbKeyboard_l78;
  wire                when_Ch376UsbKeyboard_l87;
  wire                when_Ch376UsbKeyboard_l90;
  wire                when_Ch376UsbKeyboard_l95;
  reg                 csReg;
  reg        [27:0]   delayCnt;
  wire                delayDone;
  wire                when_Ch376UsbKeyboard_l117;
  reg        [22:0]   bootCnt;
  wire                bootDone;
  wire                when_Ch376UsbKeyboard_l126;
  reg        [7:0]    cmdCode;
  reg        [7:0]    cmdWr0;
  wire       [7:0]    cmdWr1;
  reg        [1:0]    cmdWrLen;
  reg        [3:0]    cmdRdLen;
  reg        [7:0]    cmdRd_0;
  reg        [7:0]    cmdRd_1;
  reg        [7:0]    cmdRd_2;
  reg        [7:0]    cmdRd_3;
  reg        [7:0]    cmdRd_4;
  reg        [7:0]    cmdRd_5;
  reg        [7:0]    cmdRd_6;
  reg        [7:0]    cmdRd_7;
  reg        [7:0]    cmdRd_8;
  reg        [7:0]    cmdRd_9;
  reg        [3:0]    cmdRdIdx;
  reg        [3:0]    cmdStep;
  reg                 cmdBusy;
  reg                 cmdGo;
  wire                when_Ch376UsbKeyboard_l144;
  wire                when_Ch376UsbKeyboard_l155;
  wire                when_Ch376UsbKeyboard_l156;
  wire                when_Ch376UsbKeyboard_l162;
  wire                when_Ch376UsbKeyboard_l163;
  wire                when_Ch376UsbKeyboard_l169;
  wire       [15:0]   _zz_1;
  wire                when_Ch376UsbKeyboard_l176;
  reg        [63:0]   atariKeys;
  reg                 shiftPressed;
  reg                 ctrlPressed;
  reg                 breakKey;
  reg                 connectedReg;
  reg                 startKey;
  reg                 selectKey;
  reg                 optionKey;
  wire       [5:0]    scanIdx;
  wire                when_Ch376UsbKeyboard_l205;
  wire                when_Ch376UsbKeyboard_l208;
  wire                when_Ch376UsbKeyboard_l211;
  wire                when_Ch376UsbKeyboard_l214;
  reg        [5:0]    state;
  reg                 waitInt;
  reg        [27:0]   intTimeout;
  reg                 dataToggle;
  reg        [3:0]    retryCount;
  reg        [2:0]    parseIdx;
  wire                when_Ch376UsbKeyboard_l279;
  wire                when_Ch376UsbKeyboard_l282;
  wire                fsmReady;
  wire                when_Ch376UsbKeyboard_l309;
  wire                when_Ch376UsbKeyboard_l315;
  wire                when_Ch376UsbKeyboard_l329;
  wire                when_Ch376UsbKeyboard_l351;
  wire                when_Ch376UsbKeyboard_l378;
  wire                when_Ch376UsbKeyboard_l402;
  wire                when_Ch376UsbKeyboard_l427;
  wire                when_Ch376UsbKeyboard_l432;
  wire                when_Ch376UsbKeyboard_l455;
  wire                when_Ch376UsbKeyboard_l486;
  wire                when_Ch376UsbKeyboard_l492;
  wire                when_Ch376UsbKeyboard_l495;
  wire       [7:0]    _zz_when_Ch376UsbKeyboard_l521;
  wire                when_Ch376UsbKeyboard_l521;
  wire                when_Ch376UsbKeyboard_l523;
  wire                when_Ch376UsbKeyboard_l524;
  wire                when_Ch376UsbKeyboard_l525;
  wire                when_Ch376UsbKeyboard_l526;
  wire                when_Ch376UsbKeyboard_l529;
  wire       [6:0]    _zz_atariKeys;
  wire       [6:0]    _zz_atariKeys_1;
  wire                when_Ch376UsbKeyboard_l531;
  wire                when_Ch376UsbKeyboard_l537;
  reg [6:0] hidMap [0:127];

  assign _zz_when_Ch376UsbKeyboard_l176 = (cmdRdLen - 4'b0001);
  assign _zz__zz_when_Ch376UsbKeyboard_l521_1 = (_zz__zz_when_Ch376UsbKeyboard_l521_2 + 4'b0011);
  assign _zz__zz_when_Ch376UsbKeyboard_l521_2 = {1'd0, parseIdx};
  initial begin
    $readmemb("Ch376KeyboardTestTop.sv_toplevel_area_kbd_hidMap.bin",hidMap);
  end
  assign hidMap_spinal_port0 = hidMap[_zz_atariKeys];
  initial begin
    misoSync = 1'b1;
    misoS2 = 1'b1;
    intSync = 1'b1;
    intS2 = 1'b1;
    spiDiv = 16'h01f3;
    spiCnt = 16'h0;
    spiBit = 4'b0000;
    spiTxSr = 8'hff;
    spiRxSr = 8'h0;
    spiRxData = 8'h0;
    spiClk = 1'b0;
    spiBusy = 1'b0;
    spiDone = 1'b0;
    csReg = 1'b1;
    delayCnt = 28'h0;
    bootCnt = 23'h0;
    cmdCode = 8'h0;
    cmdWr0 = 8'h0;
    cmdWrLen = 2'b00;
    cmdRdLen = 4'b0000;
    cmdRd_0 = 8'h0;
    cmdRd_1 = 8'h0;
    cmdRd_2 = 8'h0;
    cmdRd_3 = 8'h0;
    cmdRd_4 = 8'h0;
    cmdRd_5 = 8'h0;
    cmdRd_6 = 8'h0;
    cmdRd_7 = 8'h0;
    cmdRd_8 = 8'h0;
    cmdRd_9 = 8'h0;
    cmdRdIdx = 4'b0000;
    cmdStep = 4'b0000;
    cmdBusy = 1'b0;
    atariKeys = 64'h0;
    shiftPressed = 1'b0;
    ctrlPressed = 1'b0;
    breakKey = 1'b0;
    connectedReg = 1'b0;
    startKey = 1'b0;
    selectKey = 1'b0;
    optionKey = 1'b0;
    state = 6'h0;
    waitInt = 1'b0;
    intTimeout = 28'h0;
    dataToggle = 1'b0;
    retryCount = 4'b0000;
    parseIdx = 3'b000;
  end

  always @(*) begin
    case(_zz__zz_when_Ch376UsbKeyboard_l521_1)
      4'b0000 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_0;
      4'b0001 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_1;
      4'b0010 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_2;
      4'b0011 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_3;
      4'b0100 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_4;
      4'b0101 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_5;
      4'b0110 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_6;
      4'b0111 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_7;
      4'b1000 : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_8;
      default : _zz__zz_when_Ch376UsbKeyboard_l521 = cmdRd_9;
    endcase
  end

  assign io_spiModeN = 1'b0;
  assign io_spiSck = spiClk;
  assign io_spiMosi = spiTxSr[7];
  always @(*) begin
    spiGo = 1'b0;
    if(cmdBusy) begin
      case(cmdStep)
        4'b0000 : begin
        end
        4'b0001 : begin
          spiGo = 1'b1;
        end
        4'b0010 : begin
        end
        4'b0011 : begin
        end
        4'b0100 : begin
          spiGo = 1'b1;
        end
        4'b0101 : begin
        end
        4'b0110 : begin
          spiGo = 1'b1;
        end
        4'b1010 : begin
        end
        4'b0111 : begin
          spiGo = 1'b1;
        end
        4'b1000 : begin
        end
        default : begin
        end
      endcase
    end
  end

  always @(*) begin
    spiTxIn = 8'h0;
    if(cmdBusy) begin
      case(cmdStep)
        4'b0000 : begin
        end
        4'b0001 : begin
          spiTxIn = cmdCode;
        end
        4'b0010 : begin
        end
        4'b0011 : begin
        end
        4'b0100 : begin
          spiTxIn = cmdWr0;
        end
        4'b0101 : begin
        end
        4'b0110 : begin
          spiTxIn = cmdWr1;
        end
        4'b1010 : begin
        end
        4'b0111 : begin
          spiTxIn = 8'hff;
        end
        4'b1000 : begin
        end
        default : begin
        end
      endcase
    end
  end

  assign when_Ch376UsbKeyboard_l78 = (! spiBusy);
  assign when_Ch376UsbKeyboard_l87 = (spiCnt == spiDiv);
  assign when_Ch376UsbKeyboard_l90 = (! spiClk);
  assign when_Ch376UsbKeyboard_l95 = (spiBit == 4'b0111);
  assign io_spiCsN = csReg;
  assign delayDone = (delayCnt == 28'h0);
  assign when_Ch376UsbKeyboard_l117 = (! delayDone);
  assign bootDone = bootCnt[22];
  assign when_Ch376UsbKeyboard_l126 = (! bootDone);
  assign io_rstOut = (! bootDone);
  assign cmdWr1 = 8'h0;
  always @(*) begin
    cmdGo = 1'b0;
    if(fsmReady) begin
      case(state)
        6'h0 : begin
        end
        6'h01 : begin
          cmdGo = 1'b1;
        end
        6'h02 : begin
          if(when_Ch376UsbKeyboard_l309) begin
            cmdGo = 1'b1;
          end
        end
        6'h03 : begin
        end
        6'h04 : begin
          cmdGo = 1'b1;
        end
        6'h05 : begin
        end
        6'h06 : begin
          cmdGo = 1'b1;
        end
        6'h07 : begin
        end
        6'h08 : begin
          cmdGo = 1'b1;
        end
        6'h09 : begin
        end
        6'h0a : begin
          cmdGo = 1'b1;
        end
        6'h0b : begin
        end
        6'h0c : begin
          cmdGo = 1'b1;
        end
        6'h0d : begin
        end
        6'h0e : begin
          if(when_Ch376UsbKeyboard_l378) begin
            cmdGo = 1'b1;
          end
        end
        6'h0f : begin
        end
        6'h10 : begin
          cmdGo = 1'b1;
        end
        6'h11 : begin
          cmdGo = 1'b1;
        end
        6'h12 : begin
        end
        6'h13 : begin
          if(when_Ch376UsbKeyboard_l402) begin
            cmdGo = 1'b1;
          end
        end
        6'h14 : begin
        end
        6'h15 : begin
          cmdGo = 1'b1;
        end
        6'h16 : begin
        end
        6'h17 : begin
          cmdGo = 1'b1;
        end
        6'h18 : begin
        end
        6'h19 : begin
          cmdGo = 1'b1;
        end
        6'h1a : begin
          cmdGo = 1'b1;
        end
        6'h1b : begin
        end
        6'h1c : begin
          cmdGo = 1'b1;
        end
        6'h1d : begin
        end
        6'h1e : begin
          cmdGo = 1'b1;
        end
        6'h1f : begin
          cmdGo = 1'b1;
        end
        6'h20 : begin
        end
        6'h21 : begin
          cmdGo = 1'b1;
        end
        6'h22 : begin
          if(when_Ch376UsbKeyboard_l486) begin
            cmdGo = 1'b1;
          end
        end
        6'h23 : begin
        end
        6'h24 : begin
        end
        default : begin
        end
      endcase
    end
  end

  assign when_Ch376UsbKeyboard_l144 = (cmdGo && (! cmdBusy));
  assign when_Ch376UsbKeyboard_l155 = (cmdWrLen != 2'b00);
  assign when_Ch376UsbKeyboard_l156 = (cmdRdLen != 4'b0000);
  assign when_Ch376UsbKeyboard_l162 = (cmdWrLen == 2'b10);
  assign when_Ch376UsbKeyboard_l163 = (cmdRdLen != 4'b0000);
  assign when_Ch376UsbKeyboard_l169 = (cmdRdLen != 4'b0000);
  assign _zz_1 = ({15'd0,1'b1} <<< cmdRdIdx);
  assign when_Ch376UsbKeyboard_l176 = (cmdRdIdx == _zz_when_Ch376UsbKeyboard_l176);
  assign io_connected = connectedReg;
  assign io_consolStart = startKey;
  assign io_consolSelect = selectKey;
  assign io_consolOption = optionKey;
  always @(*) begin
    io_keyboardResponse = 2'b11;
    if(when_Ch376UsbKeyboard_l205) begin
      io_keyboardResponse[0] = 1'b0;
    end
    if(when_Ch376UsbKeyboard_l208) begin
      io_keyboardResponse[1] = 1'b0;
    end
    if(when_Ch376UsbKeyboard_l211) begin
      io_keyboardResponse[1] = 1'b0;
    end
    if(when_Ch376UsbKeyboard_l214) begin
      io_keyboardResponse[1] = 1'b0;
    end
  end

  assign scanIdx = (~ io_keyboardScan);
  assign when_Ch376UsbKeyboard_l205 = atariKeys[scanIdx];
  assign when_Ch376UsbKeyboard_l208 = ((io_keyboardScan[5 : 4] == 2'b00) && breakKey);
  assign when_Ch376UsbKeyboard_l211 = ((io_keyboardScan[5 : 4] == 2'b10) && shiftPressed);
  assign when_Ch376UsbKeyboard_l214 = ((io_keyboardScan[5 : 4] == 2'b11) && ctrlPressed);
  assign io_dbgState = state;
  assign when_Ch376UsbKeyboard_l279 = (! intSync);
  assign when_Ch376UsbKeyboard_l282 = (intTimeout != 28'h0);
  assign fsmReady = ((((! cmdBusy) && (! waitInt)) && delayDone) && (! spiDone));
  assign when_Ch376UsbKeyboard_l309 = (cmdRd_0 == 8'h5a);
  assign when_Ch376UsbKeyboard_l315 = (4'b1010 <= retryCount);
  assign when_Ch376UsbKeyboard_l329 = (cmdRd_0 == 8'h5a);
  assign when_Ch376UsbKeyboard_l351 = (cmdRd_0 == 8'h15);
  assign when_Ch376UsbKeyboard_l378 = (! intSync);
  assign when_Ch376UsbKeyboard_l402 = (! intSync);
  assign when_Ch376UsbKeyboard_l427 = (cmdRd_0 == 8'h14);
  assign when_Ch376UsbKeyboard_l432 = (4'b0101 <= retryCount);
  assign when_Ch376UsbKeyboard_l455 = (cmdRd_0 == 8'h14);
  assign when_Ch376UsbKeyboard_l486 = (cmdRd_0 == 8'h14);
  assign when_Ch376UsbKeyboard_l492 = (cmdRd_0 == 8'h2a);
  assign when_Ch376UsbKeyboard_l495 = ((cmdRd_0 == 8'h15) || (cmdRd_0 == 8'h16));
  assign _zz_when_Ch376UsbKeyboard_l521 = _zz__zz_when_Ch376UsbKeyboard_l521;
  assign when_Ch376UsbKeyboard_l521 = (_zz_when_Ch376UsbKeyboard_l521 != 8'h0);
  assign when_Ch376UsbKeyboard_l523 = (_zz_when_Ch376UsbKeyboard_l521 == 8'h3e);
  assign when_Ch376UsbKeyboard_l524 = (_zz_when_Ch376UsbKeyboard_l521 == 8'h3f);
  assign when_Ch376UsbKeyboard_l525 = (_zz_when_Ch376UsbKeyboard_l521 == 8'h40);
  assign when_Ch376UsbKeyboard_l526 = (_zz_when_Ch376UsbKeyboard_l521 == 8'h48);
  assign when_Ch376UsbKeyboard_l529 = (_zz_when_Ch376UsbKeyboard_l521 < 8'h80);
  assign _zz_atariKeys = _zz_when_Ch376UsbKeyboard_l521[6:0];
  assign _zz_atariKeys_1 = hidMap_spinal_port0;
  assign when_Ch376UsbKeyboard_l531 = _zz_atariKeys_1[6];
  assign when_Ch376UsbKeyboard_l537 = (parseIdx == 3'b101);
  always @(posedge io_clk_in) begin
    misoS2 <= io_spiMiso;
    misoSync <= misoS2;
    intS2 <= io_intN;
    intSync <= intS2;
    spiDone <= 1'b0;
    if(when_Ch376UsbKeyboard_l78) begin
      spiClk <= 1'b0;
      if(spiGo) begin
        spiTxSr <= spiTxIn;
        spiBusy <= 1'b1;
        spiBit <= 4'b0000;
        spiCnt <= 16'h0;
      end
    end else begin
      if(when_Ch376UsbKeyboard_l87) begin
        spiCnt <= 16'h0;
        spiClk <= (! spiClk);
        if(when_Ch376UsbKeyboard_l90) begin
          spiRxSr <= {spiRxSr[6 : 0],misoSync};
        end else begin
          if(when_Ch376UsbKeyboard_l95) begin
            spiBusy <= 1'b0;
            spiClk <= 1'b0;
            spiRxData <= spiRxSr;
            spiDone <= 1'b1;
          end else begin
            spiTxSr <= {spiTxSr[6 : 0],1'b1};
            spiBit <= (spiBit + 4'b0001);
          end
        end
      end else begin
        spiCnt <= (spiCnt + 16'h0001);
      end
    end
    if(when_Ch376UsbKeyboard_l117) begin
      delayCnt <= (delayCnt - 28'h0000001);
    end
    if(when_Ch376UsbKeyboard_l126) begin
      bootCnt <= (bootCnt + 23'h000001);
    end
    if(when_Ch376UsbKeyboard_l144) begin
      cmdBusy <= 1'b1;
      cmdStep <= 4'b0000;
    end
    if(cmdBusy) begin
      case(cmdStep)
        4'b0000 : begin
          csReg <= 1'b0;
          cmdStep <= 4'b0001;
        end
        4'b0001 : begin
          cmdStep <= 4'b0010;
        end
        4'b0010 : begin
          if(spiDone) begin
            delayCnt <= 28'h0000096;
            cmdStep <= 4'b0011;
          end
        end
        4'b0011 : begin
          if(delayDone) begin
            if(when_Ch376UsbKeyboard_l155) begin
              cmdStep <= 4'b0100;
            end else begin
              if(when_Ch376UsbKeyboard_l156) begin
                cmdRdIdx <= 4'b0000;
                cmdStep <= 4'b0111;
              end else begin
                csReg <= 1'b1;
                cmdBusy <= 1'b0;
              end
            end
          end
        end
        4'b0100 : begin
          cmdStep <= 4'b0101;
        end
        4'b0101 : begin
          if(spiDone) begin
            if(when_Ch376UsbKeyboard_l162) begin
              cmdStep <= 4'b0110;
            end else begin
              if(when_Ch376UsbKeyboard_l163) begin
                cmdRdIdx <= 4'b0000;
                cmdStep <= 4'b0111;
              end else begin
                csReg <= 1'b1;
                cmdBusy <= 1'b0;
              end
            end
          end
        end
        4'b0110 : begin
          cmdStep <= 4'b1010;
        end
        4'b1010 : begin
          if(spiDone) begin
            if(when_Ch376UsbKeyboard_l169) begin
              cmdRdIdx <= 4'b0000;
              cmdStep <= 4'b0111;
            end else begin
              csReg <= 1'b1;
              cmdBusy <= 1'b0;
            end
          end
        end
        4'b0111 : begin
          cmdStep <= 4'b1000;
        end
        4'b1000 : begin
          if(spiDone) begin
            if(_zz_1[0]) begin
              cmdRd_0 <= spiRxData;
            end
            if(_zz_1[1]) begin
              cmdRd_1 <= spiRxData;
            end
            if(_zz_1[2]) begin
              cmdRd_2 <= spiRxData;
            end
            if(_zz_1[3]) begin
              cmdRd_3 <= spiRxData;
            end
            if(_zz_1[4]) begin
              cmdRd_4 <= spiRxData;
            end
            if(_zz_1[5]) begin
              cmdRd_5 <= spiRxData;
            end
            if(_zz_1[6]) begin
              cmdRd_6 <= spiRxData;
            end
            if(_zz_1[7]) begin
              cmdRd_7 <= spiRxData;
            end
            if(_zz_1[8]) begin
              cmdRd_8 <= spiRxData;
            end
            if(_zz_1[9]) begin
              cmdRd_9 <= spiRxData;
            end
            if(when_Ch376UsbKeyboard_l176) begin
              csReg <= 1'b1;
              cmdBusy <= 1'b0;
            end else begin
              cmdRdIdx <= (cmdRdIdx + 4'b0001);
              cmdStep <= 4'b0111;
            end
          end
        end
        default : begin
          cmdBusy <= 1'b0;
        end
      endcase
    end
    if(waitInt) begin
      if(when_Ch376UsbKeyboard_l279) begin
        waitInt <= 1'b0;
      end
      if(when_Ch376UsbKeyboard_l282) begin
        intTimeout <= (intTimeout - 28'h0000001);
      end else begin
        waitInt <= 1'b0;
      end
    end
    if(fsmReady) begin
      case(state)
        6'h0 : begin
          if(bootDone) begin
            delayCnt <= 28'h007a120;
            state <= 6'h01;
          end
        end
        6'h01 : begin
          cmdCode <= 8'h06;
          cmdWr0 <= 8'ha5;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0001;
          state <= 6'h02;
        end
        6'h02 : begin
          if(when_Ch376UsbKeyboard_l309) begin
            cmdCode <= 8'h05;
            cmdWrLen <= 2'b00;
            cmdRdLen <= 4'b0000;
            state <= 6'h03;
          end else begin
            retryCount <= (retryCount + 4'b0001);
            if(when_Ch376UsbKeyboard_l315) begin
              delayCnt <= 28'h2faf080;
              state <= 6'h0;
            end else begin
              delayCnt <= 28'h04c4b40;
              state <= 6'h01;
            end
          end
        end
        6'h03 : begin
          delayCnt <= 28'h04c4b40;
          state <= 6'h04;
        end
        6'h04 : begin
          cmdCode <= 8'h06;
          cmdWr0 <= 8'ha5;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0001;
          state <= 6'h05;
        end
        6'h05 : begin
          if(when_Ch376UsbKeyboard_l329) begin
            spiDiv <= 16'h0031;
            state <= 6'h06;
          end else begin
            delayCnt <= 28'h17d7840;
            state <= 6'h0;
          end
        end
        6'h06 : begin
          cmdCode <= 8'h15;
          cmdWr0 <= 8'h05;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0001;
          state <= 6'h07;
        end
        6'h07 : begin
          waitInt <= 1'b1;
          intTimeout <= 28'hee6b280;
          connectedReg <= 1'b0;
          state <= 6'h08;
        end
        6'h08 : begin
          cmdCode <= 8'h22;
          cmdWrLen <= 2'b00;
          cmdRdLen <= 4'b0001;
          state <= 6'h09;
        end
        6'h09 : begin
          if(when_Ch376UsbKeyboard_l351) begin
            state <= 6'h0a;
          end else begin
            waitInt <= 1'b1;
            intTimeout <= 28'hee6b280;
            state <= 6'h08;
          end
        end
        6'h0a : begin
          cmdCode <= 8'h15;
          cmdWr0 <= 8'h07;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0001;
          state <= 6'h0b;
        end
        6'h0b : begin
          delayCnt <= 28'h00f4240;
          state <= 6'h0c;
        end
        6'h0c : begin
          cmdCode <= 8'h15;
          cmdWr0 <= 8'h06;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0001;
          state <= 6'h0d;
        end
        6'h0d : begin
          delayCnt <= 28'h02625a0;
          state <= 6'h0e;
        end
        6'h0e : begin
          if(when_Ch376UsbKeyboard_l378) begin
            cmdCode <= 8'h22;
            cmdWrLen <= 2'b00;
            cmdRdLen <= 4'b0001;
            state <= 6'h0f;
          end else begin
            state <= 6'h10;
          end
        end
        6'h0f : begin
          delayCnt <= 28'h007a120;
          state <= 6'h0e;
        end
        6'h10 : begin
          cmdCode <= 8'h13;
          cmdWr0 <= 8'h0;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h11;
        end
        6'h11 : begin
          cmdCode <= 8'h04;
          cmdWr0 <= 8'h02;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h12;
        end
        6'h12 : begin
          delayCnt <= 28'h007a120;
          state <= 6'h13;
        end
        6'h13 : begin
          if(when_Ch376UsbKeyboard_l402) begin
            cmdCode <= 8'h22;
            cmdWrLen <= 2'b00;
            cmdRdLen <= 4'b0001;
            state <= 6'h14;
          end else begin
            state <= 6'h15;
          end
        end
        6'h14 : begin
          delayCnt <= 28'h007a120;
          state <= 6'h13;
        end
        6'h15 : begin
          cmdCode <= 8'h45;
          cmdWr0 <= 8'h01;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h16;
        end
        6'h16 : begin
          waitInt <= 1'b1;
          intTimeout <= 28'h5f5e100;
          state <= 6'h17;
        end
        6'h17 : begin
          cmdCode <= 8'h22;
          cmdWrLen <= 2'b00;
          cmdRdLen <= 4'b0001;
          state <= 6'h18;
        end
        6'h18 : begin
          if(when_Ch376UsbKeyboard_l427) begin
            state <= 6'h19;
          end else begin
            retryCount <= (retryCount + 4'b0001);
            if(when_Ch376UsbKeyboard_l432) begin
              delayCnt <= 28'h5f5e100;
              state <= 6'h0;
            end else begin
              delayCnt <= 28'h0989680;
              state <= 6'h0a;
            end
          end
        end
        6'h19 : begin
          cmdCode <= 8'h13;
          cmdWr0 <= 8'h01;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h1a;
        end
        6'h1a : begin
          cmdCode <= 8'h49;
          cmdWr0 <= 8'h01;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h1b;
        end
        6'h1b : begin
          waitInt <= 1'b1;
          intTimeout <= 28'h5f5e100;
          state <= 6'h1c;
        end
        6'h1c : begin
          cmdCode <= 8'h22;
          cmdWrLen <= 2'b00;
          cmdRdLen <= 4'b0001;
          state <= 6'h1d;
        end
        6'h1d : begin
          if(when_Ch376UsbKeyboard_l455) begin
            connectedReg <= 1'b1;
            retryCount <= 4'b0000;
            dataToggle <= 1'b0;
            state <= 6'h1e;
          end else begin
            delayCnt <= 28'h2faf080;
            state <= 6'h0;
          end
        end
        6'h1e : begin
          cmdCode <= 8'h1c;
          cmdWr0 <= (dataToggle ? 8'hc0 : 8'h80);
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h1f;
        end
        6'h1f : begin
          cmdCode <= 8'h4f;
          cmdWr0 <= 8'h19;
          cmdWrLen <= 2'b01;
          cmdRdLen <= 4'b0000;
          state <= 6'h20;
        end
        6'h20 : begin
          waitInt <= 1'b1;
          intTimeout <= 28'h17d7840;
          state <= 6'h21;
        end
        6'h21 : begin
          cmdCode <= 8'h22;
          cmdWrLen <= 2'b00;
          cmdRdLen <= 4'b0001;
          state <= 6'h22;
        end
        6'h22 : begin
          if(when_Ch376UsbKeyboard_l486) begin
            dataToggle <= (! dataToggle);
            cmdCode <= 8'h28;
            cmdWrLen <= 2'b00;
            cmdRdLen <= 4'b1001;
            state <= 6'h23;
          end else begin
            if(when_Ch376UsbKeyboard_l492) begin
              delayCnt <= 28'h007a120;
              state <= 6'h1e;
            end else begin
              if(when_Ch376UsbKeyboard_l495) begin
                connectedReg <= 1'b0;
                delayCnt <= 28'h17d7840;
                state <= 6'h0;
              end else begin
                delayCnt <= 28'h02625a0;
                state <= 6'h1e;
              end
            end
          end
        end
        6'h23 : begin
          shiftPressed <= (cmdRd_1[1] || cmdRd_1[5]);
          ctrlPressed <= (cmdRd_1[0] || cmdRd_1[4]);
          atariKeys <= 64'h0;
          startKey <= 1'b0;
          selectKey <= 1'b0;
          optionKey <= 1'b0;
          breakKey <= 1'b0;
          parseIdx <= 3'b000;
          state <= 6'h24;
        end
        6'h24 : begin
          if(when_Ch376UsbKeyboard_l521) begin
            if(when_Ch376UsbKeyboard_l523) begin
              startKey <= 1'b1;
            end
            if(when_Ch376UsbKeyboard_l524) begin
              selectKey <= 1'b1;
            end
            if(when_Ch376UsbKeyboard_l525) begin
              optionKey <= 1'b1;
            end
            if(when_Ch376UsbKeyboard_l526) begin
              breakKey <= 1'b1;
            end
            if(when_Ch376UsbKeyboard_l529) begin
              if(when_Ch376UsbKeyboard_l531) begin
                atariKeys[_zz_atariKeys_1[5 : 0]] <= 1'b1;
              end
            end
          end
          if(when_Ch376UsbKeyboard_l537) begin
            delayCnt <= 28'h007a120;
            state <= 6'h1e;
          end else begin
            parseIdx <= (parseIdx + 3'b001);
          end
        end
        default : begin
          state <= 6'h0;
        end
      endcase
    end
  end


endmodule
