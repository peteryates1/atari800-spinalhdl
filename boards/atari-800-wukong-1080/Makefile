# QMTech Wukong (XC7A100T) — Phase 0: SpinalHDL 1080p60 HDMI colour bars.
# Parallel to the 10CL025 / ECP5 builds. Flow: generate (sbt) -> create + build
# (Vivado) -> program (dirtyJtag via the Pico 2 W).
#
#   make all        # generate + create + build + program
#   make build      # up to the bitstream
#   make program    # program the built bitstream over dirtyJtag

REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO    ?= /opt/xilinx/2025.2/Vivado/bin/vivado
VDIR      := vivado
BUILD     := $(VDIR)/build/wukong_1080
BIT       := $(BUILD)/wukong_1080.runs/impl_1/Atari800Wukong1080Top.bit
CABLE     := dirtyJtag

.PHONY: generate create build program all

generate:
	cd $(REPO_ROOT) && sbt "atari/runMain atari800.Atari800Wukong1080Sv"

create: generate
	$(VIVADO) -mode batch -source $(VDIR)/tcl/create_project.tcl

build: create
	$(VIVADO) -mode batch -source $(VDIR)/tcl/build_bitstream.tcl

program:
	openFPGALoader --cable $(CABLE) $(BIT)

all: build program
