# 应灵 · CatfeeAudio

> 在 Android 上捕获其他应用播放的音频，流式识别中文，逐句翻译成英文，用悬浮字幕显示。
> 全部本地推理，不依赖云端。

---

## 1. 已实现

- **系统音频采集**：`AudioPlaybackCapture` + 一次 `MediaProjection` 授权（仅取音频，不录屏）。
- **按应用监听**：多选应用，按 UID 精确捕获；也可切到「全部媒体」按类型捕获。
- **悬浮字幕窗**：紧凑布局，只保留状态/方向 + 中文原文 + 英文译文 + 暂停/停止；去掉声浪区，长句可滚动完整阅读。
- **流式 ASR**：sherpa-onnx 中英双语 zipformer，边说话边出中文 partial（约 250ms）。
- **逐句翻译**：Qwen2.5-0.5B-Instruct（llama.cpp），每 4 秒强制断句后翻译。
- **AI Studio 风格本体**：深色扁平、22dp 圆角、薄荷绿→天蓝渐变、三页导航。

## 2. 架构

```
AudioPlaybackCapture (48k stereo)
  → 16k mono
  → sherpa-onnx Streaming Zipformer（中文 partial）
  → 端点检测 / 3 秒强制断句
  → Qwen2.5-0.5B-Instruct（llama.cpp，中 → 英，过滤中文输出）
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
| Qwen2.5-0.5B-Instruct Q4_K_M | 约 491MB | `models/qwen2.5-0.5b-instruct-q4_k_m.gguf` |

```bash
adb shell mkdir -p /sdcard/Android/data/com.catfee.yingling/files/models/sherpa-zh-en
adb push sherpa-zh-en/. /sdcard/Android/data/com.catfee.yingling/files/models/sherpa-zh-en/
adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Android/data/com.catfee.yingling/files/models/
```

模型来源：
- sherpa：<https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20>
- Qwen：<https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF>

## 5. 实测（Redmi Note 9 / Android 12 / 天玑 800U）

- 中文 partial：约 250ms 更新一次，识别准确率明显优于 Vosk 小模型。
- 翻译：15~19 个中文字符 → **1.5~1.8 秒**；20~30 字约 2~3 秒。
- 字幕节奏：每 3 秒断句一次，英文延迟约 **1.5~3 秒**；从开口算约 4~6 秒。
- 悬浮窗：已去掉声浪，默认高度约为原来的 60%，长句可滚动完整查看。
- 内存：约 1.2GB（ASR 330MB + Qwen 491MB + 运行时）。

> 翻译质量说明：0.5B 模型能读懂，但偏生硬，专有名词容易出错。
> 追求质量换 Qwen2.5-1.5B（更慢）、或接云端翻译。Q4_0 量化版在本机实测反而更慢/更容易跑偏，默认仍用 Q4_K_M。

## 6. 已知限制

- 当前只做 **中 → 英**；目标语言固定英语，源语言建议锁定中文。
- 模型文件需手动放置，未内置下载器。
- 长时间运行会发热、耗电；中端机建议插电使用。
- 被采集应用设置 `allowAudioPlaybackCapture=false`、使用 DRM 或通话音频时无法采集。
- Android 14+ 前台服务必须从可见界面启动；MIUI 对悬浮窗权限有额外管控。

## 7. 目录

```
app/src/main/java/com/catfee/yingling/
  audio/      AudioPlaybackCapture + 重采样 + 电平
  engine/     sherpa 流式引擎、Qwen JNI、模型查找
  service/    前台服务 + 悬浮窗
  ui/         Compose 三页与主题
app/src/main/cpp/
  llama_jni.cpp  Qwen 推理 JNI
  CMakeLists.txt 只编译 llama.cpp（-O3 + dotprod）
scripts/fetch-llama.ps1
```

## 8. 许可

- sherpa-onnx：Apache-2.0
- llama.cpp：MIT
- Qwen2.5：Apache-2.0
- whisper.cpp / Whisper：MIT（代码已移除，仅历史提交保留）
