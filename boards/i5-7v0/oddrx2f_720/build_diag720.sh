#!/bin/bash
# Build the Atari 800 -> 720p HDMI top (Atari800Ecp5Hdmi720DiagTop) for the i5-ext board.
# Atari core (48K BRAM, 800 OS, Star Raiders) -> GtiaPalette -> Hdmi720Scaler (genlocked
# 3x upscaler) -> Ecp5DvidOutX2 (ODDRX2F/LVCMOS33D) -> HDMI. Two PLLs: PllAtari800 (25->37.5)
# + ecp5_clkgen720 (25->370/74). yosys runs from generated/ so the ROM $readmemb .bin paths
# (emitted next to the .sv) resolve.
set -e
cd "$(dirname "$0")"
HERE=$(pwd)
ROOT=../../..
mkdir -p build

echo "== 1/4 generate SV =="
( cd $ROOT && sbt "atari/runMain atari800.Atari800Ecp5Hdmi720DiagSv" )

echo "== 2/4 synth (from generated/ for ROM .bin) =="
( cd $ROOT/generated && \
  yosys -p "read_verilog -sv $HERE/../pll_ecp5.sv $HERE/pll_hdmi_ecp5.sv $HERE/ecp5_clkgen720.v $HERE/ecp5_oddrx2x4.v \
    Atari800Ecp5Hdmi720DiagTop.sv; \
    synth_ecp5 -top Atari800Ecp5Hdmi720DiagTop -json $HERE/build/diag720.json" )

echo "== 3/4 pnr =="
nextpnr-ecp5 --25k --package CABGA381 --speed 6 --json build/diag720.json \
  --lpf hdmi720_lvds.lpf --lpf-allow-unconstrained --textcfg build/diag720.config

echo "== 4/4 pack =="
ecppack --compress build/diag720.config build/diag720.bit
echo "bitstream: build/diag720.bit"
echo "flash (SRAM):  openFPGALoader -b colorlight-i5 build/diag720.bit"
echo "flash (flash): openFPGALoader -b colorlight-i5 -f build/diag720.bit"
