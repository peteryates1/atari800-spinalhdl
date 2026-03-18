# ECP5 build flow for Colorlight i9 module (LFE5U-45F-6BG381C)
# yosys -> nextpnr-ecp5 -> ecppack
#
# Usage:
#   make synth     - synthesis only (utilisation report)
#   make pnr       - place and route (timing report)
#   make bitstream  - full build including bitstream
#   make clean

DEVICE = 45k
PACKAGE = CABGA381
SPEED = 6

# Source files (freshly generated SystemVerilog)
SV_DIR = ../../generated
SV_SRCS = $(SV_DIR)/Atari800JopTop.sv
SV_TOP = ../AC608/fit_check_top.sv
PLL_SV = pll_ecp5.sv
LPF = colorlight_i9.lpf

# ROM/RAM init files
BIN_FILES = $(wildcard $(SV_DIR)/*.bin)

TOP = fit_check_top

# Output
BUILD = build
JSON = $(BUILD)/$(TOP).json
PNR = $(BUILD)/$(TOP)_pnr.json
BIT = $(BUILD)/$(TOP).bit
LOG_SYNTH = $(BUILD)/synth.log
LOG_PNR = $(BUILD)/pnr.log

.PHONY: synth pnr bitstream clean

synth: $(JSON)

$(JSON): $(SV_SRCS) $(SV_TOP) $(PLL_SV) $(BIN_FILES)
	@mkdir -p $(BUILD)
	cd $(SV_DIR) && yosys -p "\
		read_verilog -sv $(abspath $(PLL_SV)); \
		read_verilog -sv $(abspath $(SV_TOP)); \
		read_verilog -sv $(notdir $(SV_SRCS)); \
		synth_ecp5 -top $(TOP) -json $(abspath $(JSON))" \
		2>&1 | tee $(abspath $(LOG_SYNTH))
	@echo "=== Synthesis complete ==="
	@grep -A 20 "Printing statistics" $(LOG_SYNTH) || true

pnr: $(PNR)

$(PNR): $(JSON) $(LPF)
	nextpnr-ecp5 --$(DEVICE) --package $(PACKAGE) --speed $(SPEED) \
		--json $(JSON) --lpf $(LPF) --textcfg $(PNR) \
		--freq 56.818 \
		--lpf-allow-unconstrained \
		--report $(BUILD)/report.json \
		2>&1 | tee $(LOG_PNR)
	@echo "=== Place and route complete ==="

bitstream: $(BIT)

$(BIT): $(PNR)
	ecppack --compress $(PNR) $(BIT)
	@echo "=== Bitstream: $(BIT) ==="

clean:
	rm -rf $(BUILD)
