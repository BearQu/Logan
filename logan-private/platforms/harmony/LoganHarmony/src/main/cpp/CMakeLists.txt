cmake_minimum_required(VERSION 3.16)
project(logan_napi C)

# 共享 native 源码树：与 Android logan-core 同一份官方 Clogan + mbedtls 2.6（升级只动一处）
# cpp 目录上溯 6 级 = logan-private 根
get_filename_component(PRIVATE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../" ABSOLUTE)
set(NATIVE_ROOT "${PRIVATE_ROOT}/logan-core/src/main/native")

add_library(mbedcrypto_mini STATIC
    ${NATIVE_ROOT}/mbedtls/library/aes.c
    ${NATIVE_ROOT}/mbedtls/library/aesni.c
    ${NATIVE_ROOT}/mbedtls/library/padlock.c
    ${NATIVE_ROOT}/mbedtls/library/platform.c)
target_include_directories(mbedcrypto_mini PUBLIC ${NATIVE_ROOT}/mbedtls/include)

add_library(clogan_static STATIC
    ${NATIVE_ROOT}/Clogan/clogan_core.c
    ${NATIVE_ROOT}/Clogan/construct_data.c
    ${NATIVE_ROOT}/Clogan/mmap_util.c
    ${NATIVE_ROOT}/Clogan/zlib_util.c
    ${NATIVE_ROOT}/Clogan/cJSON.c
    ${NATIVE_ROOT}/Clogan/json_util.c
    ${NATIVE_ROOT}/Clogan/aes_util.c
    ${NATIVE_ROOT}/Clogan/directory_util.c
    ${NATIVE_ROOT}/Clogan/base_util.c
    ${NATIVE_ROOT}/Clogan/console_util.c)
target_include_directories(clogan_static PUBLIC ${NATIVE_ROOT}/Clogan ${NATIVE_ROOT}/mbedtls/include)
target_link_libraries(clogan_static mbedcrypto_mini z)

add_library(logan_napi SHARED logan_napi.c)
target_link_libraries(logan_napi clogan_static mbedcrypto_mini libace_napi.z.so libz.so)
target_compile_options(logan_napi PRIVATE -fvisibility=hidden)
