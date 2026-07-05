// Extracted from generated/Atari800Rp2040HdmiLgTop.sv — DvidOut + TmdsEncoder
// (SpinalHDL-generated HDMI/DVI TMDS output path) for standalone HDMI bring-up.

module DvidOut (
  input  wire          io_clkPixel,
  input  wire          io_clkTmds,
  input  wire [7:0]    io_red,
  input  wire [7:0]    io_green,
  input  wire [7:0]    io_blue,
  input  wire          io_hsync,
  input  wire          io_vsync,
  input  wire          io_de,
  output wire          io_tmdsD0P,
  output wire          io_tmdsD0N,
  output wire          io_tmdsD1P,
  output wire          io_tmdsD1N,
  output wire          io_tmdsD2P,
  output wire          io_tmdsD2N,
  output wire          io_tmdsClkP,
  output wire          io_tmdsClkN
);

  wire       [1:0]    pixelArea_encBlue_io_ctrl;
  wire       [3:0]    ddrN_datain_h;
  wire       [3:0]    ddrN_datain_l;
  wire       [9:0]    pixelArea_encBlue_io_tmdsOut;
  wire       [9:0]    pixelArea_encGreen_io_tmdsOut;
  wire       [9:0]    pixelArea_encRed_io_tmdsOut;
  wire       [3:0]    ddrP_dataout;
  wire       [3:0]    ddrN_dataout;
  wire       [9:0]    tmdsCh0;
  wire       [9:0]    tmdsCh1;
  wire       [9:0]    tmdsCh2;
  (* async_reg = "true" *) reg        [9:0]    pixelArea_ch0Lat;
  (* async_reg = "true" *) reg        [9:0]    pixelArea_ch1Lat;
  (* async_reg = "true" *) reg        [9:0]    pixelArea_ch2Lat;
  reg        [4:0]    tmdsArea_shift0H;
  reg        [4:0]    tmdsArea_shift0L;
  reg        [4:0]    tmdsArea_shift1H;
  reg        [4:0]    tmdsArea_shift1L;
  reg        [4:0]    tmdsArea_shift2H;
  reg        [4:0]    tmdsArea_shift2L;
  reg        [4:0]    tmdsArea_shiftCH;
  reg        [4:0]    tmdsArea_shiftCL;
  reg        [2:0]    tmdsArea_shiftCnt;
  wire                when_DvidOut_l91;
  wire       [3:0]    tmdsArea_ddrH;
  wire       [3:0]    tmdsArea_ddrL;

  TmdsEncoder pixelArea_encBlue (
    .io_data     (io_blue[7:0]                     ), //i
    .io_ctrl     (pixelArea_encBlue_io_ctrl[1:0]   ), //i
    .io_dataEn   (io_de                            ), //i
    .io_tmdsOut  (pixelArea_encBlue_io_tmdsOut[9:0]), //o
    .io_clkPixel (io_clkPixel                      )  //i
  );
  TmdsEncoder pixelArea_encGreen (
    .io_data     (io_green[7:0]                     ), //i
    .io_ctrl     (2'b00                             ), //i
    .io_dataEn   (io_de                             ), //i
    .io_tmdsOut  (pixelArea_encGreen_io_tmdsOut[9:0]), //o
    .io_clkPixel (io_clkPixel                       )  //i
  );
  TmdsEncoder pixelArea_encRed (
    .io_data     (io_red[7:0]                     ), //i
    .io_ctrl     (2'b00                           ), //i
    .io_dataEn   (io_de                           ), //i
    .io_tmdsOut  (pixelArea_encRed_io_tmdsOut[9:0]), //o
    .io_clkPixel (io_clkPixel                     )  //i
  );
  altddio_out #(
    .intended_device_family ("Cyclone 10 LP"),
    .invert_output          ("OFF"          ),
    .lpm_type               ("altddio_out"  ),
    .width                  (4              )
  ) ddrP (
    .datain_h (tmdsArea_ddrH[3:0]), //i
    .datain_l (tmdsArea_ddrL[3:0]), //i
    .outclock (io_clkTmds        ), //i
    .dataout  (ddrP_dataout[3:0] )  //o
  );
  altddio_out #(
    .intended_device_family ("Cyclone 10 LP"),
    .invert_output          ("OFF"          ),
    .lpm_type               ("altddio_out"  ),
    .width                  (4              )
  ) ddrN (
    .datain_h (ddrN_datain_h[3:0]), //i
    .datain_l (ddrN_datain_l[3:0]), //i
    .outclock (io_clkTmds        ), //i
    .dataout  (ddrN_dataout[3:0] )  //o
  );
  initial begin
    pixelArea_ch0Lat = 10'h0;
    pixelArea_ch1Lat = 10'h0;
    pixelArea_ch2Lat = 10'h0;
    tmdsArea_shift0H = 5'h0;
    tmdsArea_shift0L = 5'h0;
    tmdsArea_shift1H = 5'h0;
    tmdsArea_shift1L = 5'h0;
    tmdsArea_shift2H = 5'h0;
    tmdsArea_shift2L = 5'h0;
    tmdsArea_shiftCH = 5'h0;
    tmdsArea_shiftCL = 5'h0;
    tmdsArea_shiftCnt = 3'b000;
  end

  assign pixelArea_encBlue_io_ctrl = {io_vsync,io_hsync};
  assign tmdsCh0 = pixelArea_encBlue_io_tmdsOut;
  assign tmdsCh1 = pixelArea_encGreen_io_tmdsOut;
  assign tmdsCh2 = pixelArea_encRed_io_tmdsOut;
  assign when_DvidOut_l91 = (tmdsArea_shiftCnt == 3'b100);
  assign tmdsArea_ddrH = {{{tmdsArea_shiftCH[0],tmdsArea_shift2H[0]},tmdsArea_shift1H[0]},tmdsArea_shift0H[0]};
  assign tmdsArea_ddrL = {{{tmdsArea_shiftCL[0],tmdsArea_shift2L[0]},tmdsArea_shift1L[0]},tmdsArea_shift0L[0]};
  assign ddrN_datain_h = (~ tmdsArea_ddrH);
  assign ddrN_datain_l = (~ tmdsArea_ddrL);
  assign io_tmdsD0P = ddrP_dataout[0];
  assign io_tmdsD0N = ddrN_dataout[0];
  assign io_tmdsD1P = ddrP_dataout[1];
  assign io_tmdsD1N = ddrN_dataout[1];
  assign io_tmdsD2P = ddrP_dataout[2];
  assign io_tmdsD2N = ddrN_dataout[2];
  assign io_tmdsClkP = ddrP_dataout[3];
  assign io_tmdsClkN = ddrN_dataout[3];
  always @(posedge io_clkPixel) begin
    pixelArea_ch0Lat <= tmdsCh0;
    pixelArea_ch1Lat <= tmdsCh1;
    pixelArea_ch2Lat <= tmdsCh2;
  end

  always @(posedge io_clkTmds) begin
    if(when_DvidOut_l91) begin
      tmdsArea_shiftCnt <= 3'b000;
      tmdsArea_shift0H <= {{{{pixelArea_ch0Lat[8],pixelArea_ch0Lat[6]},pixelArea_ch0Lat[4]},pixelArea_ch0Lat[2]},pixelArea_ch0Lat[0]};
      tmdsArea_shift0L <= {{{{pixelArea_ch0Lat[9],pixelArea_ch0Lat[7]},pixelArea_ch0Lat[5]},pixelArea_ch0Lat[3]},pixelArea_ch0Lat[1]};
      tmdsArea_shift1H <= {{{{pixelArea_ch1Lat[8],pixelArea_ch1Lat[6]},pixelArea_ch1Lat[4]},pixelArea_ch1Lat[2]},pixelArea_ch1Lat[0]};
      tmdsArea_shift1L <= {{{{pixelArea_ch1Lat[9],pixelArea_ch1Lat[7]},pixelArea_ch1Lat[5]},pixelArea_ch1Lat[3]},pixelArea_ch1Lat[1]};
      tmdsArea_shift2H <= {{{{pixelArea_ch2Lat[8],pixelArea_ch2Lat[6]},pixelArea_ch2Lat[4]},pixelArea_ch2Lat[2]},pixelArea_ch2Lat[0]};
      tmdsArea_shift2L <= {{{{pixelArea_ch2Lat[9],pixelArea_ch2Lat[7]},pixelArea_ch2Lat[5]},pixelArea_ch2Lat[3]},pixelArea_ch2Lat[1]};
      tmdsArea_shiftCH <= 5'h07;  // FIX: was 5'h0a (broken clock); 5'h07 -> clean 1111100000
      tmdsArea_shiftCL <= 5'h03;
    end else begin
      tmdsArea_shiftCnt <= (tmdsArea_shiftCnt + 3'b001);
      tmdsArea_shift0H <= {1'b0,tmdsArea_shift0H[4 : 1]};
      tmdsArea_shift0L <= {1'b0,tmdsArea_shift0L[4 : 1]};
      tmdsArea_shift1H <= {1'b0,tmdsArea_shift1H[4 : 1]};
      tmdsArea_shift1L <= {1'b0,tmdsArea_shift1L[4 : 1]};
      tmdsArea_shift2H <= {1'b0,tmdsArea_shift2H[4 : 1]};
      tmdsArea_shift2L <= {1'b0,tmdsArea_shift2L[4 : 1]};
      tmdsArea_shiftCH <= {1'b0,tmdsArea_shiftCH[4 : 1]};
      tmdsArea_shiftCL <= {1'b0,tmdsArea_shiftCL[4 : 1]};
    end
  end


endmodule

module TmdsEncoder (
  input  wire [7:0]    io_data,
  input  wire [1:0]    io_ctrl,
  input  wire          io_dataEn,
  output wire [9:0]    io_tmdsOut,
  input  wire          io_clkPixel
);

  wire       [3:0]    _zz_n1;
  wire       [3:0]    _zz_n1_1;
  wire       [3:0]    _zz_n1_2;
  wire       [3:0]    _zz_n1_3;
  wire       [3:0]    _zz_n1_4;
  wire       [3:0]    _zz_n1_5;
  wire       [3:0]    _zz_n1_6;
  wire       [0:0]    _zz_n1_7;
  wire       [3:0]    _zz_n1_8;
  wire       [0:0]    _zz_n1_9;
  wire       [3:0]    _zz_n1_10;
  wire       [0:0]    _zz_n1_11;
  wire       [3:0]    _zz_n1_12;
  wire       [0:0]    _zz_n1_13;
  wire       [3:0]    _zz_n1_14;
  wire       [0:0]    _zz_n1_15;
  wire       [3:0]    _zz_n1_16;
  wire       [0:0]    _zz_n1_17;
  wire       [3:0]    _zz_n1_18;
  wire       [0:0]    _zz_n1_19;
  wire       [3:0]    _zz_n1_20;
  wire       [0:0]    _zz_n1_21;
  wire       [3:0]    _zz_n1Qm_1;
  wire       [3:0]    _zz_n1Qm_2;
  wire       [3:0]    _zz_n1Qm_3;
  wire       [3:0]    _zz_n1Qm_4;
  wire       [3:0]    _zz_n1Qm_5;
  wire       [3:0]    _zz_n1Qm_6;
  wire       [3:0]    _zz_n1Qm_7;
  wire       [0:0]    _zz_n1Qm_8;
  wire       [3:0]    _zz_n1Qm_9;
  wire       [0:0]    _zz_n1Qm_10;
  wire       [3:0]    _zz_n1Qm_11;
  wire       [0:0]    _zz_n1Qm_12;
  wire       [3:0]    _zz_n1Qm_13;
  wire       [0:0]    _zz_n1Qm_14;
  wire       [3:0]    _zz_n1Qm_15;
  wire       [0:0]    _zz_n1Qm_16;
  wire       [3:0]    _zz_n1Qm_17;
  wire       [0:0]    _zz_n1Qm_18;
  wire       [3:0]    _zz_n1Qm_19;
  wire       [0:0]    _zz_n1Qm_20;
  wire       [3:0]    _zz_n1Qm_21;
  wire       [0:0]    _zz_n1Qm_22;
  wire       [3:0]    _zz_diff;
  wire       [3:0]    _zz_dcBias;
  wire       [3:0]    _zz_dcBias_1;
  wire       [8:0]    qM;
  wire       [3:0]    n1;
  wire       [8:0]    qCalc;
  wire                useXnor;
  wire                q_0;
  wire                q_1;
  wire                q_2;
  wire                q_3;
  wire                q_4;
  wire                q_5;
  wire                q_6;
  wire                q_7;
  wire                q_8;
  reg        [3:0]    dcBias;
  reg        [9:0]    outWord;
  wire       [7:0]    _zz_n1Qm;
  wire       [3:0]    n1Qm;
  wire       [3:0]    n0Qm;
  wire       [3:0]    diff;
  wire                when_TmdsEncoder_l46;
  wire                when_TmdsEncoder_l56;
  wire                when_TmdsEncoder_l59;
  wire                when_TmdsEncoder_l70;
  wire                when_TmdsEncoder_l79;
  wire                when_TmdsEncoder_l66;

  assign _zz_n1 = (_zz_n1_1 + _zz_n1_18);
  assign _zz_n1_1 = (_zz_n1_2 + _zz_n1_16);
  assign _zz_n1_2 = (_zz_n1_3 + _zz_n1_14);
  assign _zz_n1_3 = (_zz_n1_4 + _zz_n1_12);
  assign _zz_n1_4 = (_zz_n1_5 + _zz_n1_10);
  assign _zz_n1_5 = (_zz_n1_6 + _zz_n1_8);
  assign _zz_n1_7 = io_data[0];
  assign _zz_n1_6 = {3'd0, _zz_n1_7};
  assign _zz_n1_9 = io_data[1];
  assign _zz_n1_8 = {3'd0, _zz_n1_9};
  assign _zz_n1_11 = io_data[2];
  assign _zz_n1_10 = {3'd0, _zz_n1_11};
  assign _zz_n1_13 = io_data[3];
  assign _zz_n1_12 = {3'd0, _zz_n1_13};
  assign _zz_n1_15 = io_data[4];
  assign _zz_n1_14 = {3'd0, _zz_n1_15};
  assign _zz_n1_17 = io_data[5];
  assign _zz_n1_16 = {3'd0, _zz_n1_17};
  assign _zz_n1_19 = io_data[6];
  assign _zz_n1_18 = {3'd0, _zz_n1_19};
  assign _zz_n1_21 = io_data[7];
  assign _zz_n1_20 = {3'd0, _zz_n1_21};
  assign _zz_n1Qm_1 = (_zz_n1Qm_2 + _zz_n1Qm_19);
  assign _zz_n1Qm_2 = (_zz_n1Qm_3 + _zz_n1Qm_17);
  assign _zz_n1Qm_3 = (_zz_n1Qm_4 + _zz_n1Qm_15);
  assign _zz_n1Qm_4 = (_zz_n1Qm_5 + _zz_n1Qm_13);
  assign _zz_n1Qm_5 = (_zz_n1Qm_6 + _zz_n1Qm_11);
  assign _zz_n1Qm_6 = (_zz_n1Qm_7 + _zz_n1Qm_9);
  assign _zz_n1Qm_8 = _zz_n1Qm[0];
  assign _zz_n1Qm_7 = {3'd0, _zz_n1Qm_8};
  assign _zz_n1Qm_10 = _zz_n1Qm[1];
  assign _zz_n1Qm_9 = {3'd0, _zz_n1Qm_10};
  assign _zz_n1Qm_12 = _zz_n1Qm[2];
  assign _zz_n1Qm_11 = {3'd0, _zz_n1Qm_12};
  assign _zz_n1Qm_14 = _zz_n1Qm[3];
  assign _zz_n1Qm_13 = {3'd0, _zz_n1Qm_14};
  assign _zz_n1Qm_16 = _zz_n1Qm[4];
  assign _zz_n1Qm_15 = {3'd0, _zz_n1Qm_16};
  assign _zz_n1Qm_18 = _zz_n1Qm[5];
  assign _zz_n1Qm_17 = {3'd0, _zz_n1Qm_18};
  assign _zz_n1Qm_20 = _zz_n1Qm[6];
  assign _zz_n1Qm_19 = {3'd0, _zz_n1Qm_20};
  assign _zz_n1Qm_22 = _zz_n1Qm[7];
  assign _zz_n1Qm_21 = {3'd0, _zz_n1Qm_22};
  assign _zz_diff = (n1Qm - n0Qm);
  assign _zz_dcBias = ($signed(dcBias) - $signed(diff));
  assign _zz_dcBias_1 = ($signed(dcBias) + $signed(diff));
  initial begin
    dcBias = 4'b0000;
    outWord = 10'h0;
  end

  assign n1 = (_zz_n1 + _zz_n1_20);
  assign useXnor = ((4'b0100 < n1) || ((n1 == 4'b0100) && (! io_data[0])));
  assign q_0 = io_data[0];
  assign q_1 = (useXnor ? (! (q_0 ^ io_data[1])) : (q_0 ^ io_data[1]));
  assign q_2 = (useXnor ? (! (q_1 ^ io_data[2])) : (q_1 ^ io_data[2]));
  assign q_3 = (useXnor ? (! (q_2 ^ io_data[3])) : (q_2 ^ io_data[3]));
  assign q_4 = (useXnor ? (! (q_3 ^ io_data[4])) : (q_3 ^ io_data[4]));
  assign q_5 = (useXnor ? (! (q_4 ^ io_data[5])) : (q_4 ^ io_data[5]));
  assign q_6 = (useXnor ? (! (q_5 ^ io_data[6])) : (q_5 ^ io_data[6]));
  assign q_7 = (useXnor ? (! (q_6 ^ io_data[7])) : (q_6 ^ io_data[7]));
  assign q_8 = (! useXnor);
  assign qM = {q_8,{q_7,{q_6,{q_5,{q_4,{q_3,{q_2,{q_1,q_0}}}}}}}};
  assign _zz_n1Qm = qM[7 : 0];
  assign n1Qm = (_zz_n1Qm_1 + _zz_n1Qm_21);
  assign n0Qm = (4'b1000 - n1Qm);
  assign diff = _zz_diff;
  assign when_TmdsEncoder_l46 = (! io_dataEn);
  assign when_TmdsEncoder_l56 = (($signed(dcBias) == $signed(4'b0000)) || ($signed(diff) == $signed(4'b0000)));
  assign when_TmdsEncoder_l59 = (! qM[8]);
  assign when_TmdsEncoder_l70 = qM[8];
  assign when_TmdsEncoder_l79 = (! qM[8]);
  assign when_TmdsEncoder_l66 = ((($signed(4'b0000) < $signed(dcBias)) && ($signed(4'b0000) < $signed(diff))) || (($signed(dcBias) < $signed(4'b0000)) && ($signed(diff) < $signed(4'b0000))));
  assign io_tmdsOut = outWord;
  always @(posedge io_clkPixel) begin
    if(when_TmdsEncoder_l46) begin
      dcBias <= 4'b0000;
      case(io_ctrl)
        2'b00 : begin
          outWord <= 10'h354;
        end
        2'b01 : begin
          outWord <= 10'h0ab;
        end
        2'b10 : begin
          outWord <= 10'h154;
        end
        default : begin
          outWord <= 10'h2ab;
        end
      endcase
    end else begin
      if(when_TmdsEncoder_l56) begin
        outWord[9] <= (! qM[8]);
        outWord[8] <= qM[8];
        if(when_TmdsEncoder_l59) begin
          outWord[7 : 0] <= (~ qM[7 : 0]);
          dcBias <= ($signed(dcBias) - $signed(diff));
        end else begin
          outWord[7 : 0] <= qM[7 : 0];
          dcBias <= ($signed(dcBias) + $signed(diff));
        end
      end else begin
        if(when_TmdsEncoder_l66) begin
          outWord[9] <= 1'b1;
          outWord[8] <= qM[8];
          outWord[7 : 0] <= (~ qM[7 : 0]);
          if(when_TmdsEncoder_l70) begin
            dcBias <= ($signed(_zz_dcBias) + $signed(4'b0010));
          end else begin
            dcBias <= ($signed(dcBias) - $signed(diff));
          end
        end else begin
          outWord[9] <= 1'b0;
          outWord[8] <= qM[8];
          outWord[7 : 0] <= qM[7 : 0];
          if(when_TmdsEncoder_l79) begin
            dcBias <= ($signed(_zz_dcBias_1) - $signed(4'b0010));
          end else begin
            dcBias <= ($signed(dcBias) + $signed(diff));
          end
        end
      end
    end
  end


endmodule

