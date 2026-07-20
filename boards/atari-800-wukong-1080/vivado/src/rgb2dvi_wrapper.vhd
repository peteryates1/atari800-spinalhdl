-- Thin wrapper around Digilent rgb2dvi with fixed generics, so the SpinalHDL
-- top can blackbox it with a plain port interface (no generics to map across
-- languages). Self-generates the 5x TMDS serial clock from PixelClk; active-low
-- reset (aRst_n = MMCM locked).
library ieee;
use ieee.std_logic_1164.all;

entity rgb2dvi_wrapper is
  port (
    PixelClk    : in  std_logic;
    aRst_n      : in  std_logic;
    vid_pData   : in  std_logic_vector(23 downto 0);
    vid_pVDE    : in  std_logic;
    vid_pHSync  : in  std_logic;
    vid_pVSync  : in  std_logic;
    TMDS_Clk_p  : out std_logic;
    TMDS_Clk_n  : out std_logic;
    TMDS_Data_p : out std_logic_vector(2 downto 0);
    TMDS_Data_n : out std_logic_vector(2 downto 0)
  );
end rgb2dvi_wrapper;

architecture rtl of rgb2dvi_wrapper is
begin
  dvi : entity work.rgb2dvi
    generic map (
      kGenerateSerialClk => true,       -- make the 5x serial clock internally
      kClkPrimitive      => "MMCM",
      kClkRange          => 1,          -- pixel clock >= 120 MHz
      kRstActiveHigh     => false       -- use aRst_n (active-low)
    )
    port map (
      TMDS_Clk_p  => TMDS_Clk_p,
      TMDS_Clk_n  => TMDS_Clk_n,
      TMDS_Data_p => TMDS_Data_p,
      TMDS_Data_n => TMDS_Data_n,
      aRst        => '0',
      aRst_n      => aRst_n,
      vid_pData   => vid_pData,
      vid_pVDE    => vid_pVDE,
      vid_pHSync  => vid_pHSync,
      vid_pVSync  => vid_pVSync,
      PixelClk    => PixelClk,
      SerialClk   => '0'                -- unused when kGenerateSerialClk = true
    );
end rtl;
