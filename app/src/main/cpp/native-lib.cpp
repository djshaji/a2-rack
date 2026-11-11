#include <jni.h>
#include <string>
#include "AudioEngine.h"

static AudioEngine *engine = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_startEngine(
        JNIEnv* env,
        jobject /* this */) {
    if (!engine) {
        engine = new AudioEngine();
    }
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_stopEngine(
        JNIEnv* env,
        jobject /* this */) {
    if (engine) {
        engine->stop();
        delete engine;
        engine = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_loadNamModel(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    if (!engine) return JNI_FALSE;
    const char *pathChars = env->GetStringUTFChars(path, nullptr);
    bool result = engine->loadNamModel(pathChars);
    env->ReleaseStringUTFChars(path, pathChars);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_loadIrModel(
        JNIEnv* env,
        jobject /* this */,
        jstring path) {
    if (!engine) return JNI_FALSE;
    const char *pathChars = env->GetStringUTFChars(path, nullptr);
    bool result = engine->loadIrModel(pathChars);
    env->ReleaseStringUTFChars(path, pathChars);
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setInputGain(
        JNIEnv* env,
        jobject /* this */,
        jfloat gain) {
    if (engine) {
        engine->setInputGain(gain);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setOutputGain(
        JNIEnv* env,
        jobject /* this */,
        jfloat gain) {
    if (engine) {
        engine->setOutputGain(gain);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setNamBypass(
        JNIEnv* env,
        jobject /* this */,
        jboolean bypass) {
    if (engine) {
        engine->setNamBypass(bypass == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setIrBypass(
        JNIEnv* env,
        jobject /* this */,
        jboolean bypass) {
    if (engine) {
        engine->setIrBypass(bypass == JNI_TRUE);
    }
}
