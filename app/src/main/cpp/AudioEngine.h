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
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
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
    bool isRunning() const { return mRunning.load(); }
    std::string lastError() const;
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void*, int32_t) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

private:
    static constexpr int kBlockSize = 128;
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
    std::atomic<oboe::Result> mStreamError{oboe::Result::OK};
    std::string mLastError;
};

static_assert(std::atomic<float>::is_always_lock_free, "Audio parameters must be lock-free");
static_assert(std::atomic<bool>::is_always_lock_free, "Audio flags must be lock-free");
static_assert(std::atomic<void*>::is_always_lock_free, "Model handoff must be lock-free");
