# Vivado synth + impl + bitstream — Wukong Atari-at-1080p (Phase 1).
set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set build_dir  [file join $board_root vivado/build]
set proj_name  "wukong_atari"
set proj_xpr   [file join $build_dir $proj_name $proj_name.xpr]

if {![file exists $proj_xpr]} { puts "ERROR: project not found: $proj_xpr"; exit 1 }
open_project $proj_xpr

reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} { puts "ERROR: synthesis failed"; exit 1 }

reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} { puts "ERROR: implementation failed"; exit 1 }

set impl_dir [get_property DIRECTORY [get_runs impl_1]]
puts "INFO: bitstream at ${impl_dir}/Atari800WukongTop.bit"
