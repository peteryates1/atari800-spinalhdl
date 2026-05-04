QUARTUS     = /opt/altera/25.1/quartus/bin
ROOT        = ../../../..
JOP_DIR     = $(ROOT)/jop-spinalhdl
QP          = atari800_lg_v1
SOF_FILE    = output_files/$(QP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        = 500000
APP_DIR     = $(ROOT)/java/apps/AtariSupervisor
JOP_APP    ?= $(APP_DIR)/AtariSupervisor.jop
DOWNLOAD_PY = $(JOP_DIR)/fpga/scripts/download.py
MONITOR_PY  = $(JOP_DIR)/fpga/scripts/monitor.py

.PHONY: generate build program download monitor run const-java asm jop-app clean

# Atari 800 + JOP dual-PLL on LG-V1 + QMTECH EP4CGX150 (uses Atari800Ep4cgx150DualPllTop)
generate:
	cd $(ROOT) && sbt "atari/runMain atari800.Atari800Ep4cgx150DualPllSv"

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

download:
	python3 $(DOWNLOAD_PY) $(JOP_APP) $(SERIAL_PORT) $(BAUD)

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD)

run: program download monitor

const-java:
	cd $(ROOT) && sbt "atari/runMain atari800.GenerateConstJava --write"

asm:
	$(MAKE) -C $(JOP_DIR)/asm serial

jop-app:
	$(MAKE) -C $(APP_DIR)

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
