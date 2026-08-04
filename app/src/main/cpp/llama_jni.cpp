#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <utility>
#include <cstring>
#include <algorithm>
#include <chrono>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct llama_model*   g_model   = nullptr;
static struct llama_context* g_ctx     = nullptr;
static struct llama_sampler* g_sampler = nullptr;
static struct mtmd_context*  g_mtmd    = nullptr;
static llama_pos             g_n_past  = 0;

// 聊天历史：role + content 字符串对
static std::vector<std::pair<std::string, std::string>> g_chat_history;

// 由 Kotlin 侧注入的对话历史（每轮推理前通过 setHistory 设置）
static std::vector<std::pair<std::string, std::string>> g_injected_history;

// 暂存图片
static std::vector<uint8_t> g_pending_image;
static int g_pending_img_w = 0;
static int g_pending_img_h = 0;

static const struct llama_vocab* g_vocab = nullptr;

// ── 阶段3:端侧 grammar Function Calling(借鉴 Operit StructuredToolCallBridge) ──
// 用 GBNF grammar 约束本地模型输出标准 tool_calls JSON。
// grammar 启用时模型只能输出形如 {"tool_calls":[...], "content":"..."} 的 JSON 对象。
// tool_calls 数组允许为空(无工具调用);content 可选,承载自然语言回复(改善无工具调用时的 UX)。
static bool g_tool_grammar_enabled = false;
static const char* TOOL_CALL_GRAMMAR = R"GRAMMAR(
root      ::= object
object    ::= "{" ws "\"tool_calls\"" ws ":" ws array ("," ws "\"content\"" ws ":" ws string)? "}" ws
array     ::= "[" ws (tool_call ("," ws tool_call)*)? "]" ws
tool_call ::= "{" ws "\"id\"" ws ":" ws string "," ws "\"function\"" ws ":" ws fnobj "}" ws
fnobj     ::= "{" ws "\"name\"" ws ":" ws string "," ws "\"arguments\"" ws ":" ws string "}" ws
string    ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
ws        ::= "" | " " | "\n" [ \t]{0,20}
)GRAMMAR";

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_init(
    JNIEnv* env, jobject, jstring modelPath, jstring mmprojPath, jint nCtx) {

    // Bug 5 修复: 重复 init 时先释放已有资源,避免数 GB 模型/上下文句柄泄漏
    if (g_ctx)     { llama_free(g_ctx);            g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);    g_model = nullptr; }
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_mtmd)    { mtmd_free(g_mtmd);           g_mtmd = nullptr; }
    g_vocab = nullptr;
    g_n_past = 0;

    const char* mp = env->GetStringUTFChars(modelPath, nullptr);
    const char* pp = env->GetStringUTFChars(mmprojPath, nullptr);

    ggml_backend_load_all();

    llama_model_params model_params = llama_model_default_params();

    g_model = llama_model_load_from_file(mp, model_params);
    if (!g_model) {
        LOGE("Failed to load model: %s", mp);
        env->ReleaseStringUTFChars(modelPath, mp);
        env->ReleaseStringUTFChars(mmprojPath, pp);
        return false;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? nCtx : 4096;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        env->ReleaseStringUTFChars(modelPath, mp);
        env->ReleaseStringUTFChars(mmprojPath, pp);
        return false;
    }

    // 多模态初始化
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = 6;
    mtmd_params.print_timings = false;
    mtmd_params.warmup = false;

    g_mtmd = mtmd_init_from_file(pp, g_model, mtmd_params);
    if (!g_mtmd) {
        LOGI("mtmd_init failed — text-only mode");
    }

    // 采样器
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(42));

    g_n_past = 0;

    env->ReleaseStringUTFChars(modelPath, mp);
    env->ReleaseStringUTFChars(mmprojPath, pp);
    LOGI("Model loaded OK, ctx=%d", ctx_params.n_ctx);
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_processImage(
    JNIEnv* env, jobject, jbyteArray imageBytes, jint width, jint height, jint channels) {

    // Bug 7 修复: mtmd 仅支持 RGB(3) 通道,RGBA(4) 会导致位图解析错位,这里校验
    if (channels != 3) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "channels must be 3 (RGB)");
        return false;
    }

    if (!g_mtmd) {
        LOGI("No mtmd context — skipping image");
        return false;
    }

    jsize len = env->GetArrayLength(imageBytes);
    g_pending_image.resize(len);
    env->GetByteArrayRegion(imageBytes, 0, len, reinterpret_cast<jbyte*>(g_pending_image.data()));
    g_pending_img_w = width;
    g_pending_img_h = height;

    LOGI("Image stored: %dx%d, %d bytes", width, height, (int)len);
    return true;
}

JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_setHistory(
    JNIEnv* env, jobject, jobjectArray roles, jobjectArray contents) {

    g_injected_history.clear();

    if (roles == nullptr || contents == nullptr) return;

    // Bug 2 修复: 分别取两数组长度循环用 min 防越界;
    // 对每个 jstring 做 null 检查,GetStringUTFChars 返回值做 null 检查,
    // 用后立即 Release + DeleteLocalRef 防 OOM 崩溃与本地引用泄漏
    jsize n_roles = env->GetArrayLength(roles);
    jsize n_contents = env->GetArrayLength(contents);
    jsize n = std::min(n_roles, n_contents);
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring) env->GetObjectArrayElement(roles, i);
        jstring c = (jstring) env->GetObjectArrayElement(contents, i);

        std::string role, content;
        if (r != nullptr) {
            const char* rstr = env->GetStringUTFChars(r, nullptr);
            if (rstr != nullptr) {
                role = rstr;
                env->ReleaseStringUTFChars(r, rstr);
            }
            env->DeleteLocalRef(r);
        }
        if (c != nullptr) {
            const char* cstr = env->GetStringUTFChars(c, nullptr);
            if (cstr != nullptr) {
                content = cstr;
                env->ReleaseStringUTFChars(c, cstr);
            }
            env->DeleteLocalRef(c);
        }

        g_injected_history.push_back({role, content});
    }
    LOGI("History set: %d messages", (int)n);
}

// 辅助：把 token 数组喂给 llama_decode
static bool decode_tokens(const llama_token* tokens, int n_tokens, bool logits_last) {
    int n_ctx = llama_n_ctx(g_ctx);
    if (g_n_past + n_tokens > n_ctx) {
        LOGE("decode_tokens overflow: n_past=%d + n_tokens=%d > n_ctx=%d", (int)g_n_past, n_tokens, n_ctx);
        // 截断：只取能放下的 token
        n_tokens = n_ctx - g_n_past;
        if (n_tokens <= 0) return false;
    }
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    batch.n_tokens = n_tokens;
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]   = tokens[i];
        batch.pos[i]     = g_n_past + i;
        batch.n_seq_id[i]= 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]  = (logits_last && i == n_tokens - 1) ? 1 : 0;
    }
    int ret = llama_decode(g_ctx, batch);
    g_n_past += n_tokens;
    llama_batch_free(batch);
    return ret == 0;
}

// 辅助：单 token decode
// 注意：llama_batch_get_one 返回的 batch 中 pos/n_seq_id/seq_id/logits 均为 nullptr，
// 表示由 llama 内部自动跟踪 pos（单序列，最后一个 token 输出 logits）。
// 这些数组是借用指针 —— 禁止写入，也禁止 llama_batch_free（否则双重释放/写空指针崩溃）。
static bool decode_single(llama_token token) {
    if (g_n_past >= llama_n_ctx(g_ctx)) return false;
    llama_token t = token;
    llama_batch batch = llama_batch_get_one(&t, 1);
    int ret = llama_decode(g_ctx, batch);
    g_n_past++;
    return ret == 0;
}

