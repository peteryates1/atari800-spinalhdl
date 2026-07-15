#!/bin/bash
# Build the ECP5 720p HDMI colour-bar test (Ecp5DvidOutX2 / ODDRX2F) for the i5-ext board.
set -e
cd "$(dirname "$0")"
ROOT=../../..
mkdir -p build
echo "== 1/4 generate SV =="
( cd $ROOT && sbt "atari/runMain atari800.Atari800Ecp5Hdmi720TestSv" )
echo "== 2/4 synth =="
yosys -p "read_verilog -sv pll_hdmi_ecp5.sv ecp5_clkgen720.v ecp5_oddrx2x4.v \
  $ROOT/generated/Atari800Ecp5Hdmi720TestTop.sv; \
  synth_ecp5 -top Atari800Ecp5Hdmi720TestTop -json build/h720.json"
echo "== 3/4 pnr =="
nextpnr-ecp5 --25k --package CABGA381 --speed 6 --json build/h720.json \
  --lpf ../hdmi_test.lpf --lpf-allow-unconstrained --textcfg build/h720.config
echo "== 4/4 pack =="
ecppack --compress build/h720.config build/h720.bit
echo "bitstream: build/h720.bit"
echo "flash (SRAM): openFPGALoader -b colorlight-i5 build/h720.bit"
echo "flash (flash): openFPGALoader -b colorlight-i5 -f build/h720.bit"
