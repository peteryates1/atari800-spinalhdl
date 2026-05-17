QUARTUS     = /opt/altera/25.1/quartus/bin
QP          = uart_test
SOF_FILE    = output_files/$(QP).sof

SERIAL_PORT ?= /dev/ttyUSB0
BAUD        ?= 115200

.PHONY: build program echo monitor clean

build:
	$(QUARTUS)/quartus_sh --flow compile $(QP)

program: $(SOF_FILE)
	$(QUARTUS)/quartus_pgm -c "USB-Blaster" --mode=JTAG -o "P;$(SOF_FILE)@1"

# Quick non-interactive echo test: send a known string, read what comes back.
# Requires `stty` + a serial echo helper. Uses pyserial via python3 -c so we
# don't depend on `picocom`/`minicom`.
echo:
	@python3 -c "import serial,sys; \
s = serial.Serial('$(SERIAL_PORT)', $(BAUD), timeout=1); \
msg = b'V1.1 loopback ok\\\n'; \
s.write(msg); s.flush(); \
got = s.read(len(msg)); \
print('sent:', msg); print('got :', got); \
sys.exit(0 if got == msg else 1)"

# Interactive monitor — minicom-style. Ctrl-A then Q to quit (picocom).
monitor:
	picocom -b $(BAUD) $(SERIAL_PORT)

clean:
	rm -rf db incremental_db output_files
	rm -f *.qws *.cdf
