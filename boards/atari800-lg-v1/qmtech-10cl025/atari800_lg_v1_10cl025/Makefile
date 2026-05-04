QUARTUS  = /opt/altera/25.1/quartus/bin
ROOT     = ../../../..
QP       = atari800_lg_v1_10cl025
SOF_FILE = output_files/$(QP).sof

.PHONY: generate build program clean

# Atari 800 BRAM-only build for ATARI-800-LG-V1 + QMTECH 10CL025
# 40K RAM + 8K cart ROM + 10K OS ROM = 58 of 66 M9K. No SDRAM, no JOP.
generate:
	cd $(ROOT) && sbt "atari/runMain atari800.Atari800LgV1Sv"

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
