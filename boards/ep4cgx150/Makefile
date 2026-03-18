QUARTUS     = /opt/altera/25.1/quartus/bin
GEN_DIR     = ../../generated
QP          = atari_ep4cgx150
QP_JOP      = atari_ep4cgx150_jop
SOF_FILE    = $(QP).sof
SOF_JOP     = $(QP_JOP).sof

.PHONY: generate build program generate-jop build-jop program-jop clean

# ---------------------------------------------------------------------------
# Bare-metal (no JOP) build
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# JOP build (Atari + JOP + CH376T + SDRAM arbiter)
# ---------------------------------------------------------------------------
generate-jop:
	cd ../.. && sbt "atari/runMain atari800.Atari800Ep4cgx150JopSv"
	cp $(GEN_DIR)/Atari800Ep4cgx150JopTop.sv .
	cp $(GEN_DIR)/Atari800Ep4cgx150JopTop.sv_*.bin . 2>/dev/null || true

build-jop: Atari800Ep4cgx150JopTop.sv
	$(QUARTUS)/quartus_map $(QP_JOP)
	$(QUARTUS)/quartus_fit $(QP_JOP)
	$(QUARTUS)/quartus_asm $(QP_JOP)
	$(QUARTUS)/quartus_sta $(QP_JOP)

program-jop: $(SOF_JOP)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_JOP)@1"

# ---------------------------------------------------------------------------
clean:
	rm -rf db incremental_db
	rm -f Atari800Ep4cgx150Top.sv Atari800Ep4cgx150Top.sv_*.bin
	rm -f Atari800Ep4cgx150JopTop.sv Atari800Ep4cgx150JopTop.sv_*.bin
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
