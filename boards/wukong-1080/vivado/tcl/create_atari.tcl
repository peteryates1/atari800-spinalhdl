# Vivado project creation — Wukong XC7A100T Atari-at-1080p (Phase 1).
set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set repo_root  [file normalize [file join $board_root ../..]]
set build_dir  [file join $board_root vivado/build]
set proj_name  "wukong_atari"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

set sv [file join $repo_root generated/Atari800WukongTop.sv]
if {![file exists $sv]} { puts "ERROR: $sv not found. Run 'make generate-atari' first."; exit 1 }
add_files -norecurse $sv

# Vendor sources: both MMCMs + Digilent rgb2dvi + wrapper
add_files -norecurse [file join $board_root vivado/src/wukong_atari_mmcm.v]
add_files -norecurse [file join $board_root vivado/src/wukong_hdmi_mmcm.v]
foreach f [glob [file join $board_root vivado/src/rgb2dvi/*.vhd]] { add_files -norecurse $f }
add_files -norecurse [file join $board_root vivado/src/rgb2dvi_wrapper.vhd]
foreach f [get_files -filter {FILE_TYPE == "VHDL"}] { set_property FILE_TYPE "VHDL 2008" $f }

add_files -fileset constrs_1 -norecurse [file join $board_root vivado/constraints/wukong_atari.xdc]

set_property top Atari800WukongTop [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: created [file join $build_dir $proj_name $proj_name.xpr]"
