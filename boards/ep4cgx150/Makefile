QUARTUS     = /opt/altera/25.1/quartus/bin
GEN_DIR     = ../../generated
JOP_DIR     = ../../jop-spinalhdl
QP          = atari_ep4cgx150
QP_JOP      = atari_ep4cgx150_jop
QP_DUALPLL  = atari_ep4cgx150_dualpll
SOF_FILE    = $(QP).sof
SOF_JOP     = $(QP_JOP).sof
SOF_DUALPLL = $(QP_DUALPLL).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD_JOP    = 500000
BAUD_DUALPLL = 500000
APP_DIR     = ../../java/apps/AtariSupervisor
JOP_APP     ?= $(APP_DIR)/AtariSupervisor.jop
DOWNLOAD_PY = $(JOP_DIR)/fpga/scripts/download.py
MONITOR_PY  = $(JOP_DIR)/fpga/scripts/monitor.py

.PHONY: generate build program \
        generate-jop build-jop program-jop download-jop monitor-jop run-jop \
        generate-dualpll build-dualpll program-dualpll download-dualpll monitor-dualpll run-dualpll \
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
# Generate Const.java from Atari JOP config (driven from atari project)
const-java:
	cd ../.. && sbt "atari/runMain atari800.GenerateConstJava --write"

# Build microcode assembly (serial boot variant)
asm:
	$(MAKE) -C $(JOP_DIR)/asm serial

# Build AtariSupervisor.jop (requires const-java + asm first)
jop-app:
	$(MAKE) -C $(APP_DIR)

# ---------------------------------------------------------------------------
# Single-PLL JOP build (Atari + JOP BRAM-only + CH376T)
# ---------------------------------------------------------------------------
generate-jop:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150JopSv"

build-jop:
	$(QUARTUS)/quartus_sh --flow compile $(QP_JOP)

program-jop: $(SOF_JOP)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_JOP)@1"

download-jop:
	python3 $(DOWNLOAD_PY) $(JOP_APP) $(SERIAL_PORT) $(BAUD_JOP)

monitor-jop:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_JOP)

run-jop: program-jop download-jop monitor-jop

# ---------------------------------------------------------------------------
# Dual-PLL build (JOP at 80 MHz + Atari at 56.67 MHz)
# ---------------------------------------------------------------------------
generate-dualpll:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150DualPllSv"

build-dualpll:
	$(QUARTUS)/quartus_sh --flow compile $(QP_DUALPLL)

program-dualpll: $(SOF_DUALPLL)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_DUALPLL)@1"

download-dualpll:
	python3 $(DOWNLOAD_PY) $(JOP_APP) $(SERIAL_PORT) $(BAUD_DUALPLL)

monitor-dualpll:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_DUALPLL)

run-dualpll: program-dualpll download-dualpll monitor-dualpll

# ---------------------------------------------------------------------------
clean:
	rm -rf db incremental_db output_files
	rm -f *.sof *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
