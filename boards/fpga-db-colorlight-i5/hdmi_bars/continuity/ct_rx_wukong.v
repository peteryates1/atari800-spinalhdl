// HDMI continuity test, RECEIVE end: QMTECH Wukong (XC7A100T), HDMI connector
// as INPUTS. Counts rising edges on each of the 8 TMDS conductors over a
// 0.1 s gate and prints, at 115200 8N1 on the CH340 console (E3):
//   "C 0261A8 01312D 00989A 0042C4 00262C 001316 00098A 0004C6\r\n"
// in conductor order D0+ D0- D1+ D1- D2+ D2- CLK+ CLK- (hex). Expected from
// ct_tx_i9plus: 0x02625A, 0x01312D, ... halving each step.
module ct_rx_wukong (
    input  wire       clk50,       // M21
    input  wire [7:0] line,
    output wire       uart_tx
);
    localparam GATE = 5_000_000;   // 0.1 s at 50 MHz
    localparam DIV  = 434;         // 50e6 / 115200

    reg [7:0]  s1 = 0, s2 = 0, s3 = 0;
    always @(posedge clk50) begin s1 <= line; s2 <= s1; s3 <= s2; end
    wire [7:0] rise = s2 & ~s3;

    reg [22:0] gate = 0;
    reg [23:0] cnt [0:7];
    reg [23:0] snap [0:7];
    reg        go = 0;
    integer k;
    initial for (k = 0; k < 8; k = k + 1) begin cnt[k] = 0; snap[k] = 0; end
    always @(posedge clk50) begin
        go <= 0;
        if (gate == GATE - 1) begin
            gate <= 0; go <= 1;
            for (k = 0; k < 8; k = k + 1) begin snap[k] <= cnt[k]; cnt[k] <= {23'd0, rise[k]}; end
        end else begin
            gate <= gate + 1;
            for (k = 0; k < 8; k = k + 1) cnt[k] <= cnt[k] + rise[k];
        end
    end

    // Message: "C" then 8 x (" " + 6 hex) then CR LF = 1 + 56 + 2 = 59 chars.
    function [7:0] hex(input [3:0] n);
        hex = (n < 10) ? 8'h30 + n : 8'h41 + n - 10;
    endfunction
    reg [5:0] ci = 0;
    reg       busy = 0;
    reg [7:0] ch;
    wire [2:0] w = (ci - 1) / 7;
    wire [2:0] p = (ci - 1) % 7;
    always @* begin
        if (ci == 0) ch = "C";
        else if (ci == 57) ch = 8'h0D;
        else if (ci == 58) ch = 8'h0A;
        else if (p == 0) ch = " ";
        else ch = hex(snap[w] >> (4 * (6 - p)));
    end

    reg [9:0] baud = 0;
    reg [3:0] bitn = 0;
    reg [9:0] sh = 10'h3FF;
    always @(posedge clk50) begin
        if (go && !busy) begin busy <= 1; ci <= 0; bitn <= 0; baud <= 0; end
        else if (busy) begin
            if (bitn == 0 && baud == 0) begin sh <= {1'b1, ch, 1'b0}; bitn <= 10; baud <= DIV - 1; end
            else if (baud != 0) baud <= baud - 1;
            else begin
                sh <= {1'b1, sh[9:1]};
                if (bitn == 1) begin
                    bitn <= 0;
                    if (ci == 58) busy <= 0; else ci <= ci + 1;
                end else begin bitn <= bitn - 1; baud <= DIV - 1; end
            end
        end
    end
    assign uart_tx = busy ? sh[0] : 1'b1;
endmodule
