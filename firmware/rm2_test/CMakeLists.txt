cmake_minimum_required(VERSION 3.13)

include(pico_sdk_import.cmake)

project(rm2_test C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(rm2_test main.c)

# Stamp-direct RM2 pin map (override the pico_w defaults, which are #ifndef-guarded).
# Single bidirectional DATA line -> DATA_OUT == DATA_IN == HOST_WAKE = 24.
target_compile_definitions(rm2_test PRIVATE
    CYW43_DEFAULT_PIN_WL_REG_ON=22
    CYW43_DEFAULT_PIN_WL_DATA_OUT=24
    CYW43_DEFAULT_PIN_WL_DATA_IN=24
    CYW43_DEFAULT_PIN_WL_HOST_WAKE=24
    CYW43_DEFAULT_PIN_WL_CLOCK=25
    CYW43_DEFAULT_PIN_WL_CS=23
)

target_link_libraries(rm2_test pico_stdlib pico_cyw43_arch_none)

pico_add_extra_outputs(rm2_test)

# ---- Wi-Fi connectivity test (associate + DHCP + ICMP ping) ----
add_executable(wifi_test wifi_test.c)
target_compile_definitions(wifi_test PRIVATE
    CYW43_DEFAULT_PIN_WL_REG_ON=22
    CYW43_DEFAULT_PIN_WL_DATA_OUT=24
    CYW43_DEFAULT_PIN_WL_DATA_IN=24
    CYW43_DEFAULT_PIN_WL_HOST_WAKE=24
    CYW43_DEFAULT_PIN_WL_CLOCK=25
    CYW43_DEFAULT_PIN_WL_CS=23
    CYW43_PIO_CLOCK_DIV_INT=16   # slow gSPI (sysclk/16 ~7.8MHz) for the direct-wired RM2 jumper leads
)
target_include_directories(wifi_test PRIVATE ${CMAKE_CURRENT_LIST_DIR})   # lwipopts.h + wifi_creds.h
target_link_libraries(wifi_test
    pico_stdlib
    pico_cyw43_arch_lwip_threadsafe_background
)
pico_add_extra_outputs(wifi_test)
