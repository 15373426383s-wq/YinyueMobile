//
// mnn_llm_jni.cpp — SilverMoon MNN-based LLM inference engine
// 替代 llama.cpp JNI，基于 MNN + OpenCL（天玑 APU 加速）
//
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <iostream>
#include <sstream>
#include <mutex>
#include "llm/llm.hpp"

#define TAG "MnnLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;
using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;

// ── Global state ──
static Llm*        g_llm    = nullptr;
static LlmContext*  g_ctx    = nullptr;
static JavaVM*      g_jvm    = nullptr;
static jclass       g_callback_class = nullptr;
static jmethodID    g_on_token_method = nullptr;

// ── Streaming callback ostream ──
// 每 token 输出时通过 JNI 回调 Kotlin 侧
class TokenStreamBuf : public std::streambuf {
public:
    JNIEnv* env;
    jobject callback;

    TokenStreamBuf(JNIEnv* e, jobject cb) : env(e), callback(cb) {}

protected:
    virtual std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (!callback || n <= 0) return n;
        JNIEnv* cb_env = nullptr;
        bool need_detach = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&cb_env), JNI_VERSION_1_4) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&cb_env, nullptr) != JNI_OK) return n;
            need_detach = true;
        }
        jstring jtext = cb_env->NewStringUTF(std::string(s, n).c_str());
        jboolean stop = cb_env->CallBooleanMethod(callback, g_on_token_method, jtext);
        cb_env->DeleteLocalRef(jtext);
        if (need_detach) g_jvm->DetachCurrentThread();
        return stop ? 0 : n;
    }

    virtual int overflow(int c) override {
        if (!callback) return c;
        JNIEnv* cb_env = nullptr;
        bool need_detach = false;
        if (g_jvm->GetEnv(reinterpret_cast<void**>(&cb_env), JNI_VERSION_1_4) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&cb_env, nullptr) != JNI_OK) return c;
            need_detach = true;
        }
        char ch = static_cast<char>(c);
        jstring jtext = cb_env->NewStringUTF(&ch); // single char
        jboolean stop = cb_env->CallBooleanMethod(callback, g_on_token_method, jtext);
        cb_env->DeleteLocalRef(jtext);
        if (need_detach) g_jvm->DetachCurrentThread();
        return stop ? EOF : c;
    }
};

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4) != JNI_OK) {
        return JNI_VERSION_1_4;
    }
    jclass local = env->FindClass("com/yinyue/mobile/agent/MnnStreamCallback");
    if (local) {
        g_callback_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
        g_on_token_method = env->GetMethodID(g_callback_class, "onToken", "(Ljava/lang/String;)Z");
    }
    env->DeleteLocalRef(local);
    LOGI("JNI_OnLoad OK");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4) == JNI_OK && g_callback_class) {
        env->DeleteGlobalRef(g_callback_class);
    }
    g_jvm = nullptr;
    g_callback_class = nullptr;
    g_on_token_method = nullptr;
}

// ── Init: 从 MNN config JSON 文件加载模型 ──
// configPath 示例: /data/data/com.yinyue.mobile/files/mnn_config.json
JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_agent_MnnNative_init(
    JNIEnv* env, jobject, jstring configPath) {

    if (g_llm) {
        Llm::destroy(g_llm);
        g_llm = nullptr;
        g_ctx = nullptr;
    }

    const char* cp = env->GetStringUTFChars(configPath, nullptr);
    std::string path(cp);
    env->ReleaseStringUTFChars(configPath, cp);

    LOGI("Loading LLM from config: %s", path.c_str());
    g_llm = Llm::createLLM(path);
    if (!g_llm) {
        LOGE("createLLM returned null");
        return false;
    }

    bool ok = g_llm->load();
    if (!ok) {
        LOGE("load() failed");
        Llm::destroy(g_llm);
        g_llm = nullptr;
        return false;
    }

    g_ctx = const_cast<LlmContext*>(g_llm->getContext());
    LOGI("Model loaded OK, ctx=%d", g_llm->getContext() ? g_llm->getContext()->prompt_len : 0);
    return true;
}

