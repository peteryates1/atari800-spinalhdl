QUARTUS     = /opt/altera/25.1/quartus/bin
QP          = uart_test
SOF_FILE    = output_files/$(QP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        ?= 115200

.PHONY: build program echo clean

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

echo:
	@python3 -c "import serial,sys; \
s = serial.Serial('$(SERIAL_PORT)', $(BAUD), timeout=1); \
msg = b'V1.1 10CL025 loopback ok\\\n'; \
s.write(msg); s.flush(); \
got = s.read(len(msg)); \
print('sent:', msg); print('got :', got); \
sys.exit(0 if got == msg else 1)"

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
