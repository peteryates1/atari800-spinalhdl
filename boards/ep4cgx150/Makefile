QUARTUS     = /opt/altera/25.1/quartus/bin
GEN_DIR     = ../../generated
JOP_DIR     = ../../jop-spinalhdl
QP          = atari_ep4cgx150
QP_JOP      = atari_ep4cgx150_jop
SOF_FILE    = $(QP).sof
SOF_JOP     = $(QP_JOP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE   = 500000
JOP_APP     ?= $(JOP_DIR)/java/apps/AtariSupervisor/AtariSupervisor.jop
DOWNLOAD_PY = $(JOP_DIR)/fpga/scripts/download.py
MONITOR_PY  = $(JOP_DIR)/fpga/scripts/monitor.py
JOP_PRESET  = atariEp4cgx150

.PHONY: generate build program \
        generate-jop build-jop program-jop download monitor run-jop \
        const-java asm jop-app \
        clean

# ---------------------------------------------------------------------------
# Bare-metal (no JOP) build — uses generated SV in place
# ---------------------------------------------------------------------------
generate:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150Sv"

build:
	$(QUARTUS)/quartus_map $(QP)
	$(QUARTUS)/quartus_fit $(QP)
	$(QUARTUS)/quartus_asm $(QP)
	$(QUARTUS)/quartus_sta $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

# ---------------------------------------------------------------------------
# JOP software build chain
# ---------------------------------------------------------------------------
# Generate Const.java from hardware config (I/O addresses, features)
const-java:
	cd $(JOP_DIR) && sbt "runMain jop.generate.ConstGeneratorMain $(JOP_PRESET) --write"

# Build microcode assembly (serial boot variant)
asm:
	$(MAKE) -C $(JOP_DIR)/asm serial

# Build AtariSupervisor.jop (requires const-java + asm first)
jop-app:
	$(MAKE) -C $(JOP_DIR)/java/apps/AtariSupervisor

# ---------------------------------------------------------------------------
# JOP build (Atari + JOP BRAM-only + CH376T)
# Uses generated SV in place via QSF SEARCH_PATH
# ---------------------------------------------------------------------------
generate-jop:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150JopSv"

build-jop:
	$(QUARTUS)/quartus_map $(QP_JOP)
	$(QUARTUS)/quartus_fit $(QP_JOP)
	$(QUARTUS)/quartus_asm $(QP_JOP)
	$(QUARTUS)/quartus_sta $(QP_JOP)

program-jop: $(SOF_JOP)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_JOP)@1"

download:
	python3 $(DOWNLOAD_PY) $(JOP_APP) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_RATE)

run-jop: program-jop download monitor

# ---------------------------------------------------------------------------
clean:
	rm -rf db incremental_db output_files
	rm -f *.sof *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
