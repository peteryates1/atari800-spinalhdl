# vivado -mode batch -source build.tcl -tclargs <top> <part>
set here [file normalize [file dirname [info script]]]
lassign $argv top part
set bdir [file join $here build_$top]
file mkdir $bdir
read_verilog [file join $here $top.v]
read_xdc     [file join $here $top.xdc]
synth_design -top $top -part $part
opt_design; place_design; route_design
report_timing_summary -file [file join $bdir timing.rpt]
write_bitstream -force [file join $bdir $top.bit]
puts "CT DONE $top WNS=[get_property SLACK [get_timing_paths -delay_type max]]"
