cmake_minimum_required(VERSION 3.13)

set(PICO_SDK_PATH $ENV{PICO_SDK_PATH})
include(${PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(pico_kbd_test C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(pico_kbd_test
    main.c
    usb_descriptors.c
)

target_include_directories(pico_kbd_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(pico_kbd_test
    pico_stdlib
    tinyusb_device
    tinyusb_board
)

# UART on GPIO0/1 for debug probe communication
pico_enable_stdio_uart(pico_kbd_test 1)
pico_enable_stdio_usb(pico_kbd_test 0)

pico_add_extra_outputs(pico_kbd_test)
