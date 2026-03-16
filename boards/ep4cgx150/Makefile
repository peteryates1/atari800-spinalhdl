QUARTUS     = /opt/altera/25.1/quartus/bin
GEN_DIR     = ../../generated
QP          = atari_ep4cgx150
SOF_FILE    = $(QP).sof

.PHONY: generate build program clean

generate:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150Sv"
	cp $(GEN_DIR)/Atari800Ep4cgx150Top.sv .
	cp $(GEN_DIR)/Atari800Ep4cgx150Top.sv_*.bin . 2>/dev/null || true

build: Atari800Ep4cgx150Top.sv
	$(QUARTUS)/quartus_map $(QP)
	$(QUARTUS)/quartus_fit $(QP)
	$(QUARTUS)/quartus_asm $(QP)
	$(QUARTUS)/quartus_sta $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf $(OUTPUT_DIR) db incremental_db
	rm -f Atari800Ep4cgx150Top.sv Atari800Ep4cgx150Top.sv_*.bin
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
