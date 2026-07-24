package retro

/** FPGA side of the RP2040 supervisor link.
  *
  * Reserved layer — currently empty. Today all supervisor-facing logic is still
  * Atari-fused in `retro.machines.atari` (SioBridge = Atari SIO disk emulation,
  * RpAtariKeyboard = Atari keyboard/control/loader). The generic, machine-neutral
  * pieces (sector server, ioctl, the `hps_ext` framer — see
  * docs/archimedes-hps-shim.md) get extracted here during the Archimedes port,
  * at which point machine cores depend on `retro.link` rather than the reverse.
  */
package object link
