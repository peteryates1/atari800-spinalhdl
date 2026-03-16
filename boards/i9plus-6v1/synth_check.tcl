# Vivado synthesis-only flow for utilisation check
# Target: XC7A50T-FGG484 (Colorlight i9+)
# Usage: vivado -mode batch -source synth_check.tcl

set_part xc7a50tfgg484-1

# Read sources
read_verilog -sv pll_xilinx.sv
read_verilog -sv ../AC608/fit_check_top.sv
read_verilog -sv ../../generated/Atari800JopTop.sv

# Read ROM/RAM init files
foreach f [glob -nocomplain ../../generated/*.bin] {
    # Vivado picks these up automatically via $readmemb/$readmemh in the SV
}

# Synthesize
synth_design -top fit_check_top -flatten_hierarchy none

# Report utilisation
report_utilization -file synth_util.rpt
report_timing_summary -file synth_timing.rpt

puts "=== Synthesis complete ==="
puts "Utilisation report: synth_util.rpt"
puts "Timing report: synth_timing.rpt"
