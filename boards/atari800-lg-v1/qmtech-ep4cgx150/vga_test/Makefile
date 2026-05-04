QUARTUS  = /opt/altera/25.1/quartus/bin
QP       = vga_test
SOF_FILE = output_files/$(QP).sof

.PHONY: build program clean

# VGA test pattern for ATARI-800-LG-V1 + QMTECH EP4CGX150
# Self-contained — no SpinalHDL generation step
build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
