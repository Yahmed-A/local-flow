// JNI bridge to whisper.cpp for com.jaryah.localflow.Whisper
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "LocalFlow", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jaryah_localflow_Whisper_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_jaryah_localflow_Whisper_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

// Share of Arabic in Whisper's ar-vs-en language guess for the clip (first 30 s).
JNIEXPORT jfloat JNICALL
Java_com_jaryah_localflow_Whisper_nativeArabicShare(JNIEnv *env, jclass, jlong jctx,
                                                     jfloatArray jaudio, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(jctx);
    jsize n = env->GetArrayLength(jaudio);
    jfloat *audio = env->GetFloatArrayElements(jaudio, nullptr);
    float share = 0.f;
    if (whisper_pcm_to_mel(ctx, audio, n, threads) == 0) {
        std::vector<float> probs(whisper_lang_max_id() + 1, 0.f);
        if (whisper_lang_auto_detect(ctx, 0, threads, probs.data()) >= 0) {
            float ar = probs[whisper_lang_id("ar")], en = probs[whisper_lang_id("en")];
            share = ar / std::max(ar + en, 1e-9f);
        }
    }
    env->ReleaseFloatArrayElements(jaudio, audio, JNI_ABORT);
    return share;
}

// Returns one "t0<TAB>t1<TAB>text" string per segment; t0/t1 in seconds.
JNIEXPORT jobjectArray JNICALL
Java_com_jaryah_localflow_Whisper_nativeTranscribe(JNIEnv *env, jclass, jlong jctx,
                                                    jfloatArray jaudio, jstring jlang,
                                                    jstring jprompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(jctx);
    const char *lang = env->GetStringUTFChars(jlang, nullptr);
    const char *prompt = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = std::string(lang) == "auto" ? nullptr : lang;
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;              // condition_on_previous_text=False
    p.initial_prompt = prompt;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;

    jsize n = env->GetArrayLength(jaudio);
    jfloat *audio = env->GetFloatArrayElements(jaudio, nullptr);
    int rc = whisper_full(ctx, p, audio, n);
    env->ReleaseFloatArrayElements(jaudio, audio, JNI_ABORT);

    int segs = rc == 0 ? whisper_full_n_segments(ctx) : 0;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(segs, strCls, nullptr);
    for (int i = 0; i < segs; i++) {
        char head[64];
        snprintf(head, sizeof head, "%.2f\t%.2f\t",
                 whisper_full_get_segment_t0(ctx, i) / 100.0,
                 whisper_full_get_segment_t1(ctx, i) / 100.0);
        std::string s = std::string(head) + whisper_full_get_segment_text(ctx, i);
        // build the Java string from UTF-8 bytes (NewStringUTF rejects 4-byte sequences)
        jbyteArray bytes = env->NewByteArray((jsize) s.size());
        env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
        jclass cs = env->FindClass("java/nio/charset/StandardCharsets");
        jobject utf8 = env->GetStaticObjectField(cs, env->GetStaticFieldID(cs, "UTF_8", "Ljava/nio/charset/Charset;"));
        jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/nio/charset/Charset;)V");
        jobject js = env->NewObject(strCls, ctor, bytes, utf8);
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js); env->DeleteLocalRef(bytes); env->DeleteLocalRef(utf8); env->DeleteLocalRef(cs);
    }
    if (rc != 0) LOG("whisper_full failed: %d", rc);

    env->ReleaseStringUTFChars(jlang, lang);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
    return out;
}

}
