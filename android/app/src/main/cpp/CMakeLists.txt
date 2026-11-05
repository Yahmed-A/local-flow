cmake_minimum_required(VERSION 3.22.1)
project(localflow LANGUAGES C CXX)

include(FetchContent)
FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.4.tar.gz)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# fp16 + dot-product instructions: every arm64 phone from ~2018 on; several times faster
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
endif()
set(CMAKE_BUILD_TYPE Release)
FetchContent_MakeAvailable(whisper)

add_library(localflow SHARED jni.cpp)
target_link_libraries(localflow whisper log android)
target_compile_options(localflow PRIVATE -O3)
