#include <jni.h>
#include <mutex>
#include "AudioEngine.h"

// Stable lifetime makes concurrent atomic parameter writes safe during loading
// and shutdown. Control operations never take a mutex used by the callback.
static AudioEngine engine;
static std::mutex controlMutex;

extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_startEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(controlMutex);
    return engine.start();
}
extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_stopEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(controlMutex);
    engine.stop();
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_isRunning(JNIEnv*, jobject) {
    return engine.isRunning();
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_lastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(controlMutex);
    return env->NewStringUTF(engine.lastError().c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_loadNamModel(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(controlMutex);
    const bool result = engine.loadNamModel(chars);
    env->ReleaseStringUTFChars(path, chars);
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_loadIrModel(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(controlMutex);
    const bool result = engine.loadIrModel(chars);
    env->ReleaseStringUTFChars(path, chars);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setInputGain(JNIEnv*, jobject, jfloat gain) {
    engine.setInputGain(gain);
}
extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setOutputGain(JNIEnv*, jobject, jfloat gain) {
    engine.setOutputGain(gain);
}
extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setNamBypass(JNIEnv*, jobject, jboolean bypass) {
    engine.setNamBypass(bypass);
}
extern "C" JNIEXPORT void JNICALL
Java_org_acoustixaudio_a2rack_AudioEngine_setIrBypass(JNIEnv*, jobject, jboolean bypass) {
    engine.setIrBypass(bypass);
}
