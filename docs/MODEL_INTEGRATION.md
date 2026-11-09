# 模型接入与调优

当前实现：

```
sherpa-onnx Streaming Zipformer（中英双语）
  → 端点检测 / 4 秒强制断句
  → Qwen2.5-0.5B-Instruct（llama.cpp）
```

相关代码：

- `engine/SherpaStreamingEngine.kt`：ASR partial、断句、翻译调度
- `engine/QwenBridge.kt` + `cpp/llama_jni.cpp`：llama.cpp JNI
- `engine/ModelStore.kt`：模型路径查找
- `service/RecognitionService.kt`：按目标语言选择引擎

---

## 1. sherpa-onnx 流式 ASR

### 1.1 AAR

`app/libs/sherpa-onnx.aar`（v2.0.1，Apache-2.0）已入库，Gradle 以本地 AAR 引用：

```kotlin
implementation(files("libs/sherpa-onnx.aar"))
```

### 1.2 模型

目录结构：

```
models/sherpa-zh-en/
  encoder-epoch-99-avg-1.onnx   # 330MB
  decoder-epoch-99-avg-1.onnx   # 14MB
  joiner-epoch-99-avg-1.onnx    # 13MB
  tokens.txt
```

下载：<https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20>

### 1.3 关键 API 注意点

```kotlin
// 绝对路径加载模型时，AssetManager 必须为 null，否则原生层会 abort
val ctor = OnlineRecognizer::class.java.getConstructor(
    AssetManager::class.java,
    OnlineRecognizerConfig::class.java,
)
val recognizer = ctor.newInstance(null, config) as OnlineRecognizer

// 第二个参数是采样率，不是样本数！
stream.acceptWaveform(samples, 16_000)
```

### 1.4 断句策略

- 正常端点：`recognizer.isEndpoint(stream)`
- 课程/讲座长时间不停顿：`MAX_SEGMENT_MS = 4_000` 强制断句
- partial 每 250ms 更新一次悬浮窗

调大 `MAX_SEGMENT_MS`：译文更长、更连贯，但延迟增加；调小：字幕更频繁，但句子更碎。

---

## 2. Qwen 翻译

### 2.1 模型

`Qwen2.5-0.5B-Instruct-GGUF`，Q4_K_M，约 491MB：

<https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF>

### 2.2 推理参数

`cpp/llama_jni.cpp`：

- `n_ctx = 512`、`n_batch = 256`
- `n_threads = 6`（设备 8 核：2 大 + 6 小）
- 贪心采样，最多 64 token
- 每次翻译前清空 KV cache，保证不同句子互不影响
- 编译启用 `-O3` 与 `-march=armv8.2-a+dotprod+fp16`

### 2.3 Prompt

```
<|im_start|>system
Translate the Chinese subtitle into concise English. Output only the translation.<|im_end|>
<|im_start|>user
{中文}<|im_end|>
<|im_start|>assistant
```

0.5B 仍可能输出 “Sure, here is the translation:” 或引号，Kotlin 侧 `cleanTranslation()` 会清理。

### 2.4 性能实测

| 中文长度 | 耗时 |
|---|---|
| 13 字 | 1.5s |
| 20~24 字 | 1.3~2.4s |
| 28~33 字 | 2.3~4.1s |
| 37 字 | 2.4~4.7s |

想更快：
1. 缩短 `MAX_SEGMENT_MS` 到 3 秒（句子更短，输出更少）；
2. 换更小的 Q4_0 量化；
3. 换 `Qwen2.5-0.5B` 以外的更小模型（质量下降明显）；
4. 云端翻译（最快、质量最好，但需要网络/账号）。

想更准：
1. 换 `Qwen2.5-1.5B-Instruct`（约 1GB，速度约慢 2~3 倍）；
2. 在 prompt 中加入领域术语表；
3. 保留上一句作为上下文（会增加 token 与延迟）。

---

## 3. 语言方向

当前只支持目标 = 英语。源语言建议在 UI 锁定为中文；`sourceLanguage` 传给 Qwen 仅用于提示，不影响模型本身。

要支持其他目标语言，需要换翻译模型/提示词：
- 中 → 日/韩/法/俄：Qwen 提示词可改，但 0.5B 质量有限；
- 或接云端翻译 API（DeepL/Google/LLM）。

---

## 4. 许可

| 组件 | License |
|---|---|
| sherpa-onnx | Apache-2.0 |
| llama.cpp | MIT |
| Qwen2.5 | Apache-2.0 |
| 本仓库代码 | 未声明（自行补充） |
