cmake_minimum_required(VERSION 3.22.1)

project(yingling_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只编译库，不编译 whisper.cpp 自带的示例/测试/服务
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(yingling_whisper SHARED whisper_jni.cpp)
target_compile_features(yingling_whisper PRIVATE cxx_std_17)
target_link_libraries(yingling_whisper whisper android log)
