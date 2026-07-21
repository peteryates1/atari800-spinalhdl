// Low-level RP2040 <-> FPGA SPI primitives shared across modules.
// Implemented in main.c (where the SPI port + fpga_spi_frame live).
#ifndef FPGA_LINK_H
#define FPGA_LINK_H

#include <stdint.h>

// SioBridge register access over the SPI link.
//   'Q' (0x51): write reg  -> {0x51, addr, dataLo, dataHi}
//   'S' (0x53): read reg   -> {0x53, addr} triggers a one-cycle bus read and
//               latches the 16-bit result; a following status frame returns it
//               in MISO bytes 25/26. Reads of addr 1 pop the RX FIFO; reads of
//               addr 0 clear the sticky STATUS bits.
//
// SioBridge register map (see SioBridge.scala):
//   0 STATUS  : b0 COMMAND(live, low in frame), b1 RX_READY, b2 TX_BUSY,
//               b3 FRAME_ERR(sticky), b4 CMD_EDGE(sticky)
//   0 CTRL(W) : b0 TX_ENABLE
//   1 RX_DATA : [7:0] byte (pops FIFO), [15:8] cmd byte index
//   2 TX_DATA(W)
//   3 TX_STATUS: b0 EMPTY, b1 FULL, [9:2] count
//   4 BAUD_DIV(W)
//   5 RX_STATUS: b0 EMPTY, b1 FULL, [9:2] count
void     fpga_sio_write(uint8_t addr, uint16_t data);
uint16_t fpga_sio_read(uint8_t addr);

#endif // FPGA_LINK_H
