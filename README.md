# 应灵 · CatfeeAudio

> 在 Android 上捕获其他应用播放的音频，流式识别中文，逐句翻译成英文，用悬浮字幕显示。
> 默认全部本地推理；可选接入任意 OpenAI 兼容云端 LLM 提升翻译质量。

---

## 1. 已实现

- **系统音频采集**：`AudioPlaybackCapture` + 一次 `MediaProjection` 授权（仅取音频，不录屏）。
- **按应用监听**：多选应用，按 UID 精确捕获；也可切到「全部媒体」按类型捕获。
- **悬浮字幕窗**：紧凑布局，保留最近 3 句的「原文 + 译文」，新句滑入；每句停留时长可在设置中选 6/10/15/20/30 秒（默认 10 秒），超时后渐隐移除，不会长期占屏，可滚动回看。
- **模型自动下载**：首次使用在设置页下载，Wi‑Fi 下自动开始；通知栏和设置页显示总进度、当前文件、速度与剩余时间，支持断点续传，完成后自动启用。
- **流式 ASR**：中文/英文用 sherpa-onnx 流式 zipformer；日/韩/英用 SenseVoice 离线多语种识别。
- **逐句翻译**：默认 Qwen2.5-0.5B-Instruct（llama.cpp）；可在设置中开启云端 LLM（自定义 Base URL / Key / 模型），云端失败自动回退本地。支持目标中文或英文；日/韩/英 → 中、中 → 英。
- **AI Studio 风格本体**：深海军蓝底、亮青→宝蓝渐变、22dp 圆角、三页导航；应用图标与整体配色取自项目 LOGO。

## 2. 架构

```
AudioPlaybackCapture (48k stereo)
  → 16k mono
  → 中文/英文：sherpa-onnx Streaming Zipformer
  → 日/韩/英 → 中文：sherpa-onnx SenseVoice（能量 VAD 切段）
  → 端点检测 / 3 秒强制断句
  → 翻译：本地 Qwen2.5-0.5B-Instruct 或云端 LLM（可切换）
  → 悬浮字幕（原文 + 译文完整显示）
```

为什么不用 Whisper：Whisper 非流式，实测在 Redmi Note 9 上 4.8 秒音频要 12.4 秒推理，字幕严重滞后。
sherpa 流式 ASR 的识别是实时的，瓶颈只剩翻译，而 0.5B 模型每句约 2~4 秒。

## 3. 构建

环境：Android Studio / JDK 17 / SDK 36 / NDK 27.2 / CMake 3.22.1。

```powershell
# 1) 拉取 llama.cpp 源码（不入库）
powershell -ExecutionPolicy Bypass -File scripts/fetch-llama.ps1

# 2) 编译
gradlew.bat assembleDebug
```

- sherpa-onnx Android AAR 已放在 `app/libs/sherpa-onnx.aar`，无需额外下载。
- 原生代码强制 `-O3` 并启用 ARMv8.2 `dotprod+fp16`；debug 包也能跑出可用速度。
- APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 4. 模型（应用内自动下载）

首次使用时，打开 **设置 → 离线模型**：

- Wi‑Fi 下会自动开始下载；移动网络下需要点一下确认，避免误耗流量。
- 设置页显示总进度条、当前文件、已下载大小、速度和剩余时间；通知栏同步显示。
- 下载使用 `.part` 临时文件，可中断后续传；全部完成后自动启用，无需电脑。
- 总大小约 **1.01 GB**（7 个文件）。

| 模型 | 大小 | 路径 |
|---|---|---|
| sherpa zipformer 中英双语 | encoder 330MB + decoder 14MB + joiner 13MB + tokens | `models/sherpa-zh-en/` |
| SenseVoice 多语种（中英日韩粤） | 239MB + tokens | `models/sensevoice/` |
| Qwen2.5-0.5B-Instruct Q4_K_M | 约 491MB | `models/qwen2.5-0.5b-instruct-q4_k_m.gguf` |

模型来源（应用内通过 hf-mirror 下载）：
- sherpa zipformer：<https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20>
- SenseVoice：<https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17>
- Qwen：<https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF>

> 调试时也可以手动把模型放到上表路径；正常使用不需要 adb push。

## 5. 云端 LLM 翻译（可选）

设置 → 翻译引擎 → 云端 LLM：

