#include <jni.h>

#include <string>
#include <vector>

#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_catfee_yingling_engine_WhisperBridge_nativeInit(
        JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    if (modelPath == nullptr) return 0;

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_catfee_yingling_engine_WhisperBridge_nativeTranscribe(
        JNIEnv *env, jobject /* thiz */, jlong handle, jshortArray pcm,
        jboolean translate, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr || pcm == nullptr) return env->NewStringUTF("");

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<jshort> samples(static_cast<size_t>(n));
    env->GetShortArrayRegion(pcm, 0, n, samples.data());

    std::vector<float> audio(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        audio[static_cast<size_t>(i)] = samples[static_cast<size_t>(i)] / 32768.0f;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate == JNI_TRUE;
    params.n_threads = 4;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.detect_language = false;
    params.temperature = 0.0f;

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
    }
    params.language = lang;

    const int rc = whisper_full(ctx, params, audio.data(), n);

    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }
    if (rc != 0) return env->NewStringUTF("");

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;

        std::string part(text);
        const size_t start = part.find_first_not_of(" \t\n\r");
        if (start == std::string::npos) continue;
        const size_t end = part.find_last_not_of(" \t\n\r");
        if (!out.empty()) out += " ";
        out += part.substr(start, end - start + 1);
    }

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_catfee_yingling_engine_WhisperBridge_nativeRelease(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    if (handle != 0) {
        whisper_free(reinterpret_cast<whisper_context *>(handle));
    }
}
