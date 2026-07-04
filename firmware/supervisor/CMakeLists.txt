cmake_minimum_required(VERSION 3.13)

set(CMAKE_C_STANDARD 11)
set(PICO_BOARD pico CACHE STRING "Board type")

# Pico-PIO-USB (vendored): picked up by TinyUSB's rp2040 family support
set(PICO_PIO_USB_PATH ${CMAKE_CURRENT_LIST_DIR}/lib/Pico-PIO-USB)

include(pico_sdk_import.cmake)
project(supervisor C CXX ASM)
pico_sdk_init()

add_executable(supervisor
    main.c
    usb_descriptors.c
    sd_spi.c
    sd_diskio.c
    lib/fatfs/source/ff.c
    lib/fatfs/source/ffunicode.c
)

target_include_directories(supervisor PRIVATE ${CMAKE_CURRENT_LIST_DIR})

target_link_libraries(supervisor PRIVATE
    pico_stdlib
    hardware_spi
    tinyusb_device
    tinyusb_host
    tinyusb_board
    tinyusb_pico_pio_usb
)

pico_add_extra_outputs(supervisor)
