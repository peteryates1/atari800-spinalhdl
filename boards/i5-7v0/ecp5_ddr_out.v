// ECP5 DDR output primitive for TMDS serialisation — the ECP5 equivalent of the
// Cyclone's ALTDDIO_OUT (see DvidOut.scala). One ODDRX1F per channel: datain_h
// is driven on the rising SCLK edge, datain_l on the falling edge (x1 gearing,
// 2 bits per SCLK). SCLK = the TMDS clock (5x pixel); over 5 SCLK cycles this
// emits the 10 serialised bits per pixel.
//
// Output is single-ended here; make the pin a true LVDS/LVCMOS33D pair in the
// .lpf and the tools drive the complement automatically (pseudo-differential
// HDMI, the standard ECP5 approach). For 640x480 SCLK is 125 MHz — comfortably
// within ODDRX1F. At 720p (371 MHz) / 1080p (742 MHz) this will need ECLK gearing
// (ECLKSYNCB + CLKDIVF or ODDRX2F) — a later refinement.
module ecp5_ddr_out #(parameter WIDTH = 4) (
    input  wire [WIDTH-1:0] datain_h,   // rising-edge bits
    input  wire [WIDTH-1:0] datain_l,   // falling-edge bits
    input  wire             outclock,   // TMDS (5x pixel) clock
    output wire [WIDTH-1:0] dataout
);
    genvar i;
    generate
        for (i = 0; i < WIDTH; i = i + 1) begin : ddr
            ODDRX1F oddr (
                .D0   (datain_h[i]),
                .D1   (datain_l[i]),
                .SCLK (outclock),
                .RST  (1'b0),
                .Q    (dataout[i])
            );
        end
    endgenerate
endmodule
