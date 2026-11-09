cmake_minimum_required(VERSION 3.22.1)

project(yingling_llama C CXX)

# AGP 的 debug 变体默认用 -O0；LLM 推理必须开优化。
set(CMAKE_C_FLAGS_DEBUG "-g -O3")
set(CMAKE_CXX_FLAGS_DEBUG "-g -O3")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ARMv8.2 dotprod/fp16：Q4 量化矩阵乘在 A76/A55 上明显更快
set(ARM_FLAGS "-march=armv8.2-a+dotprod+fp16")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${ARM_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ARM_FLAGS}")

# 只编译 llama 库
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(yingling_llama SHARED llama_jni.cpp)
target_compile_features(yingling_llama PRIVATE cxx_std_17)
target_link_libraries(yingling_llama llama android log)
