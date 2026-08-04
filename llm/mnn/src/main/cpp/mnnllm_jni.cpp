// MNN LLM JNI 桥接骨架。借鉴 Operit mnnllmnative.cpp。
//
// 注意:这是骨架实现,实际 MNN API 以 MNN 仓库的
// `project/android/apps/MnnLlmApp` 与 `llm/llm.hpp`/`pipeline/llm_pipeline.hpp` 为准。
// 实际编译需用户在 Android Studio 中完成(下载 MNN 源码 + NDK 编译)。

#include <jni.h>
#include <android/log.h>
#include <memory>
#include <string>
#include "llm/llm.hpp"
#include "pipeline/llm_pipeline.hpp"

#define LOG_TAG "MnnLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局 LLM 实例(简化版,后续可扩展为多实例 + 引用计数,见 Operit 多实例管理)
static std::shared_ptr<LLM> g_llm = nullptr;
static std::shared_ptr<Executor> g_executor = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_mnn_MNNLlmNative_nativeInit(
    JNIEnv* env, jclass, jstring configPath) {
    if (g_llm != nullptr) {
        LOGI("MNN LLM 已加载,先释放旧实例");
        g_llm.reset();
        g_executor.reset();
    }
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    if (path == nullptr) return JNI_FALSE;
    std::string config(path);
    env->ReleaseStringUTFChars(configPath, path);

    try {
        g_llm.reset(LLM::createLLM(config));
        g_executor = std::make_shared<Executor>(config);
        g_llm->setExecutor(g_executor.get());
        LOGI("MNN LLM 加载成功: %s", config.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("MNN LLM 加载失败: %s", e.what());
        g_llm.reset();
        g_executor.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_yinyue_mobile_mnn_MNNLlmNative_nativeReset(JNIEnv*, jclass) {
    if (g_llm) g_llm->reset();
    LOGI("MNN KV cache 已重置");
}

JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_mnn_MNNLlmNative_nativeChat(
    JNIEnv* env, jclass, jstring prompt, jint maxTokens) {
    if (!g_llm) return env->NewStringUTF("[错误: MNN 模型未加载]");
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    if (p == nullptr) return env->NewStringUTF("[错误: prompt 为空]");
    std::string input(p);
    env->ReleaseStringUTFChars(prompt, p);

    std::string output;
    try {
        g_llm->response(input, &output, maxTokens > 0 ? maxTokens : 2048);
    } catch (const std::exception& e) {
        LOGE("MNN 推理失败: %s", e.what());
        return env->NewStringUTF(("[推理失败: " + std::string(e.what()) + "]").c_str());
    }
    return env->NewStringUTF(output.c_str());
}

JNIEXPORT void JNICALL
Java_com_yinyue_mobile_mnn_MNNLlmNative_nativeFree(JNIEnv*, jclass) {
    g_llm.reset();
    g_executor.reset();
    LOGI("MNN LLM 已释放");
}

} // extern "C"
