// Raspberry Pi Radio Module 2 (CYW43439) connectivity smoke test.
//
// The RM2 hangs off the FPGA and is reached by the RP2040 through the FPGA's
// combinational SPI passthrough (Atari800Rp2040HdmiLgTop):
//   RP2040 GPIO20=SCK, 21=MOSI, 22<-MISO, 23=CS, 5=WIFI_ON, 4=BT_ON, 24<-IRQ_n.
// This bit-bangs the chip's gSPI just enough to read its test register, which a
// healthy CYW43439 returns as 0xFEEDBEAD — proving the RP2040 <-> FPGA <-> RM2
// path and that the chip powers up. Real WiFi/BT is a separate driver effort.
#ifndef RM2_H
#define RM2_H

#include <stdint.h>
#include <stdbool.h>

// Power the chip (WIFI_ON), then read the gSPI test register. Returns true if it
// reads back 0xFEEDBEAD; *out_val (if non-NULL) gets the last value read.
bool rm2_smoke_test(uint32_t *out_val);

#endif // RM2_H
