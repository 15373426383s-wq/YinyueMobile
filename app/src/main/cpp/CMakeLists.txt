cmake_minimum_required(VERSION 3.22.1)
project("silvermoon_jni")

# ── 公共路径 ──
set(PROJECT_ROOT ${CMAKE_SOURCE_DIR}/../../../../)
set(JNILIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# ═══════════════════════════════════════════════════════
# Target 1: llama_jni（llama.cpp 后端，保留兼容）
# ═══════════════════════════════════════════════════════
set(LLAMA_DIR ${PROJECT_ROOT}/llama.cpp)

set(LLAMA_LIBS ggml ggml-base ggml-cpu llama mtmd)
foreach(lib ${LLAMA_LIBS})
    add_library(${lib} SHARED IMPORTED GLOBAL)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION ${JNILIBS_DIR}/lib${lib}.so
    )
endforeach()

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
    ${LLAMA_DIR}/tools/mtmd
)

target_link_libraries(llama_jni
    mtmd llama ggml-cpu ggml-base ggml
    android log
)

# ═══════════════════════════════════════════════════════
# Target 2: mnn_llm_jni（MNN + OpenCL 后端）
# ═══════════════════════════════════════════════════════
set(MNN_DIR ${PROJECT_ROOT}/MNN)

# MNN 核心库（由 build_mnn.ps1 预编译→jniLibs）
add_library(MNN SHARED IMPORTED GLOBAL)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/libMNN.so
)

# LLM Transformer 引擎库（由 build_mnn.ps1 预编译→jniLibs）
add_library(llm SHARED IMPORTED GLOBAL)
set_target_properties(llm PROPERTIES
    IMPORTED_LOCATION ${JNILIBS_DIR}/libllm.so
)

add_library(mnn_llm_jni SHARED mnn_llm_jni.cpp)

target_include_directories(mnn_llm_jni PRIVATE
    ${MNN_DIR}/include
    ${MNN_DIR}/transformers/llm/engine/include
    ${MNN_DIR}/3rd_party
)

target_link_libraries(mnn_llm_jni
    llm
    MNN
    android
    log
)
