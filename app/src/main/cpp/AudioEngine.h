#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <memory>
#include <string>
#include "NAM/get_dsp.h"
#include "dsp/ImpulseResponse.h"

// All control operations are serialized by the JNI bridge. Only the callback
// touches active DSP objects while streams are running.
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine() = default;
    ~AudioEngine();
    bool start();
    void stop();
    bool loadNamModel(const std::string& path);
    bool loadIrModel(const std::string& path);
    void setInputGain(float gain);
    void setOutputGain(float gain);
    void setNamBypass(bool bypass);
    void setIrBypass(bool bypass);
    void setMuted(bool muted) { mMuted.store(muted); }
    void setDeviceIds(int input, int output) { mInputDeviceId = input; mOutputDeviceId = output; }
    bool isRunning() const { return mRunning.load() && streamError() == oboe::Result::OK; }
    bool isRecoverableError() const { return streamError() == oboe::Result::ErrorDisconnected; }
    std::string lastError() const;
    // Versioned numeric snapshot; queried only on the management worker.
    std::array<double, 23> diagnostics();
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void*, int32_t) override;


private:
    static constexpr int kBlockSize = 128;
    // Each stream owns its error callback and epoch. A late error from an old
    // device cannot mark a newly opened stream as disconnected.
    class StreamErrors : public oboe::AudioStreamErrorCallback {
    public:
        StreamErrors(AudioEngine& owner, uint64_t token) : owner(owner), token(token) {}
        bool onError(oboe::AudioStream*, oboe::Result error) override {
            owner.reportError(token, error);
            // The Kotlin management worker owns stop/close/reopen. Oboe must
            // not close concurrently with that worker.
            return true;
        }
    private:
        AudioEngine& owner;
        uint64_t token;
    };
    oboe::Result streamError() const {
        return static_cast<oboe::Result>(static_cast<int32_t>(mStreamState.load()));
    }
    void reportError(uint64_t token, oboe::Result error) {
        auto expected = token;
        mStreamState.compare_exchange_strong(expected, token | static_cast<uint32_t>(error));
    }
    uint64_t streamToken() const { return mStreamState.load() & 0xffffffff00000000ULL; }
    struct ModelUpdate {
        bool isNam;
        std::unique_ptr<nam::DSP> nam;
        std::unique_ptr<dsp::ImpulseResponse> ir;
        std::atomic<bool> applied{false};
    };
    bool publish(ModelUpdate& update);
    void applyPendingUpdate();
    bool openPlaybackStream();
    bool openRecordStream();
    void closeStreams();

    std::shared_ptr<oboe::AudioStream> mPlayStream;
    std::shared_ptr<oboe::AudioStream> mRecordStream;
    std::unique_ptr<nam::DSP> mNamModel;
    std::unique_ptr<dsp::ImpulseResponse> mIrModel;
    std::atomic<ModelUpdate*> mPending{nullptr};
    std::array<float, kBlockSize> mMono{};
    std::array<float, kBlockSize> mNamOutput{};
    std::atomic<float> mInputGain{0.5f};
    std::atomic<float> mOutputGain{0.5f};
    std::atomic<bool> mNamBypass{false};
    std::atomic<bool> mIrBypass{false};
    std::atomic<bool> mRunning{false};
    std::atomic<bool> mProcessingFailed{false};
    std::atomic<bool> mMuted{true};
    std::atomic<uint64_t> mStreamState{0};
    uint32_t mStreamEpoch = 0;
    int mInputDeviceId = 0;
    int mOutputDeviceId = 0;
    std::string mLastError;
    std::atomic<float> mInputPeak{0.0f};
    std::atomic<float> mOutputPeak{0.0f};
    std::atomic<bool> mInputClip{false};
    std::atomic<bool> mOutputClip{false};
    std::atomic<uint32_t> mShortReads{0};
    uint32_t mDiagnosticId = 0;
    std::array<double, 23> mLastDiagnostics{};
    static void recordPeak(std::atomic<float>& accumulator, float peak) {
        float previous = accumulator.load(std::memory_order_relaxed);
        while (peak > previous && !accumulator.compare_exchange_weak(
                previous, peak, std::memory_order_relaxed)) {}
    }
};

static_assert(std::atomic<float>::is_always_lock_free, "Audio parameters must be lock-free");
static_assert(std::atomic<bool>::is_always_lock_free, "Audio flags must be lock-free");
static_assert(std::atomic<void*>::is_always_lock_free, "Model handoff must be lock-free");

static_assert(std::atomic<uint64_t>::is_always_lock_free, "Stream error state must be lock-free");
