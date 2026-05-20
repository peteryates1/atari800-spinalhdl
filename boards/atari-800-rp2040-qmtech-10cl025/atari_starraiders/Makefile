QUARTUS  = /opt/altera/25.1/quartus/bin
ROOT     = ../../..
QP       = atari_starraiders
SOF_FILE = output_files/$(QP).sof

.PHONY: generate build program clean

# Re-generate the Atari core SV from SpinalHDL (only needed if the Scala
# source changes — output lands at $(ROOT)/generated/Atari800Rp2040HdmiLgTop.sv).
generate:
	cd $(ROOT) && sbt "atari/runMain atari800.Atari800Rp2040HdmiLgSv"

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
