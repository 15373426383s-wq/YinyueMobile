# YinYue Mobile（银月）

在 Android 设备上运行的**本地 LLM 智能体** App。无需联网即可在手机端加载大模型，结合 Android 无障碍服务操控屏幕、调用工具，实现"感知 → 决策 → 执行 → 验证"的 GUI Agent 闭环。

- 包名：`com.yinyue.mobile`
- 平台：Android（仅 `arm64-v8a`，`minSdk 26` / `compileSdk 34`）
- 推理后端：**llama.cpp**（兼容保留）+ **MNN + OpenCL**（主力，天玑 APU GPU 加速）

---

## 功能特性

- 🤖 **本地推理**：模型跑在端侧，隐私不出设备；支持流式对话。
- 🖥️ **无障碍 GUI 操控**：通过 `AccessibilityService` 读取屏幕 UI 树、模拟点击/输入/滚动，免 Root、免 MediaProjection 截图（Android 30+ 用系统截图 API）。
- 🛠️ **工具系统**：内置文件读写、Shell、应用启动、网页抓取/搜索、剪贴板等工具；危险操作带人工确认闸门（ApprovalGate）。
- 💬 **多轮对话 UI**：Jetpack Compose 实现，含对话气泡、工具调用卡、审批卡、线程切换、文件浏览器（带 LCS diff）、任务历史。
- 💾 **本地持久化**：Room 存储会话与任务，API Key 存于 `SharedPreferences`。

---

## 架构概览

```
app/src/main/
├── java/com/yinyue/mobile/
│   ├── agent/         # Agent 循环、LLM 客户端、双引擎封装、工具注册与权限
│   ├── accessibility/ # 无障碍服务、UI 树拍平、动作执行、手势控制
│   ├── tools/         # 各类工具实现（文件/Shell/浏览器/Python/系统…）
│   ├── data/          # Room 数据库、Repository、SettingsStore
│   ├── service/       # 前台保活服务、录屏服务
│   └── ui/            # Compose 界面（chat / screen / files / history）
├── cpp/               # JNI 胶水层（llama_jni.cpp / mnn_llm_jni.cpp + mtmd）
└── jniLibs/arm64-v8a/ # 预编译 .so（由构建脚本生成，默认不入库）
```

**Agent 循环**（`agent/AgentLoop.kt`）：循环执行 感知→决策（LLM）→执行（工具）→验证，直至任务完成或达到步数上限（`MAX_STEPS`）。

**双推理引擎**：
| 引擎 | 封装类 | 后端 | 状态 |
|------|--------|------|------|
| llama.cpp | `LocalLLMEngine` / `LlamaNative` | `libllama` + `libmtmd`（mtmd 多模态 API） | 兼容保留 |
| MNN | `MNNLLMEngine` / `MnnNative` | `libMNN` + `libllm`（OpenCL） | 主力（默认） |

切换方式：运行时由 `SettingsStore.localBackend`（`"llama"` / `"mnn"`，默认 `"mnn"`）决定；构建期两套 `.so` 均通过 `CMakeLists.txt` 的 `IMPORTED` 方式链接，不参与 Gradle 编译。详见 `ENGINE_MIGRATION.txt`。

---

## 技术栈 / 版本

| 项 | 版本 |
|----|------|
| AGP | 8.5.2 |
| Kotlin | 2.0.21 |
| Gradle | 8.7（腾讯/阿里 Maven 镜像分发） |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| JVM / CMake / NDK | 17 / 3.22.1 / 27.0.12077973 |
| ABI | arm64-v8a only |

---

## 构建

### 前置条件
- Android SDK（含 `platform-tools` / `adb`；建议加入 `PATH`）
- **NDK 27**（构建脚本自动探测 `27.0.12077973` 或 `26.3.11579264`）
- CMake 3.22.1
- PowerShell（Windows）、`gradlew.bat`
- **Gradle JDK 必须为 JDK 17**（Android Studio 自带 JBR 为 Java 21/25，会在配置阶段崩溃；`Settings → Build → Gradle → Gradle JDK` 指向 JDK 17）
- 已知 Kotlin Gradle Plugin 并发锁问题（KT-69330，已在 `gradle.properties` 以 `daemon=false`/`parallel=false`/`configuration-cache=false` 临时规避，可逐步清理）

### 1. 生成原生库（.so）
```powershell
# llama.cpp 后端（输出到 app/src/main/jniLibs/arm64-v8a/）
.\build_llamacpp.ps1

# MNN 后端（默认主力）
.\build_mnn.ps1
```
> `.so` 默认被 `.gitignore` 忽略（属可复现的构建产物）。克隆仓库后需先运行上述脚本；或自行用 Git LFS / Release 分发。

### 2. 编译 / 安装
```powershell
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat installDebug
```
或直接在 Android Studio 打开根目录同步后运行。

### 3. 部署模型到设备
```powershell
.\deploy_mnn.ps1   # 需手机已 USB 调试连接；自动探测 adb
```

---

## 模型

仓库**不含**模型权重（GB 级，已 gitignore）。请自备并放置到：
- MNN 模型（如 `Qwen3-8B-MNN`）：`mnn_models/`，再用 `deploy_mnn.ps1` 推送到设备；
- GGUF 模型（如 `qwen3-4b-instruct-q4_k_m.gguf`）：`temp/`，供 llama.cpp 后端加载。

可从 HuggingFace 等公开渠道获取对应量化模型。

---

## 目录结构（顶层）

```
YinyueMobile/
├── app/                 # Android 应用（Kotlin + Compose + JNI）
├── llm/                 # MNN JNI 独立模块（当前禁用，见下）
├── llama.cpp/           # vendored 上游（MIT）
├── MNN/                 # vendored 上游（Apache-2.0）
├── build_llamacpp.ps1   # 构建 llama.cpp .so
├── build_mnn.ps1        # 构建 MNN .so
├── deploy_mnn.ps1       # 部署 MNN 模型 + APK 到设备
├── ENGINE_MIGRATION.txt # 引擎切换说明
├── THIRD_PARTY_NOTICES.md
└── LICENSE
```

> 注：`llm/mnn` 为早期独立 MNN 模块副本，因其在 `CMakeLists.txt` 中用 FetchContent 联网拉取上游已被 `settings.gradle.kts` 禁用，实际生效的 MNN 通路为 `app/` 内的 `mnn_llm_jni.cpp` + `MnnNative.kt`。属遗留孤儿代码，可保留或清理。

---

## 许可证

- 本项目自有代码：**MIT** —— 见 [`LICENSE`](./LICENSE)
- 第三方库见 [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md)（llama.cpp = MIT，MNN = Apache-2.0）
