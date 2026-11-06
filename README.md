# 应灵 · CatfeeAudio

> YINGLING — 在 Android 上捕获其他应用播放的音频，实时识别并翻译成中文字幕。
> 全部本地处理，不依赖云端；当前版本内置 Demo 引擎，模型接入点已预留。

---

## 1. 现在能跑什么

- **指定应用监听**：从已安装应用里多选，按 UID 精确捕获；也可切到「全部媒体」按类型捕获。
- **系统音频采集**：基于 Android 10+ 的 `AudioPlaybackCapture`，需一次 `MediaProjection` 授权（仅用于取音频，不录屏）。拿到的是应用输出的数字音频，插耳机/蓝牙也能采。
- **悬浮字幕窗**：显示实时声浪、运行状态、翻译方向（如「英 → 中」）、原文与中文译文，支持拖动、贴边、收起。
- **悬浮窗控制**：暂停/继续识别、停止会话、隐藏悬浮窗。
- **AI Studio 风格本体**：深色扁平化、22dp 圆角卡片、薄荷绿→天蓝渐变、三页底部导航（实时 / 应用 / 设置）。
- **Demo 识别引擎**：不装模型也能验证「采集 → 断句 → 识别 → 翻译 → 字幕」整条链路，六语种台词轮播。
- **Whisper 中→英（已接入）**：whisper.cpp 原生库 + `ggml-small-q5_1.bin`，中文语音直接输出英文；模型需手动放入应用私有目录（见下）。
- **前台服务**：持续监听、通知栏控制、Android 14+ `microphone` 前台服务类型。

## 2. 快速开始

### 环境

- Android Studio（支持 AGP 8.13.2）
- JDK 17
- Android SDK 36（compileSdk / targetSdk = 36，minSdk = 29）

### 构建

仓库不包含第三方 `whisper.cpp` 源码（约 40MB），首次构建前先拉取：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/fetch-whisper.ps1
```

然后：

```bash
gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

也可以直接用 Android Studio 打开 `CatfeeAudio` 目录同步运行。仓库使用阿里云镜像 + 腾讯 Gradle 镜像，国内网络可直接同步。

### 首次使用

1. 打开应用，在「应用」页勾选要监听的应用（或切换到「全部媒体」）。
2. 回到「实时」页，点「启动监听」，依次授予：录音、悬浮窗、通知，最后在系统弹窗里允许「录制或投射屏幕」。
   > 这个授权是 Android 解锁「采集其他应用音频」的唯一入口；应灵不会创建虚拟显示，也不会录屏。
3. 打开目标应用播放音频，悬浮窗会出现声浪与字幕。
4. 悬浮窗可拖动；「暂停」停止识别但保留会话，「停止」结束服务。

### 启用真实「中 → 英」

1. 下载 whisper.cpp 模型（MIT）：`ggml-small-q5_1.bin`，约 181MB。
2. 推到应用私有目录：

```bash
adb shell mkdir -p /sdcard/Android/data/com.catfee.yingling/files/models
adb push ggml-small-q5_1.bin /sdcard/Android/data/com.catfee.yingling/files/models/
```

3. App 内「翻译方向」把源语言设为中文、输出设为英语；启动后引擎标签会变成 `WHISPER`。

> 中端机跑 Whisper small 的 30 秒编码窗口偏慢，首条字幕可能需要数十秒；追求速度可换 `ggml-base-q5_1.bin`（约 57MB，质量下降），或在 `whisper_jni.cpp` 里调小 `params.audio_ctx`。

> 提示：若目标应用设置 `allowAudioPlaybackCapture=false`、使用 DRM 或 targetSdk < 29 且未开启采集，则系统不会提供音频，悬浮窗只会显示静默声浪。

## 3. 目录结构

