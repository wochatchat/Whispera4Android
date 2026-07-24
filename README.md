# Whispera4Android

Android 端口复刻 [maomao-2001/Whispera](https://github.com/maomao-2001/Whispera) —— 一个本地实时语音对话应用。延续原版的管线结构，**保留其三大核心体验**：

- **流式**：LLM 输出 → 按句切分 → 句子级 TTS 流式合成 → AudioTrack 即出声
- **低延迟**：LLM 首 token → TTS 首块音频 < 1s（取决于手机性能和 LLM 选择）；ASR 的 RTF ~0.1 on arm64
- **拟人音**：替换不可在手机上跑的 VoxCPM-2 为 [**Kokoro-82M** ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)，支持 103 个发音人音色，中文/英文都可，听感接近真人

## 与原版的对应表

| 环节 | 原版 (Whispera) | 本项目 (Whispera4Android) |
|---|---|---|
| 桌面/前端框架 | Electron | Kotlin + Jetpack Compose |
| VAD | Silero VAD (ONNX, CPU) | **Silero VAD (ONNX, 不变)** via sherpa-onnx |
| ASR | SenseVoice (FunASR, PyTorch, GPU) | SenseVoice (ONNX 导出, sherpa-onnx) |
| LLM | llama-server (C++/GGUF + CUDA) | OpenAI 兼容 HTTP 客户端；本地 llama-server (手机 Termux) 或任何云端 API |
| 文本分段 | StreamingTextSegmenter (Python) | StreamingTextSegmenter (Kotlin, 直译) |
| TTS | VoxCPM-2 (PyTorch, CUDA) | Kokoro-82M ONNX (sherpa-onnx) |
| 协议 | WebSocket | 业务逻辑直接在 App 内（无 WebSocket 中间层） |
| 打断 | barge-in (VAD 内) | **同款** barge-in (VadSession + ttx.stopGeneration + AudioTrack 停止) |

## 架构

```
AudioRecord(16kHz mono PCM)
    └─→ VadSession (Silero ONNX, barge-in 检测)
        └─→ AsrEngine (SenseVoice ONNX, utterance 级)
            └─→ LlmClient.stream (OpenAI SSE, 兼容 llama-server)
                └─→ StreamingTextSegmenter (句号/标点/char 级流式切分)
                    └─→ TtsEngine.generateStreaming (Kokoro ONNX, chunk callback)
                        └─→ AudioTrack (即出声)
```

## 开发机要求

- Android Studio Hedgehog+ 或命令行 + Android SDK + NDK（arm64-v8a）
- JDK 17
- Gradle 8.5+ 由 gradlew 自动拉取（项目内置 wrapper 配置）
- 至少 2 GB 空闲空间（便于下载 ONNX 模型）
- 一台 Android 8.0 (API 26) 以上、ARM64 手机（推荐 RAM ≥ 8 GB，以便在手机本地跑 llama.cpp LLM）

## 快速开始

```bash
# 1. 克隆项目
git clone https://github.com/wochatchat/Whispera4Android.git
cd Whispera4Android

# 2. 下载模型 + 下载 Sherpa-ONNX AAR 一键脚本
./scripts/setup_models.sh
#   ↑ 下载到 app/src/main/assets/models/：
#     - silero_vad/silero_vad.onnx       (~2 MB)
#     - sensevoice/model.onnx + tokens.txt  (~500 MB，可选，多语种)
#     - kokoro-multi-lang-v1_1/*           (~200 MB，多音色)
#   也会下载 sherpa-onnx AAR 到 app/libs/

# 3. 直接构建——内置签名 key，开箱即用
./scripts/build-release.sh
# ↑ 无需任何 keystore 配置即可生成签名 release APK！
#
# 项目自带了一把 PUBLIC 测试 key (app/keystore/builtin-release.p12)：
#   alias: whispera-builtin
#   passwords: whispera (公开)
# clone 即可 build 出已签 release APK，零配置。
# ⚠ 这把 key 所有人都能拿到，仅用于开源测试构建。
#   千万别用它上 Google Play；想上正式版请运行：
./scripts/gen-keystore.sh    # 生成你自己的私有 keystore（输出会被 .gitignore 排除）

# 产物：app/build/outputs/apk/offlineFull/release/app-offlineFull-release.apk
# debug 版：./scripts/build-debug.sh

# 4. 安装到手机（需 adb）
./scripts/install.sh
```

## GitHub Actions 自动构建

仓库内置了 `.github/workflows/build-apk.yml`：

- 推送 main 分支（修改了 app/、scripts/、build 配置）或手动触发 workflow 后，CI 自动：
  1. 装 JDK 17 + Android SDK + NDK
  2. 下载 ONNX 模型和 sherpa-onnx AAR
  3. 用**内置 public test key** 给 release APK 签名
  4. 上传 `Whispera4Android-offlineFull-release` 和 `Whispera4Android-liteCloud-release` 两个 artifact
- 在仓库的 **Actions** 标签页能直接下载产物 APK，30 天保留期

## 两套 product flavor

- **offlineFull**（推荐默认）：把 VAD/ASR/TTS 三个 ONNX 模型都打进 APK。APK 约 700 MB+，安装后**完全离线**。
- **liteCloud**：APK 不包模型，首次启动时按需下载。如果同时选择云端 LLM API，APK 默认极小。

`build.gradle.kts` 里 `flavorDimensions + "engine"`；`offlineFull` 与 `liteCloud` 在 `BuildConfig.BUNDLE_MODELS` 上区分。

## 配置 LLM（最关键的一步）

App 本身**不内置 llama.cpp 推理**，只暴露 OpenAI 兼容 HTTP 客户端。你有两种选择：

- **完全离线**：在手机上用 Termux 装一个 llama-server 并绑定到 `127.0.0.1:8080/v1`；在 App 设置里把 `API 地址` 设为 `http://127.0.0.1:8080/v1`，`密钥` 留空或任意。
  - 推荐模型：`Qwen2.5-1.5B-Instruct.Q4_K_M.gguf`（适合 8 GB RAM 手机，中文流畅）
  - 12 GB RAM 以上的可跑 `Qwen2.5-3B-Instruct.Q4_K_M.gguf`，质量明显好一档
- **联网低延迟**：填任一 OpenAI 兼容服务（中转站、豆包、Moonshot 等）。若开启云端 LLM，你只离线 ASR/TTS，LLM 网络拉。

> 这套架构与原版 Whispera 完全一致——Whispera 也不编译 llama.cpp，只下发 llama-server 二进制让用户自启。

## 三个核心需求达成的做法

### 1. 流式

LLM 用 OkHttp SSE 客户端，每收到一个 token delta，喂给 `StreamingTextSegmenter`（直译自 Whispera 的 Python 类）：
- 句号/句末标点就出一句 TTS
- 长句超过 `hard_limit`（默认 120 字符）切一刀
- TTS 用 `OfflineTts.generateWithCallback(...)` 原生 chunk-callback，逐块浮点音频喂给 AudioTrack

### 2. 低延迟

| 阶段 | 来源 |
|---|---|
| VAD | 1024-sample 帧 ~32 ms |
| ASR (SenseVoice ONNX) | 单条 utterance RTF ~0.1，长度短的回答 ~100-300 ms |
| LLM 首 token | 本地 1.5B Q4 在 8 GB 手机上 ~500-1000 ms；云端更快 |
| TTS 首块 | Kokoro chunk callback 第一块 ~200-400 ms |

端到端（从用户说完一句话，到第一个声音出来）目标 **1.5–3 秒**。复杂推理题会更慢。

### 3. 拟人音

Kokoro 是 GitHub 上目前跑得动的 ONNX TTS 里的**音质最好**的中文多语种 TTS。原版 VoxCPM 可能略胜一筹（支持声音克隆），但手机跑不下模型。换 Kokoro 是**性能/音质取舍**后的可落地最优解：支持切换 103 种内置音色，通过 `speaker id` 实时换人。

## 注意 / 已知限制

- 没有 llama.cpp ANT 编译进 App。这降低了构建复杂度，但**完全离线时用户需要自行在 Termux 远端启动 llama-server**。这是为了避免在沙箱里难以验证的 NDK 编译链；如你确有需求可自行接 [llama.cpp android](https://github.com/ggerganov/llama.cpp) 编译产物。
- VoxCPM 声音克隆没移植：手机跑不下 VoxCPM 的 PyTorch 推理；如果未来 sherpa-onnx 提供 VoxCPM 的 ONNX 导出，可以直接换引擎。
- Emoji 情绪标记、mem0 长期记忆模块尚未移植——可按需补，主流程不阻塞。
- 单 ABI：只构建 arm64-v8a（绝大多数手机兼容），不做 x86_64。

## 仓库结构

```
app/
  build.gradle.kts                  # signingConfigs 用内置 public test key，productFlavors (offlineFull / liteCloud)
  keystore/builtin-release.p12      # 内置 PUBLIC 测试 keystore（已提交），clone 即可签 release
  src/main/
    AndroidManifest.xml
    java/com/whispera/android/
      config/AppConfig.kt           # 持久配置（SharedPreferences 层）
      config/ModelManager.kt        # 模型文件查找与解包
      vad/VadSession.kt             # 直译自原版 vad_session.py
      asr/AsrEngine.kt              # sherpa-onnx OfflineRecognizer 封装
      llm/LlmClient.kt             # OkHttp SSE 流式客户端
      segmenter/StreamingTextSegmenter.kt   # 直译自原版 text_segmenter.py
      tts/TtsEngine.kt              # sherpa-onnx Kokoro TTS（含 chunk callback）
      pipeline/RealtimePipeline.kt  # 端到端状态机：mic→vad→asr→llm→seg→tts→play
      ui/MainActivity.kt
      ui/ConversationScreen.kt      # Compose 对话主界面
      ui/SettingsSheet.kt           # 设置面板
    res/                            # 暗色主题、strings、icon
    assets/models/                  # gitignored；setup_models.sh 写入
scripts/
  setup_models.sh                   # 下载 VAD/ASR/TTS ONNX 模型 + sherpa AAR
  gen-builtin-keystore.sh           # 生成内置 PUBLIC 测试 keystore（已提交，仅项目初始化用）
  gen-keystore.sh                   # 生成你自己的私有 release keystore（gitignored）
  build-debug.sh                    # gradlew assembleOfflineFullDebug
  build-release.sh                  # gradlew assembleOfflineFullRelease（签名版）
  install.sh                        # adb install -r
keystore.properties.example          # 模板；用户私有 key 配置（提交进 git）
.gitignore                          # 排除模型/私有 jks/keystore.properties
.github/workflows/build-apk.yml    # GitHub Actions：自动拉模型 + 构建签名 APK + 上传 artifact
```

## 许可证

与原版一致，采用 [Apache License 2.0](LICENSE)。集成的 sherpa-onnx 提供自身许可证，请一并查阅 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 仓库。

## 鸣谢

- [maomao-2001/Whispera](https://github.com/maomao-2001/Whispera) — 原项目的架构与设计来源
- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Android 上 ONNX ASR/TTS/VAD 的核心推理引擎
- [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) — 离线 LLM 推理后端
