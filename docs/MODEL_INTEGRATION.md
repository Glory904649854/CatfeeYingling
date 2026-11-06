# 模型接入指南

当前版本只有一个 `DemoRecognitionEngine`。真实模型接入只需实现 `RecognitionEngine`：

```kotlin
interface RecognitionEngine {
    val name: String
    var onResult: ((RecognitionResult) -> Unit)?
    fun start()
    fun onAudio(pcm16k: ShortArray, level: Float)   // 16kHz / mono / 16bit
    fun pause()
    fun resume()
    fun stop()
}
```

然后把 `RecognitionService.buildEngine()` 的返回值换掉即可。音频层已经把 48kHz 立体声转成 **16kHz 单声道 PCM**，无需再处理采样率。

---

## 1. 推荐架构

```
AudioPlaybackCapture (48k stereo)
  → 16k mono (已实现)
  → Silero VAD 断句
  → Whisper small / SenseVoice（ASR + LID）
  → Qwen3-0.6B/1.7B（翻译成中文）
  → RecognitionResult → 悬浮窗
```

六语种（中/英/日/韩/法/俄）建议：

- **ASR 用 Whisper 单模型**，自带语种识别，覆盖全部六种；
- **翻译用 Qwen 单模型**，一个模型覆盖六种源语言，避免维护 5 个 OPUS-MT 语向；
- VAD 用 Silero，语言无关。

---

## 2. sherpa-onnx 接入（推荐）

### 2.1 依赖

在 `app/build.gradle.kts` 增加 AAR（以官方发布版本为准）：

```kotlin
dependencies {
    implementation("com.k2-fsa.sherpa.onnx:sherpa-onnx-android:1.12.0") // 版本按 release 调整
}
```

若 Maven 坐标不可用，从 [sherpa-onnx Releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载 `sherpa-onnx-*.aar`，放入 `app/libs/`，再：

```kotlin
implementation(files("libs/sherpa-onnx.aar"))
```

### 2.2 模型

从 GitHub Releases 的 `asr-models` 标签下载（免费、无需账号）：

- `sherpa-onnx-whisper-small`（六语种，约 180MB q5）
- `sherpa-onnx-silero-vad`（约 2MB）
- 可选：中文标点模型（CT-Transformer）

不要塞进 APK，放 `filesDir/models/`，首次启动下载或让用户手动导入。

### 2.3 代码骨架

```kotlin
class SherpaRecognitionEngine(
    private val modelDir: File,
) : RecognitionEngine {

    override val name = "Whisper small"
    override var onResult: ((RecognitionResult) -> Unit)? = null

    private val vad by lazy { VadModelConfig(...) }
    private val recognizer by lazy { OfflineRecognizer(...) }
    private var buffer = ShortArray(0)

    override fun onAudio(pcm16k: ShortArray, level: Float) {
        // 1) VAD 判断语音/静音
        // 2) 累积到 2~8 秒的语音段
        // 3) 静音超过 500ms 或段长超限 → recognizer.decode(segment)
        // 4) 得到 sourceText + lang，再调用翻译引擎
        // 5) onResult(RecognitionResult(source, translated, lang))
    }
}
```

要点：

- Whisper 是非流式模型，必须靠 VAD 切段；单段建议 2~8 秒，太短准确率明显下降。
- 语种识别用 Whisper 自带 LID，**连续两段一致再锁定**，避免短句抖动。
- 静音段一定要跳过，否则 Whisper 容易幻觉出「谢谢观看」。
- 中文输出可能是繁体，接 `OpenCC` 转简体后再翻译。

---

## 3. whisper.cpp 接入（备选）

- 仓库：<https://github.com/ggml-org/whisper.cpp>（MIT）
- Android 示例：`examples/whisper.android`
- 模型：`ggml-small-q5_1.bin`（约 190MB）
- 优点：依赖少、JNI 简单；缺点：VAD、标点、端测工程化要自己补。

---

## 4. Qwen 翻译接入

### 4.1 运行时

- llama.cpp（MIT）Android 交叉编译；或
- 阿里 MNN（Apache-2.0）的 LLM Android demo。

模型：`Qwen3-0.6B-GGUF`（约 400MB Q4）或 `Qwen2.5-1.5B-Instruct-GGUF`（约 1GB Q4）。

### 4.2 Prompt（字幕场景）

```text
你是实时字幕翻译引擎。把 <src> 翻译成简体中文，口语化、简短，只输出译文。
不要解释，不要加引号，不要输出原文。
如果 <src> 已经是中文，直接原样返回。
最近上下文（仅供参考，不要翻译）：
上一句：{prev1}
上上句：{prev2}
<src>{text}</src>
```

### 4.3 注意事项

- **Qwen3 默认可能输出思考过程**：prompt 里加 `/no_think`，或使用 non-thinking 模式，否则延迟不可接受。
- 每次只翻译一句，`max_tokens` 控制在 64~96，避免超长输出。
- 保留最近 2 句上下文，能显著改善代词与术语一致性。
- 日/韩→中的离线小模型质量有限，若追求质量，只能上更大模型或云端。

---

## 5. 语言识别与路由

- Whisper 每个语音段返回 `lang`，用它做 LID。
- 策略：连续 2 段相同才锁定；连续 3 段不同才切换。
- UI 已提供「手动锁定语种」，影视剧/综艺建议手动锁定。
- 混合语种不必特殊处理：逐段识别，按段语言路由翻译即可。

---

## 6. 性能预算（旗舰 / 中端机量级）

| 环节 | 参考值 |
|---|---|
| 采集 + 重采样 | < 5% CPU |
| Whisper small q5 | RTF ≈ 0.25~0.5（旗舰）/ 0.4~0.7（中端） |
| Qwen3-0.6B Q4 | 30~60 tok/s（旗舰）/ 15~30 tok/s（中端） |
| OPUS-MT / M2M 备选 | 单句 0.05~1.5s |
| 端到端字幕延迟 | 2~3.5s |

省电建议：

- 只在 VAD 判定有语音时跑 ASR；
- 16kHz 单声道，不要保留 48kHz 缓冲；
- 采集线程与推理线程分离，推理用 2~4 个大核；
- 悬浮窗隐藏时仍然可以只跑识别，但可下调刷新率。

---

## 7. 许可清单

| 组件 | License | 备注 |
|---|---|---|
| Whisper 代码与权重 | MIT | 可商用 |
| whisper.cpp | MIT | 可商用 |
| sherpa-onnx | Apache-2.0 | 可商用 |
| Silero VAD | MIT | 可商用 |
| Qwen3 | Apache-2.0 | 可商用 |
| llama.cpp / MNN | MIT / Apache-2.0 | 可商用 |
| OpenCC | Apache-2.0 | 可商用 |
| OPUS-MT | CC-BY-4.0 | 需署名 |
| M2M-100 | MIT | 可商用 |
| NLLB-200 | CC-BY-NC-4.0 | **非商用** |
| SenseVoice | MIT（以模型卡为准） | 仅中英日韩粤 |