```
CatfeeAudio/
├── app/src/main/java/com/catfee/yingling/
│   ├── MainActivity.kt              # 入口，edge-to-edge
│   ├── core/                        # 状态总线、偏好、语种、数据模型
│   ├── data/                        # 安装应用列表与图标
│   ├── audio/                       # AudioPlaybackCapture、PCM 重采样、电平
│   ├── engine/                      # 识别引擎接口 + Demo 实现
│   ├── service/                     # 前台服务 + 悬浮窗控制器
│   ├── ui/                          # Compose 主题 / 组件 / 三页
│   └── util/                        # 权限工具
├── docs/MODEL_INTEGRATION.md        # Whisper / sherpa-onnx / Qwen 接入指南
└── gradle/libs.versions.toml        # 版本目录
```

## 4. 关键实现说明

### 4.1 音频采集

当前 Android SDK 中 `AudioPlaybackCaptureConfiguration` 必须绑定 `MediaProjection`：

```kotlin
AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUid(uid)   // 指定应用；不填则按 usage 捕获全部媒体
    .build()
```

所以启动流程是：`MediaProjectionManager.createScreenCaptureIntent()` → 用户授权 → 前台服务先以 `microphone|mediaProjection` 类型启动 → 兑换 `MediaProjection` → 构建 `AudioRecord`。应灵只拿音频流，不创建 `VirtualDisplay`。

以 48kHz 立体声 16bit 采集，混单声道后用 3:1 抽取降到 16kHz，直接送给引擎。电平按 `-60dBFS ~ 0dBFS` 映射到 0~1，供声浪绘制。

### 4.2 悬浮窗

原生 `View` + `WindowManager`，`TYPE_APPLICATION_OVERLAY`。
- 窗口为 `FLAG_NOT_FOCUSABLE`，不抢输入焦点；
- 顶部区域可拖动，松手自动贴边；
- 声浪由 `WaveformView` 自己维护 44 格环形缓冲，20Hz 输入、30fps 平滑绘制；
- 暂停时声浪转灰并归零。

### 4.3 状态流

`AppState` 是单例 `MutableStateFlow` 总线：服务写入，Compose 与悬浮窗读取。
电平单独一条 flow，并只在 `Canvas` 绘制阶段读取，避免 20Hz 重组整页。

### 4.4 识别引擎

- `DemoRecognitionEngine`：默认引擎，按音量模拟断句，用于没有模型时跑通 UI 与采集链路。
- `WhisperRecognitionEngine`：检测到模型且目标语言为英语时启用；通过 JNI 调用 whisper.cpp，使用 `translate=true` 直接输出英文。
- 模型路径：`/sdcard/Android/data/com.catfee.yingling/files/models/ggml-small-q5_1.bin`（或 `ggml-base-q5_1.bin`）。

## 5. 已知限制

- **无法捕获所有应用**：被采集方可通过 `allowAudioPlaybackCapture=false` 或 `ALLOW_CAPTURE_BY_NONE` 拒绝；DRM 内容永远拿不到；通话类 `USAGE_VOICE_COMMUNICATION` 不允许采集。
- **Android 14+**：前台服务只能在应用可见时启动；`mediaProjection` 类型还要求先启动前台服务、再兑换授权（代码已按此顺序实现）。从后台拉起会抛 `ForegroundServiceStartNotAllowedException`（已做降级提示）。
- **隐私提示**：采集期间系统会显示录音/麦克风隐私标识，这是系统行为，无法隐藏。
- **未 root 无法全局强采**：要突破应用自拒绝，只能系统签名/提权，普通应用做不到。
- **Demo 引擎只模拟台词**，不产生真实识别结果。
- **Whisper small 在中端机上不实时的风险**：Whisper 固定 30 秒编码窗口，Redmi Note 9 级别设备单段推理可能需要数十秒；需要实时性时换 base 模型或调小 `audio_ctx`。

## 6. 模型接入

见 [docs/MODEL_INTEGRATION.md](docs/MODEL_INTEGRATION.md)。推荐组合：

| 层 | 方案 | License |
|---|---|---|
| VAD | Silero VAD | MIT |
| ASR | sherpa-onnx + Whisper small（六语种） | Apache-2.0 / MIT |
| 翻译 | Qwen3-0.6B / 1.7B（llama.cpp / MNN） | Apache-2.0 |
| 简繁 | OpenCC | Apache-2.0 |

## 7. 版本

- v0.1.0 — 项目骨架、采集、悬浮窗、三页 UI、Demo 引擎
