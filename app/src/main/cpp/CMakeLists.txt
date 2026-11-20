# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(wprint CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BRLASER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/brlaser)

add_library(wprint SHARED
        bridge.cpp
        brcore.cpp
        ${BRLASER_DIR}/job.cc
        ${BRLASER_DIR}/line.cc
)

target_include_directories(wprint PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${BRLASER_DIR}
)

# brlaser 的三个 .cc 原样编译，只关掉它在新 clang 下会刷屏的告警，
# 不做任何代码层面的"顺手优化"——字节一致性优先于整洁。
target_compile_options(wprint PRIVATE
        -Wall -Wextra
        -Wno-unused-parameter
        -Wno-sign-compare
)

find_library(log-lib log)
target_link_libraries(wprint ${log-lib})
