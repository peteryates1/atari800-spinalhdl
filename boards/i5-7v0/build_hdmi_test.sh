#!/bin/bash
# Build + program the ECP5 640x480 HDMI jitter test (Colorlight i5 v7.0).
# Self-contained: 25MHz -> PLL -> fixed supervisor-style 640x480 text -> HDMI.
set -e
cd "$(dirname "$0")"
ROOT=../..
echo "== 1/4 generate SV =="
( cd $ROOT && sbt "atari/runMain retro.boards.Atari800Ecp5HdmiTestSv" )
echo "== 2/4 synth =="
mkdir -p build
yosys -p "read_verilog -sv pll_hdmi_ecp5.sv; read_verilog -sv ecp5_ddr_out.v; \
  read_verilog -sv $ROOT/generated/Atari800Ecp5HdmiTestTop.sv; \
  synth_ecp5 -top Atari800Ecp5HdmiTestTop -json build/hdmitest.json"
echo "== 3/4 pnr =="
nextpnr-ecp5 --25k --package CABGA381 --speed 6 \
  --json build/hdmitest.json --lpf hdmi_test.lpf --textcfg build/hdmitest.config \
  --lpf-allow-unconstrained
echo "== 4/4 pack =="
ecppack --compress build/hdmitest.config build/hdmitest.bit
echo "== bitstream: build/hdmitest.bit =="
echo "program (volatile SRAM):  openFPGALoader -b colorlight-i5 build/hdmitest.bit"
echo "program to flash:         openFPGALoader -b colorlight-i5 -f build/hdmitest.bit"
