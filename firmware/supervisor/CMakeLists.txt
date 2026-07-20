cmake_minimum_required(VERSION 3.13)

set(CMAKE_C_STANDARD 11)
set(PICO_BOARD pico CACHE STRING "Board type")

# Target base board, selected on the CLI:  cmake -DBOARD=colorlight ..  (default)
#                                           cmake -DBOARD=qmtech ..
# Maps to a BOARD_<UPPER> compile define used by the RP2040 pin map in
# sd_spi.c / main.c / blaster.c / jtag.c (colorlight = the i5/i9+ base board;
# qmtech = the older QMTech / SD-direct pinout via the #else branches).
set(BOARD colorlight CACHE STRING "Target base board: colorlight | qmtech")
string(TOUPPER "${BOARD}" BOARD_UC)
add_compile_definitions(BOARD_${BOARD_UC})
message(STATUS "Supervisor target board: ${BOARD}  (-DBOARD_${BOARD_UC})")

# Pico-PIO-USB (vendored): picked up by TinyUSB's rp2040 family support
set(PICO_PIO_USB_PATH ${CMAKE_CURRENT_LIST_DIR}/lib/Pico-PIO-USB)

include(pico_sdk_import.cmake)
project(supervisor C CXX ASM)
pico_sdk_init()

add_executable(supervisor
    main.c
    config.c
    sio.c
    jtag.c
    blaster.c
    msc_disk.c
    fpga_config.c
    rm2.c
    fbtext.c
    supervisor.c
    usb_descriptors.c
    sd_spi.c
    sd_diskio.c
    lib/fatfs/source/ff.c
    lib/fatfs/source/ffunicode.c
)

target_include_directories(supervisor PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/lib/fatfs/source
)

# JTAG shift PIO program (USB-Blaster fast path)
pico_generate_pio_header(supervisor ${CMAKE_CURRENT_LIST_DIR}/blaster_jtag.pio)

target_link_libraries(supervisor PRIVATE
    pico_stdlib
    hardware_spi
    hardware_pio
    hardware_flash
    tinyusb_device
    tinyusb_host
    tinyusb_board
    tinyusb_pico_pio_usb
)

# WiFi (Pico W / Pico 2 W only): on-demand HTTP SD manager. cyw43_arch in poll
# mode fits the single-core cooperative loop; lwIP config is lwipopts.h.
if(PICO_BOARD MATCHES "_w$")
    message(STATUS "Supervisor: WiFi enabled for ${PICO_BOARD}")
    target_sources(supervisor PRIVATE wifi.c httpsrv.c)
    target_link_libraries(supervisor PRIVATE pico_cyw43_arch_lwip_poll)
    target_compile_definitions(supervisor PRIVATE HAVE_WIFI=1)
endif()

pico_add_extra_outputs(supervisor)
