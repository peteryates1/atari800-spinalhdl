# QMTech Wukong (XC7A100T) — Atari 800 at native 1080p60. See README.md.
#
# Two build families share this Makefile:
#   Phase 0 (colour bars):  make build / make program   (bring-up only)
#   Atari appliance:        make atari / make deploy-core / make push-core
#
#   make atari         # build .bit -> push to SD -> supervisor configs + boots it
#                      #   (full deploy over USB; no JTAG cable needed)
#   make deploy-core   # build-atari + push-core (update SD core, no activate)
#   make push-core     # push an already-built .bit to the SD core slot
#   make build-atari   # up to the bitstream only
#   make program-atari # direct SRAM load over a JTAG cable (CABLE=dirtyJtag)

REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO    ?= /opt/xilinx/2025.2/Vivado/bin/vivado
VDIR      := vivado
BUILD     := $(VDIR)/build/wukong_1080
BIT       := $(BUILD)/wukong_1080.runs/impl_1/Atari800Wukong1080Top.bit
CABLE     := dirtyJtag

.PHONY: generate create build program all \
        generate-atari create-atari build-atari program-atari atari \
        push-core deploy-core

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

# Direct SRAM program over a JTAG cable (dirtyJtag / usb-blaster). Immediate but
# volatile, and needs a cable; the SD deploy below is the appliance path.
program-atari:
	openFPGALoader --cable $(CABLE) $(ABIT)

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

# Main target: build, push the core to SD, then have the supervisor configure the
# Artix from it and boot the Atari — the full deploy entirely over USB (no cable).
atari: build-atari
	$(PUSH) $(ABIT) $(CORE_SDPATH) --activate
