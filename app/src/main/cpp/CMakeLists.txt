cmake_minimum_required(VERSION 3.22.1)
project("mymedia")

# ── FFmpeg 路径自动探测 ──
# 优先用项目内 FFmpeg编译/output/{架构}（随项目打包，解压即用）；
# 否则读环境变量 FFMPEG_PREFIX；都没有则报错。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(FFMPEG_ARCH_DIR "arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(FFMPEG_ARCH_DIR "armv7a")
else()
    set(FFMPEG_ARCH_DIR "x86_64")
endif()

set(PROJECT_FFMPEG "${CMAKE_CURRENT_LIST_DIR}/../../../../FFmpeg编译/output/${FFMPEG_ARCH_DIR}")
if(EXISTS "${PROJECT_FFMPEG}/lib/libavcodec.so")
    set(FFMPEG_PREFIX "${PROJECT_FFMPEG}")
elseif(DEFINED ENV{FFMPEG_PREFIX})
    set(FFMPEG_PREFIX "$ENV{FFMPEG_PREFIX}")
else()
    message(FATAL_ERROR "找不到 FFmpeg 库目录，请设置环境变量 FFMPEG_PREFIX 指向 FFmpeg编译/output/{arm64|armv7a}")
endif()
message(STATUS "FFMPEG_PREFIX = ${FFMPEG_PREFIX}")

# Include directories
include_directories(${FFMPEG_PREFIX}/include)

# FFmpeg shared libraries
add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libavformat.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libswresample.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES
    IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libswscale.so)

# avfilter（可选）：存在则启用 atempo 变速（音调不变），否则回退丢样本变速。
# 启用方法：FFmpeg 编译时 --enable-avfilter --enable-filter=atempo
if(EXISTS "${FFMPEG_PREFIX}/lib/libavfilter.so")
    add_library(avfilter SHARED IMPORTED)
    set_target_properties(avfilter PROPERTIES
        IMPORTED_LOCATION ${FFMPEG_PREFIX}/lib/libavfilter.so)
    set(HAVE_AVFILTER TRUE)
endif()

# JNI bridge library
add_library(mymedia SHARED ffmpeg_jni.c)

target_link_libraries(mymedia
    avcodec
    avformat
    avutil
    swresample
    swscale
    log
    z
)

if(HAVE_AVFILTER)
    target_link_libraries(mymedia avfilter)
    target_compile_definitions(mymedia PRIVATE HAVE_AVFILTER=1)
    message(STATUS "avfilter ENABLED: atempo 变速不变调")
else()
    message(STATUS "avfilter NOT FOUND: 变速回退丢样本（可能变调）")
endif()
