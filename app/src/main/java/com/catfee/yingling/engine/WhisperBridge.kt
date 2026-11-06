package com.catfee.yingling.engine

/** whisper.cpp 的 JNI 桥。模型由外部放入应用私有 models 目录。 */
internal object WhisperBridge {

    init {
        System.loadLibrary("yingling_whisper")
    }

    external fun nativeInit(modelPath: String): Long

    external fun nativeTranscribe(
        handle: Long,
        pcm: ShortArray,
        translate: Boolean,
        language: String?,
    ): String

    external fun nativeRelease(handle: Long)
}
