# QMTECH 10CL025 on ATARI-800-LG-V1: Pin Limitations

The QMTECH 10CL025 core board has four dedicated clock input pins at
U7 connector positions 39-42 (FPGA pins A8, A9, B8, B9). These pins
have restrictions compared to the EP4CGX150 which has general-purpose
I/O at the same connector positions.

## Clock pin mapping

| U7 pos | 10CL025 pin | EP4CGX150 pin | Signal          | Issue                           |
|--------|-------------|---------------|-----------------|---------------------------------|
| 39     | A8 (CLK10)  | AC14          | io_ledBase[3]   | Input-only -- cannot drive LED  |
| 40     | B8 (CLK11)  | AD14          | io_consolOption | No internal weak pull-up        |
| 41     | A9 (CLK8)   | AF11          | io_joy1Right    | No internal weak pull-up        |
| 42     | B9 (CLK9)   | AF12          | io_consolReset  | No internal weak pull-up        |

## What was done in the QSF

- **io_ledBase[3]**: Reassigned from A8 to C14 (U8 pin 48, unconnected
  on base board). The LED on the base board at U7.2 position 39 is not
  usable with this core board.

- **io_consolOption (B8), io_joy1Right (A9), io_consolReset (B9)**:
  Internal weak pull-up assignments removed. These signals are active-low
  and need pull-ups to idle high when the switch/joystick is not pressed.

## Hardware changes needed

Three signals require **external 10K pull-up resistors to 3.3V** on the
base board (or a daughter board / bodge):

| Signal          | FPGA pin | Function            |
|-----------------|----------|---------------------|
| io_consolOption | B8       | OPTION console key  |
| io_joy1Right    | A9       | Joystick 1 right    |
| io_consolReset  | B9       | System reset button  |

Without these pull-ups the inputs will float, causing:
- OPTION held low at boot (Atari 800 disables BASIC when OPTION is held)
- Joystick 1 may read phantom right-press
- Reset line may be unstable

**For a board revision:** add 10K pull-ups on these three net traces,
or add a resistor network near the U7.2 connector.

## Resource utilisation (25 Apr 2026)

| Resource                | Used      | Available | %    |
|-------------------------|-----------|-----------|------|
| Logic elements          | 8,561     | 24,624    | 35%  |
| Memory bits (M9K)       | 504,336   | 608,256   | 83%  |
| M9K blocks              | 62        | 66        | 94%  |
| Pins                    | 48        | 151       | 32%  |
| PLLs                    | 1         | 4         | 25%  |
| Embedded multipliers    | 2         | 132       | 2%   |

### M9K block breakdown

| Block                | Size   | M9K blocks | Purpose                      |
|----------------------|--------|------------|------------------------------|
| Internal RAM         | 40K    | 40         | 0000-9FFF                    |
| Cartridge ROM        | 8K     | 8          | A000-BFFF (Star Raiders)     |
| OS ROM (atarios2)    | 2K     | 2          | D800-DFFF                    |
| OS ROM (atariosb)    | 8K     | 8          | E000-FFFF                    |
| Scandoubler line A   | 1825B  | 2          | VGA line doubler             |
| Scandoubler line B   | 1825B  | 2          | VGA line doubler             |
| **Total**            |        | **62**     | **4 spare**                  |

Timing: worst-case setup slack +11.2 ns at 56.67 MHz (very comfortable).
