cmake_minimum_required(VERSION 3.22.1)
project(logan_private C)

set(CMAKE_C_STANDARD 11)
set(MBED_DIR ${CMAKE_CURRENT_SOURCE_DIR}/mbedtls)
set(CLOGAN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/Clogan)
set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jni)

# ---- 最小 mbedtls：AES-128-CBC 只需要这三个文件（上游全量 mbedcrypto 编不动也没必要）----
add_library(mbedcrypto_mini STATIC
    ${MBED_DIR}/library/aes.c
    ${MBED_DIR}/library/aesni.c
    ${MBED_DIR}/library/padlock.c
    ${MBED_DIR}/library/platform.c)
# mbedtls 2.6.0：AES-CBC 最小闭包 = aes + aesni + padlock + platform（无 platform_util，那是 2.14+）
target_include_directories(mbedcrypto_mini PUBLIC ${MBED_DIR}/include)

# ---- clogan 共享内核（与美团官方 Clogan 逐字节同源，协议零偏移）----
add_library(clogan STATIC
    ${CLOGAN_DIR}/clogan_core.c
    ${CLOGAN_DIR}/construct_data.c
    ${CLOGAN_DIR}/mmap_util.c
    ${CLOGAN_DIR}/zlib_util.c
    ${CLOGAN_DIR}/cJSON.c
    ${CLOGAN_DIR}/json_util.c
    ${CLOGAN_DIR}/aes_util.c
    ${CLOGAN_DIR}/directory_util.c
    ${CLOGAN_DIR}/base_util.c
    ${CLOGAN_DIR}/console_util.c)
target_include_directories(clogan PUBLIC ${CLOGAN_DIR} ${MBED_DIR}/include)
target_link_libraries(clogan mbedcrypto_mini z)

# ---- JNI 桥（官方 clogan_protocol.c 原样）----
add_library(logan SHARED ${JNI_DIR}/clogan_protocol.c)
target_include_directories(logan PRIVATE ${JNI_DIR} ${CLOGAN_DIR})
target_link_libraries(logan clogan z log)

# Android 16KB page size（Pixel 9/Android15+ 强制，官方已修，保留）
target_link_options(logan PRIVATE "-Wl,-z,max-page-size=16384")
