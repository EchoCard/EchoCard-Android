cmake_minimum_required(VERSION 3.22.1)
project(callmate_opus_jni LANGUAGES C CXX)

set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/opus EXCLUDE_FROM_ALL)

# AGP Debug 常为无优化；libopus 会 #pragma 警告且实时编解码极慢
if(TARGET opus)
  target_compile_options(opus PRIVATE
    $<$<COMPILE_LANGUAGE:C>:-O2>
    $<$<COMPILE_LANGUAGE:CXX>:-O2>)
endif()

# libsbc（Google Apache-2.0）：只用到 mSBC 编码一条链路，和 iOS ThirdParty/libsbc 同源。
add_library(callmate_sbc STATIC
  third_party/libsbc/src/sbc.c
  third_party/libsbc/src/bits.c)
target_include_directories(callmate_sbc PUBLIC
  third_party/libsbc/include
  third_party/libsbc/src)
target_compile_options(callmate_sbc PRIVATE -O2 -Wno-unused-parameter)

add_library(callmate_opus SHARED opus_jni.cpp msbc_jni.c)
target_link_libraries(callmate_opus PRIVATE opus callmate_sbc)
target_compile_features(callmate_opus PRIVATE cxx_std_17)
