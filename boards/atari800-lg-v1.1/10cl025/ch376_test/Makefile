QUARTUS     = /opt/altera/25.1/quartus/bin
QP          = ch376_test
SOF_FILE    = output_files/$(QP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        ?= 500000

.PHONY: build program probe clean

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

probe:
	python3 ch376_probe.py $(SERIAL_PORT) $(BAUD)

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
