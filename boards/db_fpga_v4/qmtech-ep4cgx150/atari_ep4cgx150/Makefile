QUARTUS  = /opt/altera/25.1/quartus/bin
ROOT     = ../../../..
GEN_DIR  = $(ROOT)/generated
QP       = atari_ep4cgx150
SOF_FILE = output_files/$(QP).sof

.PHONY: generate build program clean

# Bare-metal (no JOP) build — single PLL, BRAM-only
generate:
	cd $(ROOT) && sbt "atari/runMain atari800.Atari800Ep4cgx150Sv"

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
