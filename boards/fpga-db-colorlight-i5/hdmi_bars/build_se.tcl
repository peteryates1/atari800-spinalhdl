# vivado -mode batch -source build_se.tcl [-tclargs <part> [<drive> <slew>]]
#   default xc7a50tfgg484-1, the XDC's drive/slew; a drive/slew pair overrides
#   the HDMI pins and builds into build_se_d<drive><slew>.
set here  [file normalize [file dirname [info script]]]
set part  [expr {[llength $argv] ? [lindex $argv 0] : "xc7a50tfgg484-1"}]
set drive [lindex $argv 1]
set slew  [lindex $argv 2]
set dvi   [file normalize [file join $here ../../wukong-1080/vivado/src/rgb2dvi]]
set bdir  [file join $here [expr {$drive eq "" ? "build_se" : "build_se_d${drive}[string tolower $slew]"}]]
file mkdir $bdir
read_vhdl -vhdl2008 [file join $dvi TMDS_Encoder.vhd]
read_vhdl -vhdl2008 [file join $dvi DVI_Constants.vhd]
read_verilog [file join $here hdmi_se_top.v]
read_xdc     [file join $here hdmi_se.xdc]
synth_design -top hdmi_se_top -part $part
if {$drive ne ""} { set_property -dict [list DRIVE $drive SLEW $slew] [get_ports hdmi_*] }
opt_design
place_design
route_design
report_timing_summary -file [file join $bdir timing.rpt]
write_bitstream -force [file join $bdir hdmi_se.bit]
puts "HDMI SE DONE ($part) WNS=[get_property SLACK [get_timing_paths -delay_type max]]"
