QUARTUS  = /opt/altera/25.1/quartus/bin
ROOT     = ../../../..
QP       = ch376_test
SOF_FILE = output_files/$(QP).sof

.PHONY: generate build program clean

# CH376T USB keyboard bridge — standalone test
# SpinalHDL-generated Ch376KeyboardTestTop.sv
generate:
	cd $(ROOT) && sbt "atari/runMain atari800.Ch376KeyboardTestTopSv"
	cp $(ROOT)/generated/Ch376KeyboardTestTop.sv .
	cp $(ROOT)/generated/Ch376KeyboardTestTop.sv_*.bin . 2>/dev/null || true

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf *.bin
