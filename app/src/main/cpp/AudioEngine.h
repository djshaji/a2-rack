#pragma once
#include <oboe/Oboe.h>
#include <memory>
#include <string>
#include <mutex>
#include "NAM/get_dsp.h"
#include "dsp/ImpulseResponse.h"

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine();
    bool start();
    void stop();

    bool loadNamModel(const std::string& path);
    bool loadIrModel(const std::string& path);
    void setInputGain(float gain);
    void setOutputGain(float gain);
    void setNamBypass(bool bypass);
    void setIrBypass(bool bypass);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mPlayStream;
    std::shared_ptr<oboe::AudioStream> mRecordStream;

    bool openPlaybackStream();
    bool openRecordStream();
    void closeStreams();

    std::mutex mDspMutex;
    std::unique_ptr<nam::DSP> mNamModel;
    std::unique_ptr<dsp::ImpulseResponse> mIrModel;

    float mInputGain = 1.0f;
    float mOutputGain = 1.0f;
    bool mNamBypass = false;
    bool mIrBypass = false;
};
