# ===========================================================================
# SDC Timing Constraints for Atari 800 + JOP fit check
# ===========================================================================
# PLL stub passes clk50 through as c0/c1/c2, so all logic runs on clk50.
# Constrain at 56.67 MHz (17.646 ns) — the real system clock frequency.

create_clock -name clk50 -period 17.646 [get_ports {clk50}]

derive_clock_uncertainty

# --------------------------------------------------------------------------
# SDRAM I/O timing
# --------------------------------------------------------------------------
# sdram_clk is a direct output of clk50 (through PLL stub passthrough)
create_generated_clock -name sdram_clk_ext -source [get_ports {clk50}] \
    [get_ports {sdram_clk}]

# Output delays: W9825G6KH tDS=1.5ns setup, tDH=0.8ns hold
set_output_delay -clock sdram_clk_ext -max 1.5 \
    [get_ports {sdram_addr[*] sdram_ba[*] sdram_dq[*] sdram_cs_n sdram_ras_n sdram_cas_n sdram_we_n sdram_dqml sdram_dqmh}]
set_output_delay -clock sdram_clk_ext -min -0.8 \
    [get_ports {sdram_addr[*] sdram_ba[*] sdram_dq[*] sdram_cs_n sdram_ras_n sdram_cas_n sdram_we_n sdram_dqml sdram_dqmh}]

# Input delays: W9825G6KH tAC=6.0ns max, tOH=2.5ns min
set_input_delay -clock sdram_clk_ext -max 6.0 [get_ports {sdram_dq[*]}]
set_input_delay -clock sdram_clk_ext -min 2.5 [get_ports {sdram_dq[*]}]

# --------------------------------------------------------------------------
# Asynchronous / slow I/O — false paths
# --------------------------------------------------------------------------
set_false_path -from [get_ports {joy1[*] joy2[*] joy3[*] joy4[*] cart_data[*] cart_rd4 cart_rd5 spi_miso uart_rx}]
set_false_path -to [get_ports {vga_r[*] vga_g[*] vga_b[*] vga_hsync vga_vsync}]
set_false_path -to [get_ports {audio_l audio_r}]
set_false_path -to [get_ports {cart_addr[*] cart_s4_n cart_s5_n cart_cctl_n cart_phi2}]
set_false_path -to [get_ports {spi_sclk spi_mosi spi_cs0 spi_cs1}]
set_false_path -to [get_ports {uart_tx}]
set_false_path -to [get_ports {hdmi_d0p hdmi_d0n hdmi_d1p hdmi_d1n hdmi_d2p hdmi_d2n hdmi_clkp hdmi_clkn}]
set_false_path -to [get_ports {led[*]}]
set_false_path -to [get_ports {jop_*}]
