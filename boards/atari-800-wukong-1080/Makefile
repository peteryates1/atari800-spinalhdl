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

# === Phase 1: Atari at 1080p ===
ABIT := $(VDIR)/build/wukong_atari/wukong_atari.runs/impl_1/Atari800WukongTop.bit

generate-atari:
	cd $(REPO_ROOT) && sbt "atari/runMain atari800.Atari800WukongSv"

create-atari: generate-atari
	$(VIVADO) -mode batch -source $(VDIR)/tcl/create_atari.tcl

build-atari: create-atari
	$(VIVADO) -mode batch -source $(VDIR)/tcl/build_atari.tcl

program-atari:
	openFPGALoader --cable $(CABLE) $(ABIT)

atari: build-atari program-atari

# === Push the built core onto the SD card over USB (no MSC/eject) ===
# Streams the bitstream to the supervisor's 'U' command, which writes it to the
# SD as the config-boot core. Reliable and progress-reporting; the supervisor
# JTAG-configures the Artix from this file at power-on (Phase 2b).
PUSH        := python3 $(REPO_ROOT)/firmware/supervisor/tools/push_file.py
CORE_SDPATH ?= /atari/800/core.bit

push-core:
	$(PUSH) $(ABIT) $(CORE_SDPATH)

# Build the bitstream and push it straight onto the SD card in one step.
deploy-core: build-atari push-core
