cmake_minimum_required(VERSION 3.22.1)
project("mnn_jni")

# 借鉴 Operit:用 FetchContent + archive tarball 替代 submodule,可复现构建
# 注意:MNN 仓库较大,首次构建会下载源码并编译,需在 Android Studio 中联网执行
include(FetchContent)
set(MNN_GIT_REF "master" CACHE STRING "MNN git ref")
set(LIB_NAME "MnnLlmWrapper")

# FetchContent_Declare 下载 MNN 源码(实际构建时由 NDK 拉取)
FetchContent_Declare(
    mnn
    GIT_REPOSITORY https://github.com/alibaba/MNN.git
    GIT_TAG ${MNN_GIT_REF}
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(mnn)

# MNN 构建选项(借鉴 Operit)
set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "" FORCE)

# JNI 桥接库
add_library(${LIB_NAME} SHARED
    mnnllm_jni.cpp
)
target_link_libraries(${LIB_NAME}
    mnn
    llm
    log
    android
)
