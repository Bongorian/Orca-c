cmake_minimum_required(VERSION 3.22.1)
project(orcacore C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(orcacore SHARED
    native_bridge.c
    ${ORCA_C_ROOT}/field.c
    ${ORCA_C_ROOT}/gbuffer.c
    ${ORCA_C_ROOT}/sim.c
    ${ORCA_C_ROOT}/vmio.c
)

target_include_directories(orcacore PRIVATE ${ORCA_C_ROOT})
target_compile_definitions(orcacore PRIVATE _POSIX_C_SOURCE=200809L)
target_compile_options(orcacore PRIVATE -Wall -Wextra -Wno-unused-parameter)
