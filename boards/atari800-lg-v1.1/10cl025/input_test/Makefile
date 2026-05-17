QUARTUS     = /opt/altera/25.1/quartus/bin
QP          = input_test
SOF_FILE    = output_files/$(QP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        ?= 115200

.PHONY: build program monitor changes clean

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

monitor:
	python3 input_monitor.py $(SERIAL_PORT) $(BAUD)

changes:
	python3 input_changes.py $(SERIAL_PORT) $(BAUD)

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
