# Vivado project creation — Wukong XC7A100T 1080p HDMI (Phase 0).
# vivado -mode batch -source vivado/tcl/create_project.tcl
set script_dir [file dirname [file normalize [info script]]]
set board_root [file normalize [file join $script_dir ../..]]
set repo_root  [file normalize [file join $board_root ../..]]
set build_dir  [file join $board_root vivado/build]
set proj_name  "wukong_1080"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

# SpinalHDL-generated top
set sv [file join $repo_root generated/Atari800Wukong1080Top.sv]
if {![file exists $sv]} { puts "ERROR: $sv not found. Run 'make generate' first."; exit 1 }
add_files -norecurse $sv

# Vendor sources: MMCM (Verilog) + Digilent rgb2dvi + wrapper (VHDL)
add_files -norecurse [file join $board_root vivado/src/wukong_hdmi_mmcm.v]
foreach f [glob [file join $board_root vivado/src/rgb2dvi/*.vhd]] { add_files -norecurse $f }
add_files -norecurse [file join $board_root vivado/src/rgb2dvi_wrapper.vhd]

# The Digilent rgb2dvi VHDL uses VHDL-2008 constructs (constrained function
# returns, etc.) — compile all VHDL as 2008.
foreach f [get_files -filter {FILE_TYPE == "VHDL"}] { set_property FILE_TYPE "VHDL 2008" $f }

# Constraints
add_files -fileset constrs_1 -norecurse [file join $board_root vivado/constraints/wukong_hdmi.xdc]

set_property top Atari800Wukong1080Top [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: created [file join $build_dir $proj_name $proj_name.xpr]"