// ── chat: 非流式对话（兼容旧 agent 循环） ──
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_MnnNative_chat(
    JNIEnv* env, jobject, jobjectArray roles, jobjectArray contents, jint maxTokens) {

    if (!g_llm) {
        return env->NewStringUTF("[ERROR] 模型未加载");
    }

    jsize n_roles = env->GetArrayLength(roles);
    jsize n_contents = env->GetArrayLength(contents);
    jsize n = n_roles < n_contents ? n_roles : n_contents;

    ChatMessages msgs;
    std::vector<std::string> holders;
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(roles, i);
        jstring c = (jstring)env->GetObjectArrayElement(contents, i);
        std::string role, content;
        if (r) {
            const char* rs = env->GetStringUTFChars(r, nullptr);
            if (rs) { role = rs; env->ReleaseStringUTFChars(r, rs); }
            env->DeleteLocalRef(r);
        }
        if (c) {
            const char* cs = env->GetStringUTFChars(c, nullptr);
            if (cs) { content = cs; env->ReleaseStringUTFChars(c, cs); }
            env->DeleteLocalRef(c);
        }
        holders.push_back(role);
        holders.push_back(content);
    }
    for (jsize i = 0; i < n; i++) {
        msgs.push_back({holders[i*2], holders[i*2+1]});
    }

    // 用 stringstream 收集输出 → 兼容旧阻塞式调用
    std::ostringstream oss;
    int mt = maxTokens > 0 ? maxTokens : 256;
    g_llm->response(msgs, &oss, nullptr, mt);

    std::string result = oss.str();
    return env->NewStringUTF(result.c_str());
}

// ── chatStream: 流式对话（Flow-based） ──
JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_agent_MnnNative_chatStream(
    JNIEnv* env, jobject, jobjectArray roles, jobjectArray contents,
    jint maxTokens, jobject callback) {

    if (!g_llm) {
        LOGE("chatStream: model not loaded");
        return false;
    }

    jsize n_roles = env->GetArrayLength(roles);
    jsize n_contents = env->GetArrayLength(contents);
    jsize n = n_roles < n_contents ? n_roles : n_contents;

    ChatMessages msgs;
    std::vector<std::string> holders;
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring)env->GetObjectArrayElement(roles, i);
        jstring c = (jstring)env->GetObjectArrayElement(contents, i);
        std::string role, content;
        if (r) {
            const char* rs = env->GetStringUTFChars(r, nullptr);
            if (rs) { role = rs; env->ReleaseStringUTFChars(r, rs); }
            env->DeleteLocalRef(r);
        }
        if (c) {
            const char* cs = env->GetStringUTFChars(c, nullptr);
            if (cs) { content = cs; env->ReleaseStringUTFChars(c, cs); }
            env->DeleteLocalRef(c);
        }
        holders.push_back(role);
        holders.push_back(content);
    }
    for (jsize i = 0; i < n; i++) {
        msgs.push_back({holders[i*2], holders[i*2+1]});
    }

    int mt = maxTokens > 0 ? maxTokens : 256;
    TokenStreamBuf buf(env, callback);
    std::ostream os(&buf);
    g_llm->response(msgs, &os, nullptr, mt);
    return true;
}

// ── reset: 清空 KV cache ──
JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_MnnNative_reset(JNIEnv*, jobject) {
    if (g_llm) {
        g_llm->reset();
        LOGI("KV cache cleared");
    }
}

// ── free: 释放模型 ──
JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_MnnNative_free(JNIEnv*, jobject) {
    if (g_llm) {
        Llm::destroy(g_llm);
        g_llm = nullptr;
        g_ctx = nullptr;
        LOGI("Model freed");
    }
}

// ── isLoaded ──
JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_agent_MnnNative_isLoaded(JNIEnv*, jobject) {
    return g_llm != nullptr;
}

// ── getPerfInfo: 推理性能信息 ──
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_MnnNative_getPerfInfo(JNIEnv* env, jobject) {
    if (!g_llm || !g_ctx) {
        return env->NewStringUTF("{}");
    }
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"prefill_ms\":%lld,\"decode_ms\":%lld,\"sample_ms\":%lld,"
        "\"prompt_tokens\":%d,\"gen_tokens\":%d,\"status\":%d}",
        (long long)(g_ctx->prefill_us / 1000),
        (long long)(g_ctx->decode_us / 1000),
        (long long)(g_ctx->sample_us / 1000),
        g_ctx->prompt_len,
        g_ctx->gen_seq_len,
        static_cast<int>(g_ctx->status));
    return env->NewStringUTF(buf);
}

// ── dumpConfig: 当前配置 JSON ──
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_MnnNative_dumpConfig(JNIEnv* env, jobject) {
    if (!g_llm) return env->NewStringUTF("{}");
    std::string cfg = g_llm->dump_config();
    return env->NewStringUTF(cfg.c_str());
}

} // extern "C"