- Base URL 默认 `https://api.deepseek.com/v1`，可填任意 OpenAI 兼容服务。
- **支持多供应商**：每个供应商独立保存名称、Base URL、API Key 和模型；切换不会覆盖其他供应商的 Key。
- **拨测**：发一条最小对话请求，显示是否连通、HTTP 错误或耗时。
- **拉取模型**：请求 `GET /models`，弹窗列出可用模型，点选后自动写入当前供应商；需要供应商支持 OpenAI 兼容的模型列表接口。
- 模型默认 `deepseek-chat`；API Key 只保存在本机应用私有存储，不入库、不写日志。
- 开关关闭：完全保持原来的本地 Qwen 行为，不发起任何网络请求。
- 开关开启：识别文本优先发往云端翻译；请求失败或返回空时自动回退本地 Qwen。
- 只上传 ASR 识别出的文本，不上传音频、不截屏。
- 自定义供应商（非 `api.deepseek.com`）会自动发送 `reasoning_effort: none` 关闭思考，无需手动配置；DeepSeek 官方 `deepseek-chat`/`deepseek-flash` 本身不思考。

> 自定义 Base URL 若使用 `http://`，应用已允许明文流量（`usesCleartextTraffic`）；
> 公网服务建议使用 HTTPS。

## 6. 实测（Redmi Note 9 / Android 12 / 天玑 800U）

- 中文 partial：约 250ms 更新一次，识别准确率明显优于 Vosk 小模型。
- 日 → 中：SenseVoice 识别日语（6.1s 音频）→ 本地 Qwen 中文，翻译约 2.3s；DeepSeek 云端约 1.0s。
- 英 → 中：SenseVoice 识别英语（6.4s 音频）→ 本地 Qwen 中文，翻译约 1.5s；DeepSeek 云端约 0.7s。
- 中 → 英：流式 zipformer + Qwen，15~19 字约 1.5~1.8s。
- 字幕节奏：每 3 秒断句一次，译文延迟约 **1.5~3 秒**；从开口算约 4~6 秒。
- 悬浮窗：已去掉声浪，默认高度约为原来的 60%，长句可滚动完整查看；停留时长默认 10 秒，可在设置调整。
- 内存：约 1.2GB（ASR 330MB / SenseVoice 239MB + Qwen 491MB + 运行时）。

> 云端测试说明：测试手机当时没有默认数据网络，因此通过 `adb reverse` 将请求转发到
> 本机中继再访问 DeepSeek；手机有正常网络时由应用直接请求同一接口。
> DeepSeek 对同一句日文/英文的译文明显比 0.5B 本地模型自然，且耗时更短。

> 翻译质量说明：0.5B 模型能读懂，但偏生硬，专有名词容易出错。
> 追求质量换 Qwen2.5-1.5B（更慢）、或接云端翻译。Q4_0 量化版在本机实测反而更慢/更容易跑偏，默认仍用 Q4_K_M。

## 7. 已知限制

- 当前端到端支持：**中 → 英、英 → 中、日 → 中、韩 → 中**；法/俄语音识别暂未接入。
- 日/韩译文质量受 0.5B 模型限制，偏生硬；追求质量可开启云端 LLM。
- 云端模式需要网络；无网络时自动回退本地，不会中断字幕。
- 首次使用需下载约 1.01GB 模型，建议在 Wi‑Fi 下完成。
- 长时间运行会发热、耗电；中端机建议插电使用。
- 被采集应用设置 `allowAudioPlaybackCapture=false`、使用 DRM 或通话音频时无法采集。
- Android 14+ 前台服务必须从可见界面启动；MIUI 对悬浮窗权限有额外管控。

## 8. 目录

```
app/src/main/java/com/catfee/yingling/
  audio/      AudioPlaybackCapture + 重采样 + 电平
  engine/     Sherpa/SenseVoice、Qwen JNI、模型目录与下载器
  service/    前台服务 + 悬浮窗 + 模型下载服务
  ui/         Compose 三页与主题
app/src/main/cpp/
  llama_jni.cpp  Qwen 推理 JNI
  CMakeLists.txt 只编译 llama.cpp（-O3 + dotprod）
scripts/fetch-llama.ps1
```

## 9. 许可

- sherpa-onnx：Apache-2.0
- llama.cpp：MIT
- Qwen2.5：Apache-2.0
- ZCOOL XiaoWei（标题字体）：SIL OFL 1.1，仅打包标题所需字形，许可见 `docs/fonts/OFL-ZCOOL-XiaoWei.txt`
- whisper.cpp / Whisper：MIT（代码已移除，仅历史提交保留）
