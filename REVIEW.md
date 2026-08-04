# 代码审查记录（通读摘要）

> 本次对 `D:\work\2026-08-02-16-26-15` 全仓库的审查记录，作为"读取全部文件"的整合产出。
> 仓库共 15,574 个文件，其中 `MNN/`（11,144）与 `llama.cpp/`（4,206）为 vendored 第三方库；项目自有代码约 240 个文件（`app/` 89、`llm/` 151，含 145 个已禁用的构建产物）。

## 一、项目定位
Android 端本地 LLM 智能体（包名 `com.yinyue.mobile`）。双推理后端：llama.cpp（兼容保留）、MNN+OpenCL（主力）。通过无障碍服务操控屏幕，工具体系执行动作，形成 GUI Agent 闭环。

## 二、模块职责（app/src/main）
- **agent/**：`AgentLoop`（感知→决策→执行→验证，MAX_STEPS）、`LLMClient`（DeepSeek OpenAI 兼容 + function calling）、`LocalLLMEngine`/`MNNLLMEngine`、双 JNI 封装、工具注册/权限/确认（ApprovalGate）。
- **accessibility/**：`CoomiAccessibilityService`、UI 树拍平（`ScreenReader`）、`ActionExecutor`、`GestureController`。
- **tools/**：文件、Shell、浏览器、Python、系统、剪贴板、应用启动等（含危险标记与人工确认）。
- **data/**：Room（tasks/messages）、Repository、`SettingsStore`（API Key 存 SharedPreferences）。
- **ui/**：Compose 三导航（对话/屏幕/文件）+ 历史页，含工具卡/审批卡/文件 diff。
- **cpp/**：`llama_jni.cpp`、`mnn_llm_jni.cpp`、mtmd 头文件；CMakeLists 用 IMPORTED 链接预编译 .so。

## 三、构建 / 依赖
- 流程：PowerShell 脚本预编译 .so → 拷入 `jniLibs/arm64-v8a/` → Gradle 仅编 JNI 胶水层。
- 版本：AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7 / compileSdk 34 / minSdk 26 / NDK 27 / CMake 3.22.1。
- 依赖源：腾讯云 nexus → 阿里云 public → google() → mavenCentral()（`FAIL_ON_PROJECT_REPOS`）。
- 已知坑：Gradle JDK 须 JDK 17（JBR 21/25 配置期崩溃）；KSP 并发锁 KT-69330 已用 `gradle.properties` 规避。

## 四、安全审查结论
- ✅ **无真实凭据泄露**：未发现硬编码 API Key / Token / 密码 / 私钥；`apiKey` 仅运行时从 SharedPreferences 注入。
- ✅ 仓库**尚未 `git init`**，无历史泄密需清洗。
- 🔧 已修复：`deploy_mnn.ps1` 的 `$PROJECT`/`$ADB` 硬编码绝对路径（改为 `$PSScriptRoot` + PATH 探测）。
- 🔧 已加固：`.gitignore` 现忽略 `.workbuddy/`（含本机用户名的内部记录）、`.kotlin/`、`build/`、`.cxx`、`temp/`、`mnn_models/`、`jniLibs/*.so`、`*.apk` 等。
- 🚫 必须忽略的大文件：`temp/*.gguf`（~2.5GB）、`mnn_models/`（~7.8GB）、`jniLibs/*.so`（~236MB）——均已排除，避免超 GitHub 100MB 限制与仓库膨胀。

## 五、遗留问题（非阻塞，建议后续处理）
1. 品牌名遗留：`CoomiAccessibilityService` / `CoomiApp` 沿用旧品牌，与 `com.yinyue.mobile` 不一致。
2. 工具清单不一致：`PromptBuilder.systemPrompt()` 仍列出已禁用的 `python_exec`，与 `ToolRegistry` 实际注册工具不符，易误导模型发起无效调用。
3. `llm/mnn` 为已禁用孤儿模块（构建产物 145 个文件），可清理或与 `app/` 内实现合并。

## 六、第三方许可证
- `llama.cpp/`：MIT（`llama.cpp/LICENSE`）
- `MNN/`：Apache-2.0（`MNN/LICENSE.txt`）
- 均已整库提交（vendored，不拆 submodule），见 `THIRD_PARTY_NOTICES.md`。
