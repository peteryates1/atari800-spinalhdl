# 1080p60 HDMI serializer timing probe for the Colorlight i9+ (XC7A50T).
# Usage: vivado -mode batch -source build_hdmi.tcl -tclargs <part> <tag>
#   e.g. ... -tclargs xc7a50tfgg484-1 s1
#        ... -tclargs xc7a50tfgg484-2 s2
# Reuses the Wukong's proven Digilent rgb2dvi (OSERDESE2 + internal 5x MMCM).

set part [lindex $argv 0]
set tag  [lindex $argv 1]
set here [file dirname [file normalize [info script]]]
set wuk  [file normalize [file join $here ../../wukong-1080/vivado/src]]

create_project -in_memory -part $part

read_vhdl -vhdl2008 [glob $wuk/rgb2dvi/*.vhd]
read_vhdl -vhdl2008 $wuk/rgb2dvi_wrapper.vhd
read_verilog -sv $here/src/hdmi_test_top.sv
read_xdc $here/constraints/i9plus_hdmi.xdc

synth_design -top hdmi_test_top
opt_design
place_design
route_design

report_timing_summary -file $here/timing_$tag.rpt

# Worst intra-clock setup slack per clock -> the serial (742.5 MHz) clock is the limiter.
puts "===== RESULT $part ($tag) ====="
foreach clk [get_clocks] {
    set p [get_property PERIOD $clk]
    if {$p eq ""} { continue }
    set paths [get_timing_paths -delay_type max -to [get_clocks $clk] -from [get_clocks $clk] -max_paths 1 -nworst 1]
    if {[llength $paths] == 0} { continue }
    set wns [get_property SLACK [lindex $paths 0]]
    set freq [format %.1f [expr {1000.0/$p}]]
    if {$wns ne ""} {
        set achper [expr {$p - $wns}]
        set achf [format %.1f [expr {1000.0/$achper}]]
        puts [format "clock %-22s target=%6s MHz  WNS=%7s ns  -> Fmax=%6s MHz" [get_property NAME $clk] $freq [format %.3f $wns] $achf]
    }
}
set wns_all [get_property SLACK [get_timing_paths -delay_type max -max_paths 1 -nworst 1]]
puts "design WNS = $wns_all ns"
puts "===== END $tag ====="
