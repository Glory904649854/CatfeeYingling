#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "llama.h"

#define TAG "YingLingQwen"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct QwenCtx {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

std::string trim(const std::string &s) {
    size_t start = s.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    size_t end = s.find_last_not_of(" \t\r\n");
    return s.substr(start, end - start + 1);
}

std::string generate(QwenCtx *qc, const std::string &prompt) {
    llama_memory_t memory = llama_get_memory(qc->ctx);
    if (memory != nullptr) {
        llama_memory_clear(memory, true);
    }

    std::vector<llama_token> tokens(prompt.size() + 32);
    int32_t n = llama_tokenize(
        qc->vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            qc->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
        );
    }
    if (n <= 0) return "";
    tokens.resize(static_cast<size_t>(n));

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    if (llama_decode(qc->ctx, llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()))) != 0) {
        llama_sampler_free(sampler);
        return "";
    }

    constexpr int kMaxTokens = 64;
    for (int i = 0; i < kMaxTokens; ++i) {
        const llama_token id = llama_sampler_sample(sampler, qc->ctx, -1);
        if (llama_vocab_is_eog(qc->vocab, id)) break;

        char buffer[256];
        const int32_t len = llama_token_to_piece(qc->vocab, id, buffer, sizeof(buffer), 0, true);
        if (len > 0) {
            out.append(buffer, static_cast<size_t>(len));
        }
        llama_sampler_accept(sampler, id);

        llama_token next = id;
        if (llama_decode(qc->ctx, llama_batch_get_one(&next, 1)) != 0) break;
    }

    llama_sampler_free(sampler);
    return trim(out);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_catfee_yingling_engine_QwenBridge_nativeInit(
        JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    if (modelPath == nullptr) return 0;

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;

    llama_model_params modelParams = llama_model_default_params();

    llama_model *model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 512;
    ctxParams.n_batch = 256;
    ctxParams.n_threads = 6;
    ctxParams.n_threads_batch = 6;

    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *qc = new QwenCtx();
    qc->model = model;
    qc->ctx = ctx;
    qc->vocab = llama_model_get_vocab(model);
    LOGD("model loaded");
    return reinterpret_cast<jlong>(qc);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_catfee_yingling_engine_QwenBridge_nativeTranslate(
        JNIEnv *env, jobject /* thiz */, jlong handle, jstring text) {
    auto *qc = reinterpret_cast<QwenCtx *>(handle);
    if (qc == nullptr || text == nullptr) return env->NewStringUTF("");

    const char *raw = env->GetStringUTFChars(text, nullptr);
    std::string input = raw != nullptr ? raw : "";
    if (raw != nullptr) env->ReleaseStringUTFChars(text, raw);
    if (input.empty()) return env->NewStringUTF("");

    const std::string prompt =
        "<|im_start|>system\n"
        "Translate the Chinese subtitle into concise English. Output only the translation.<|im_end|>\n"
        "<|im_start|>user\n" + input + "<|im_end|>\n"
        "<|im_start|>assistant\n";

    const std::string out = generate(qc, prompt);
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_catfee_yingling_engine_QwenBridge_nativeRelease(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *qc = reinterpret_cast<QwenCtx *>(handle);
    if (qc == nullptr) return;
    if (qc->ctx != nullptr) llama_free(qc->ctx);
    if (qc->model != nullptr) llama_model_free(qc->model);
    delete qc;
}
