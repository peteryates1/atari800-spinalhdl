#ifndef _LWIPOPTS_H
#define _LWIPOPTS_H

// Base config shared by the pico-examples wifi samples (NO_SYS, DHCP, TCP/UDP, etc.)
#include "lwipopts_examples_common.h"

// We ping with the lwIP RAW API (ICMP echo), so make sure it's enabled.
#ifndef LWIP_RAW
#define LWIP_RAW 1
#endif

#endif
