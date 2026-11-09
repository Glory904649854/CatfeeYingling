# 应灵 · CatfeeAudio

> 在 Android 上捕获其他应用播放的音频，流式识别中文，逐句翻译成英文，用悬浮字幕显示。
> 默认全部本地推理；可选接入任意 OpenAI 兼容云端 LLM 提升翻译质量。

---

## 1. 已实现

- **系统音频采集**：`AudioPlaybackCapture` + 一次 `MediaProjection` 授权（仅取音频，不录屏）。
- **按应用监听**：多选应用，按 UID 精确捕获；也可切到「全部媒体」按类型捕获。
- **悬浮字幕窗**：紧凑布局，保留最近 3 句的「原文 + 译文」，新句滑入；每句停留 6 秒后渐隐移除，不会长期占屏，可滚动回看。
- **流式 ASR**：中文/英文用 sherpa-onnx 流式 zipformer；日/韩/英用 SenseVoice 离线多语种识别。
- **逐句翻译**：默认 Qwen2.5-0.5B-Instruct（llama.cpp）；可在设置中开启云端 LLM（自定义 Base URL / Key / 模型），云端失败自动回退本地。支持目标中文或英文；日/韩/英 → 中、中 → 英。
- **AI Studio 风格本体**：深色扁平、22dp 圆角、薄荷绿→天蓝渐变、三页导航。

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

## 4. 模型（不入库，需手动放入手机）

| 模型 | 大小 | 放置路径 |
|---|---|---|
| sherpa zipformer 中英双语 | encoder 330MB + decoder 14MB + joiner 13MB + tokens | `models/sherpa-zh-en/` |
| SenseVoice 多语种（中英日韩粤） | 239MB + tokens | `models/sensevoice/` |
| Qwen2.5-0.5B-Instruct Q4_K_M | 约 491MB | `models/qwen2.5-0.5b-instruct-q4_k_m.gguf` |

```bash
adb shell mkdir -p /sdcard/Android/data/com.catfee.yingling/files/models/sherpa-zh-en
adb push sherpa-zh-en/. /sdcard/Android/data/com.catfee.yingling/files/models/sherpa-zh-en/

adb shell mkdir -p /sdcard/Android/data/com.catfee.yingling/files/models/sensevoice
adb push sensevoice/. /sdcard/Android/data/com.catfee.yingling/files/models/sensevoice/

adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.catfee.yingling/files/models/
```

模型来源：
- sherpa zipformer：<https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20>
- SenseVoice：<https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17>
- Qwen：<https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF>

## 5. 云端 LLM 翻译（可选）

设置 → 翻译引擎 → 云端 LLM：

- Base URL 默认 `https://api.deepseek.com/v1`，可填任意 OpenAI 兼容服务。
- 模型默认 `deepseek-chat`；API Key 只保存在本机应用私有存储，不入库、不写日志。
- 开关关闭：完全保持原来的本地 Qwen 行为，不发起任何网络请求。
- 开关开启：识别文本优先发往云端翻译；请求失败或返回空时自动回退本地 Qwen。
- 只上传 ASR 识别出的文本，不上传音频、不截屏。

> 自定义 Base URL 若使用 `http://`，应用已允许明文流量（`usesCleartextTraffic`）；
> 公网服务建议使用 HTTPS。

## 6. 实测（Redmi Note 9 / Android 12 / 天玑 800U）

- 中文 partial：约 250ms 更新一次，识别准确率明显优于 Vosk 小模型。
- 日 → 中：SenseVoice 识别日语（6.1s 音频）→ 本地 Qwen 中文，翻译约 2.3s；DeepSeek 云端约 1.0s。
- 英 → 中：SenseVoice 识别英语（6.4s 音频）→ 本地 Qwen 中文，翻译约 1.5s；DeepSeek 云端约 0.7s。
- 中 → 英：流式 zipformer + Qwen，15~19 字约 1.5~1.8s。
- 字幕节奏：每 3 秒断句一次，译文延迟约 **1.5~3 秒**；从开口算约 4~6 秒。
- 悬浮窗：已去掉声浪，默认高度约为原来的 60%，长句可滚动完整查看；每句停留 6 秒后渐隐。
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
- 模型文件需手动放置，未内置下载器。
- 长时间运行会发热、耗电；中端机建议插电使用。
- 被采集应用设置 `allowAudioPlaybackCapture=false`、使用 DRM 或通话音频时无法采集。
- Android 14+ 前台服务必须从可见界面启动；MIUI 对悬浮窗权限有额外管控。

## 8. 目录

```
app/src/main/java/com/catfee/yingling/
  audio/      AudioPlaybackCapture + 重采样 + 电平
  engine/     sherpa 流式引擎、SenseVoice、Qwen JNI、云端 Translator、模型查找
  service/    前台服务 + 悬浮窗
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
- whisper.cpp / Whisper：MIT（代码已移除，仅历史提交保留）
