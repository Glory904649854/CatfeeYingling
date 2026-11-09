package com.catfee.yingling.engine

/** llama.cpp 的 JNI 桥，用于 Qwen 文本翻译。 */
internal object QwenBridge {

    init {
        System.loadLibrary("yingling_llama")
    }

    external fun nativeInit(modelPath: String): Long

    /** 中文 -> 英文。失败返回空串。 */
    external fun nativeTranslate(handle: Long, text: String): String

    external fun nativeRelease(handle: Long)
}
