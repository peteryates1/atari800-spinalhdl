# vivado -mode batch -source build.tcl [-tclargs <part>]   (default -1)
set here  [file normalize [file dirname [info script]]]
set part  [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
set dvi   [file normalize [file join $here ../../wukong-1080/vivado/src/rgb2dvi]]
set bdir  [file join $here build]
file mkdir $bdir
foreach f [glob $dvi/*.vhd] { if {[file tail $f] ne "rgb2dvi.vhd"} { read_vhdl -vhdl2008 $f } }
read_vhdl -vhdl2008 [file join $here rgb2dvi_inv.vhd]
read_verilog [file join $here hdmi_bars_top.v]
read_xdc     [file join $here hdmi_bars.xdc]
synth_design -top hdmi_bars_top -part $part
opt_design
place_design
route_design
report_timing_summary -file [file join $bdir timing.rpt]
report_clocks         -file [file join $bdir clocks.rpt]
write_bitstream -force [file join $bdir hdmi_bars.bit]
puts "HDMI BARS DONE ($part) WNS=[get_property SLACK [get_timing_paths -delay_type max]]"