// 辅助(阶段3):构造工具描述 system 消息,引导模型用 tool_calls JSON 格式输出。
// tools_json 为 OpenAI 风格 tools 数组 JSON(由 Kotlin StructuredToolCallBridge.toolsToJson 生成)。
static std::string build_tool_system_message(const std::string& tools_json) {
    std::string s;
    s += "你是一个可以调用工具的助手。当需要调用工具时,请严格输出以下 JSON 格式:\n";
    s += "{\"tool_calls\":[{\"id\":\"call_1\",\"function\":{\"name\":\"工具名\",\"arguments\":\"{\\\"参数\\\":\\\"值\\\"}\"}}]}\n";
    s += "若无需调用工具,请输出 {\"tool_calls\":[],\"content\":\"你的自然语言回复\"}。\n";
    s += "可用工具列表(JSON):\n";
    s += tools_json;
    return s;
}

// 辅助(阶段3):生成循环。从 chat() 抽取,供 chat()/chatWithTools() 复用。
// 保留 Bug 3 修复(token_to_piece 缓冲不足返回负数时按 -n 动态分配重试,避免丢字乱码)。
// 新增硬超时:手机端 7B 模型 CPU 推理极慢,若 max_tokens 过大且模型不输出 EOG,
// 生成可能持续数十分钟。加 60 秒强制截断,保证 JNI 调用必然返回,Kotlin 侧超时才真正生效。
static std::string generate_loop(llama_sampler* sampler, int max_tokens) {
    std::string result;
    int max_out = max_tokens > 0 ? max_tokens : 2048;
    const auto t_start = std::chrono::steady_clock::now();
    const long GENERATION_TIMEOUT_MS = 60000; // 60 秒硬上限
    bool timed_out = false;
    for (int i = 0; i < max_out; i++) {
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_start).count();
        if (elapsed > GENERATION_TIMEOUT_MS) {
            timed_out = true;
            LOGI("generate_loop: timeout after %ldms, stopping", elapsed);
            break;
        }
        llama_token id = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;

        // 部分 GGUF 未把 <|im_end|> 等标记为 EOG，且特殊 token 可能被 tokenizer 拆成多个
        // 普通 token（如 "<|" + "im_end" + "|>"），完整字符串匹配不到。这里检测模板标记的
        // 所有特征子串：<| 开头、|> 结尾、im_end/endoftext/end_of_text 内容。
        // JSON 工具调用中不会出现这些字符，不会误杀工具输出。
        char buf[128];
        int n = llama_token_to_piece(g_vocab, id, buf, sizeof(buf), 0, true);
        auto is_stop_piece = [](const char* data, int len) -> bool {
            std::string_view sv(data, len);
            return sv.find("<|") != std::string_view::npos ||
                   sv.find("|>") != std::string_view::npos ||
                   sv.find("im_end") != std::string_view::npos ||
                   sv.find("endoftext") != std::string_view::npos ||
                   sv.find("end_of_text") != std::string_view::npos;
        };
        if (n < 0) {
            std::vector<char> tmp(-n);
            n = llama_token_to_piece(g_vocab, id, tmp.data(), tmp.size(), 0, true);
            if (n > 0) {
                if (is_stop_piece(tmp.data(), n)) break;
                result.append(tmp.data(), n);
            }
        } else if (n > 0) {
            if (is_stop_piece(buf, n)) break;
            result.append(buf, n);
        }

        if (!decode_single(id)) break;
    }
    if (timed_out && result.empty()) {
        result = "[生成超时,请尝试更短的提问或换更小的模型]";
    }
    return result;
}

