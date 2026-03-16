QUARTUS     = /opt/altera/25.1/quartus/bin
QUARTUS_DIR = boards/AC608
GEN_DIR     = generated

.PHONY: bootstrap generate quartus-prep \
        quartus-map quartus-fit quartus-sta quartus-asm quartus all clean

# --- Bootstrap (after fresh clone) ---
# JOP microcode files are gitignored; generate them from the assembler.
# Requires: gcc, java, jop-spinalhdl/java/tools/dist/jopa.jar

bootstrap:
	cd jop-spinalhdl/asm && $(MAKE) all serial flash dsp div hwmath

# --- SpinalHDL generation (unified build — single step) ---

generate:
	sbt "atari/runMain atari800.Atari800JopTopSv"

# --- Quartus build ---

quartus-prep: generate
	cp $(GEN_DIR)/Atari800JopTop.sv $(QUARTUS_DIR)/
	cp $(GEN_DIR)/Atari800JopTop.sv_*.bin $(QUARTUS_DIR)/

quartus-map: quartus-prep
	cd $(QUARTUS_DIR) && $(QUARTUS)/quartus_map --read_settings_files=on --write_settings_files=off fit_check

quartus-fit: quartus-map
	cd $(QUARTUS_DIR) && $(QUARTUS)/quartus_fit --read_settings_files=on --write_settings_files=off fit_check

quartus-sta: quartus-fit
	cd $(QUARTUS_DIR) && $(QUARTUS)/quartus_sta fit_check

quartus-asm: quartus-fit
	cd $(QUARTUS_DIR) && $(QUARTUS)/quartus_asm fit_check

quartus: quartus-sta quartus-asm

all: quartus

# --- Cleanup ---

clean:
	sbt clean
	rm -f $(GEN_DIR)/*.sv $(GEN_DIR)/*.bin
	rm -rf $(QUARTUS_DIR)/db $(QUARTUS_DIR)/incremental_db $(QUARTUS_DIR)/output_files
	rm -f $(QUARTUS_DIR)/Atari800JopTop.sv $(QUARTUS_DIR)/Atari800JopTop.sv_*.bin
