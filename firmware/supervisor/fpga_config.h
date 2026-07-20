// SD-side FPGA loader: configure the FPGA over JTAG from an .rbf on the SD card,
// no host. See fpga_config.c for the flow.
//
// The SD card is reached through the FPGA's passthrough, so it goes offline
// during reconfiguration — the .rbf therefore can't be streamed from SD while
// configuring, and at 700 KB it won't fit in RAM. So it is first STAGED into a
// reserved region of the RP2040's own flash (done pre-USB, single-core = safe),
// then the config sequence streams it from flash (XIP) into the JTAG TAP.
#ifndef FPGA_CONFIG_H
#define FPGA_CONFIG_H

#include <stdint.h>
#include <stdbool.h>

// Copy an FPGA .rbf from SD into the reserved flash region. Call BEFORE USB /
// core1 start (flash writes require single-core). SD must be mounted. Returns
// bytes staged, or 0 on error / file absent.
uint32_t fpga_stage_rbf(const char *path);

// Stage only if the SD file changed (compares size + mtime against the staged
// flash header — no full re-read, so flash isn't rewritten on every boot).
// Returns the staged length, or 0 if nothing usable. Use this, not the raw
// fpga_stage_rbf, for the boot path.
uint32_t fpga_stage_if_changed(const char *path);

// Length of a valid staged .rbf (verifies header + checksum in flash), else 0.
uint32_t fpga_staged_len(void);

// Configure the FPGA over JTAG from the staged .rbf. Reads flash (XIP) and
// drives GPIO0-3 via blaster_process (no flash writes -> safe with USB running).
// Returns true if CONF_DONE asserted afterwards.
bool fpga_config_from_flash(void);

#ifdef BOARD_WUKONG
// Configure a Xilinx 7-series FPGA (Artix-7) over JTAG by STREAMING a .bit from
// the SD card (the Wukong's SD is local to the Pico, so it stays readable during
// reconfig, and the ~3.8 MB bitstream won't fit flash-staging). Parses the .bit
// header, bit-reverses each config byte (Xilinx MSB-first vs the LSB-first blaster
// shift), runs JPROGRAM/CFG_IN/JSTART. SD must be mounted. Returns true if the
// sequence completed and the TAP is alive afterwards.
bool fpga_config_from_sd(const char *path);
#endif

#endif // FPGA_CONFIG_H
