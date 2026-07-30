cmake_minimum_required(VERSION 3.22.1)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

project("eye_native")

# VAD 已迁移至 Silero VAD (sherpa-onnx), 移除 WebRTC VAD C++ 代码
# 仅保留 audio_processor 相关模块
add_library(
    eye_native
    SHARED
    native-lib.cpp
    audio/audio_processor.c
    audio/audio_processor_jni.cpp
)

target_include_directories(eye_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
)

set_source_files_properties(audio/audio_processor.c PROPERTIES LANGUAGE C)

find_library(log-lib log)

target_link_libraries(
    eye_native
    ${log-lib}
    android
)