// 辅助(阶段3):把格式化 prompt 编码进上下文(支持多模态/纯文本)。
// 保留 Bug 8 修复(tokenize 缓冲不足返回负数时按 -n_tokens resize 重试)与 mtmd 路径。
// 成功返回 true;失败返回 false 并写出错误信息到 err。
static bool prepare_context(const std::string& formatted_str, std::string& err) {
    bool has_image = !g_pending_image.empty() && g_mtmd;
    if (has_image) {
        mtmd_bitmap* bitmap = mtmd_bitmap_init(g_pending_img_w, g_pending_img_h, g_pending_image.data());
        if (!bitmap) {
            LOGE("Failed to create bitmap");
            err = "[ERROR] 图片处理失败";
            g_pending_image.clear();
            return false;
        }
        mtmd_input_chunks* chunks = mtmd_input_chunks_init();
        mtmd_input_text in_text;
        in_text.text = formatted_str.c_str();
        in_text.text_len = formatted_str.size();
        in_text.add_special = true;
        in_text.parse_special = true;

        const mtmd_bitmap* bitmaps[] = { bitmap };
        int res = mtmd_tokenize(g_mtmd, chunks, &in_text, bitmaps, 1);
        mtmd_bitmap_free(bitmap);

        if (res != 0) {
            LOGE("mtmd_tokenize failed: %d", res);
            mtmd_input_chunks_free(chunks);
            err = "[ERROR] 图片编码失败";
            g_pending_image.clear();
            return false;
        }

        llama_pos new_n_past = g_n_past;
        // logits_last 必须为 true：最后一个 chunk 需要 logits 供采样器取样
        res = mtmd_helper_eval_chunks(
            g_mtmd, g_ctx, chunks,
            g_n_past, 0, 512, true, &new_n_past);
        mtmd_input_chunks_free(chunks);

        if (res != 0) {
            LOGE("mtmd_helper_eval_chunks failed: %d", res);
            err = "[ERROR] 图片解码失败";
            g_pending_image.clear();
            return false;
        }
        g_n_past = new_n_past;
        g_pending_image.clear();
        return true;
    }

    // 纯文本 tokenize
    std::vector<llama_token> tokens(formatted_str.size() + 64);
    int n_tokens = llama_tokenize(
        g_vocab,
        formatted_str.c_str(), formatted_str.size(),
        tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            g_vocab,
            formatted_str.c_str(), formatted_str.size(),
            tokens.data(), tokens.size(), true, false);
    }
    if (n_tokens < 0) {
        err = "[ERROR] Tokenize 失败";
        return false;
    }
    tokens.resize(n_tokens);

    // logits_last=true：prompt 最后一个 token 必须有 logits，否则采样器无输出可取
    if (!decode_tokens(tokens.data(), n_tokens, true)) {
        err = "[ERROR] Decode 失败";
        return false;
    }
    return true;
}

// 辅助(阶段3):apply chat template(保留 Bug 1 修复的 resize-retry 模式)+ prepare_context。
// 成功返回 true 并写出 formatted_str;失败返回 false 并写出 err。
static bool apply_template_and_prepare(
    const std::vector<llama_chat_message>& msgs,
    std::string& formatted_str, std::string& err) {
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    int n_ctx = llama_n_ctx(g_ctx);
    std::vector<char> formatted(n_ctx);
    int len = llama_chat_apply_template(
        tmpl, msgs.data(), msgs.size(), true,
        formatted.data(), formatted.size());
    if (len < 0) {
        err = "[ERROR] Chat template 失败";
        return false;
    }
    // Bug 1 修复: 缓冲不足时 apply_template 返回所需总长度(可能 > n_ctx),
    // 按 len resize 并重试一次,避免用 len > capacity 构造 string 越界读取
    if (len > (int)formatted.size()) {
        formatted.resize(len);
        len = llama_chat_apply_template(
            tmpl, msgs.data(), msgs.size(), true,
            formatted.data(), formatted.size());
        if (len < 0) {
            err = "[ERROR] Chat template 失败";
            return false;
        }
    }
    formatted_str.assign(formatted.data(), len);
    return prepare_context(formatted_str, err);
}

JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_chat(
    JNIEnv* env, jobject, jstring prompt, jint maxTokens) {

    if (!g_ctx || !g_model || !g_sampler || !g_vocab) {
        return env->NewStringUTF("[ERROR] 模型未加载");
    }

    // 重置 KV cache，防止无限累积导致 n_ctx 溢出
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string user_text(p);
    env->ReleaseStringUTFChars(prompt, p);

    bool has_image = !g_pending_image.empty() && g_mtmd;
    std::string full_prompt;

    // 构建 llama_chat_message 数组
    std::vector<llama_chat_message> msgs;
    for (auto& h : g_injected_history) {
        msgs.push_back({h.first.c_str(), h.second.c_str()});
    }
    if (has_image) {
        full_prompt = std::string(mtmd_default_marker()) + "\n" + user_text;
        msgs.push_back({"user", full_prompt.c_str()});
    } else {
        msgs.push_back({"user", user_text.c_str()});
    }

    std::string formatted_str, err;
    auto t_tpl = std::chrono::steady_clock::now();
    if (!apply_template_and_prepare(msgs, formatted_str, err)) {
        return env->NewStringUTF(err.c_str());
    }
    auto t_dec = std::chrono::steady_clock::now();
    long ms_tpl_decode = std::chrono::duration_cast<std::chrono::milliseconds>(t_dec - t_tpl).count();
    LOGI("[TIMING] template+tokenize+decode: %ldms, formatted_len=%zu", ms_tpl_decode, formatted_str.size());

    // 阶段3:grammar 启用时,构造临时采样器链(temperature + dist + grammar),
    // 借鉴 Operit StructuredToolCallBridge。生成后释放临时链,不影响 g_sampler 复用。
    llama_sampler* grammar_chain = nullptr;
    llama_sampler* active_sampler = g_sampler;
    if (g_tool_grammar_enabled) {
        grammar_chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(grammar_chain, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(grammar_chain, llama_sampler_init_dist(42));
        struct llama_sampler* g = llama_sampler_init_grammar(g_vocab, TOOL_CALL_GRAMMAR, "root");
        if (g) {
            llama_sampler_chain_add(grammar_chain, g);
        } else {
            // grammar 解析失败时退化为无 grammar 采样,避免 NULL 加入链导致崩溃
            LOGE("tool-call grammar parse failed; falling back to no-grammar sampling");
        }
        active_sampler = grammar_chain;
    }

    std::string result = generate_loop(active_sampler, maxTokens);
    auto t_gen = std::chrono::steady_clock::now();
    long ms_gen = std::chrono::duration_cast<std::chrono::milliseconds>(t_gen - t_dec).count();
    LOGI("[TIMING] generate_loop: %ldms, result_len=%zu", ms_gen, result.size());

    if (grammar_chain) llama_sampler_free(grammar_chain);

    // Bug 4 修复: g_chat_history 全文件从未被读取(prompt 实际用 g_injected_history),
    // 此处 push 只会造成无限增长与内存泄漏,故删除

    // 清除注入历史，确保下次 setHistory 调用前为干净状态
    g_injected_history.clear();

    return env->NewStringUTF(result.c_str());
}

// 核心修复:完整消息列表对话。
// 之前 Kotlin 侧把"系统:/用户:/助手:"手工拼成一个长字符串作为单条 user 消息,
// C++ 再套 chat template → 双重拼接,模型看到 <|im_start|>user\n系统:... 这种混乱结构,
// 会按模板格式"扮演"整段对话(echo 历史 + 输出 user/assistant 标签)。
// 现在:由 Kotlin 传入完整 roles[]/contents[](含 system),C++ 唯一负责套 chat template。
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_chatWithMessages(
    JNIEnv* env, jobject, jobjectArray roles, jobjectArray contents, jint maxTokens) {

    if (!g_ctx || !g_model || !g_sampler || !g_vocab) {
        return env->NewStringUTF("[ERROR] 模型未加载");
    }

    // 重置 KV cache，防止无限累积导致 n_ctx 溢出
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;

    // 读取 roles/contents 数组 → llama_chat_message 数组
    // 注意:msgs 只持有 c_str 指针,必须用局部 std::string 容器保证生命周期
    std::vector<llama_chat_message> msgs;
    std::vector<std::string> role_holder, content_holder;
    jsize n_roles = env->GetArrayLength(roles);
    jsize n_contents = env->GetArrayLength(contents);
    jsize n = std::min(n_roles, n_contents);
    for (jsize i = 0; i < n; i++) {
        jstring r = (jstring) env->GetObjectArrayElement(roles, i);
        jstring c = (jstring) env->GetObjectArrayElement(contents, i);
        std::string role, content;
        if (r != nullptr) {
            const char* rs = env->GetStringUTFChars(r, nullptr);
            if (rs != nullptr) { role = rs; env->ReleaseStringUTFChars(r, rs); }
            env->DeleteLocalRef(r);
        }
        if (c != nullptr) {
            const char* cs = env->GetStringUTFChars(c, nullptr);
            if (cs != nullptr) { content = cs; env->ReleaseStringUTFChars(c, cs); }
            env->DeleteLocalRef(c);
        }
        // 最后一条 user 消息若有图片,前置视觉 marker
        bool has_image = !g_pending_image.empty() && g_mtmd;
        if (has_image && i == n - 1 && role == "user") {
            content = std::string(mtmd_default_marker()) + "\n" + content;
        }
        role_holder.push_back(role);
        content_holder.push_back(content);
        msgs.push_back({role_holder.back().c_str(), content_holder.back().c_str()});
    }

    // 套 chat template + 编码上下文
    std::string formatted_str, err;
    auto t_tpl = std::chrono::steady_clock::now();
    if (!apply_template_and_prepare(msgs, formatted_str, err)) {
        return env->NewStringUTF(err.c_str());
    }
    auto t_dec = std::chrono::steady_clock::now();
    long ms_tpl_decode = std::chrono::duration_cast<std::chrono::milliseconds>(t_dec - t_tpl).count();
    LOGI("[TIMING] chatWithMessages template+tokenize+decode: %lldms, formatted_len=%zu, msgs=%d",
         (long long) ms_tpl_decode, formatted_str.size(), (int)msgs.size());

    // 生成（复用 generate_loop：内含 EOG 子串检测 + 60s 硬超时）
    std::string result = generate_loop(g_sampler, maxTokens);
    auto t_gen = std::chrono::steady_clock::now();
    long ms_gen = std::chrono::duration_cast<std::chrono::milliseconds>(t_gen - t_dec).count();
    LOGI("[TIMING] chatWithMessages generate_loop: %ldms, result_len=%zu", ms_gen, result.size());

    // 图片消费完毕
    g_pending_image.clear();
    return env->NewStringUTF(result.c_str());
}

// 阶段3:带工具的对话(借鉴 Operit StructuredToolCallBridge)。
// 在 messages 前注入工具描述 system 消息,用 grammar 约束输出为 tool_calls JSON。
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_chatWithTools(
    JNIEnv* env, jobject, jstring prompt, jint maxTokens, jstring toolsJson) {
    if (!g_ctx || !g_model || !g_sampler || !g_vocab) {
        return env->NewStringUTF("[ERROR] 模型未加载");
    }

    // 重置 KV cache
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;

    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string user_text(p);
    env->ReleaseStringUTFChars(prompt, p);

    const char* tj = env->GetStringUTFChars(toolsJson, nullptr);
    std::string tools_json(tj);
    env->ReleaseStringUTFChars(toolsJson, tj);

    // 工具描述 system 消息置顶,引导模型用 tool_calls JSON 格式输出
    std::string tool_sys = build_tool_system_message(tools_json);

    bool has_image = !g_pending_image.empty() && g_mtmd;
    std::string full_prompt;

    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", tool_sys.c_str()});
    for (auto& h : g_injected_history) {
        msgs.push_back({h.first.c_str(), h.second.c_str()});
    }
    if (has_image) {
        full_prompt = std::string(mtmd_default_marker()) + "\n" + user_text;
        msgs.push_back({"user", full_prompt.c_str()});
    } else {
        msgs.push_back({"user", user_text.c_str()});
    }

    std::string formatted_str, err;
    if (!apply_template_and_prepare(msgs, formatted_str, err)) {
        return env->NewStringUTF(err.c_str());
    }

    // 构造带 grammar 的临时采样器链(每次调用独立创建/释放,不影响 g_sampler)
    llama_sampler* tool_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(tool_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(tool_sampler, llama_sampler_init_dist(42));
    struct llama_sampler* g = llama_sampler_init_grammar(g_vocab, TOOL_CALL_GRAMMAR, "root");
    if (g) {
        llama_sampler_chain_add(tool_sampler, g);
    } else {
        LOGE("tool-call grammar parse failed; generating without grammar constraint");
    }

    std::string result = generate_loop(tool_sampler, maxTokens);
    llama_sampler_free(tool_sampler);

    g_injected_history.clear();
    return env->NewStringUTF(result.c_str());
}

// 阶段3:启用/禁用工具调用 grammar 约束(借鉴 Operit nativeClearToolCallGrammar)。
// 启用后,后续 chat() 也会用 TOOL_CALL_GRAMMAR 约束输出。
// 注意:chatWithTools() 始终自带 grammar,本开关主要影响 chat() 的常规路径。
JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_setToolCallGrammar(JNIEnv*, jobject, jboolean enabled) {
    g_tool_grammar_enabled = enabled;
    LOGI("Tool-call grammar %s", enabled ? "enabled" : "disabled");
}

// 阶段3:解析 LLM 输出为标准 tool_calls JSON(借鉴 Operit nativeParseToolCallResponse)。
// 容错:去 markdown fence、提取首个 {...} JSON 对象。无 JSON 时返回 {"tool_calls":[]}。
// 注意:Kotlin 侧 StructuredToolCallBridge.parseToolCalls 提供更完整的 Gson 解析,
// 本接口主要供直接 JNI 调用方使用,二者可任选。
JNIEXPORT jstring JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_parseToolCallResponse(
    JNIEnv* env, jobject, jstring response) {
    if (response == nullptr) {
        return env->NewStringUTF("{\"tool_calls\":[]}");
    }
    const char* r = env->GetStringUTFChars(response, nullptr);
    std::string s(r);
    env->ReleaseStringUTFChars(response, r);

    // trim 前导空白
    size_t i = 0;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++;
    std::string trimmed = s.substr(i);

    // 去 ```json ... ``` fence
    if (trimmed.rfind("```", 0) == 0) {
        trimmed = trimmed.substr(3);
        if (trimmed.rfind("json", 0) == 0) trimmed = trimmed.substr(4);
        size_t fence_end = trimmed.rfind("```");
        if (fence_end != std::string::npos) trimmed = trimmed.substr(0, fence_end);
    }

    // 找第一个 { 到最后一个 }
    size_t start = trimmed.find('{');
    size_t end = trimmed.rfind('}');
    if (start == std::string::npos || end == std::string::npos || end <= start) {
        return env->NewStringUTF("{\"tool_calls\":[]}");
    }
    std::string json_obj = trimmed.substr(start, end - start + 1);
    return env->NewStringUTF(json_obj.c_str());
}

JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_resetChat(JNIEnv*, jobject) {
    g_chat_history.clear();
    // Bug 6 修复: 同步清理注入历史,否则下次 chat() 复用上一轮注入历史导致上下文错乱
    g_injected_history.clear();
    g_pending_image.clear();
    if (g_ctx) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_n_past = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_yinyue_mobile_agent_LlamaNative_free(JNIEnv*, jobject) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_mtmd)    { mtmd_free(g_mtmd);           g_mtmd = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);            g_ctx = nullptr; }
    if (g_model)   { llama_model_free(g_model);    g_model = nullptr; }
    g_vocab = nullptr;
    g_chat_history.clear();
    g_pending_image.clear();
    g_n_past = 0;
    LOGI("Model freed");
}

} // extern "C"
