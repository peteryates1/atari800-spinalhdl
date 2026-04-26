QUARTUS     = /opt/altera/25.1/quartus/bin
GEN_DIR     = ../../generated
JOP_DIR     = ../../jop-spinalhdl
QP          = atari800_lg_v1_bram

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        = 500000
APP_DIR     = ../../java/apps/AtariSupervisor
JOP_APP     ?= $(APP_DIR)/AtariSupervisor.jop
DOWNLOAD_PY = $(JOP_DIR)/fpga/scripts/download.py
MONITOR_PY  = $(JOP_DIR)/fpga/scripts/monitor.py

.PHONY: generate build program download monitor run clean

# ---------------------------------------------------------------------------
# SpinalHDL generate (same design as ep4cgx150 dualpll)
# ---------------------------------------------------------------------------
generate:
	cd ../.. && sbt "atari/runMain atari800.Atari800LgV1Sv"

# ---------------------------------------------------------------------------
# Quartus build
# ---------------------------------------------------------------------------
build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program:
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;output_files/$(QP).sof@1"

download:
	python3 $(DOWNLOAD_PY) $(JOP_APP) $(SERIAL_PORT) $(BAUD)

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD)

run: program download monitor

# ---------------------------------------------------------------------------
clean:
	rm -rf db incremental_db output_files
