#include "AudioEngine.h"
#include <android/log.h>
#include <algorithm>
#include <vector>

#define TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start() {
    if (mPlayStream && mPlayStream->getState() == oboe::StreamState::Started) {
        return true;
    }

    if (!openPlaybackStream()) {
        return false;
    }

    if (!openRecordStream()) {
        closeStreams();
        return false;
    }

    oboe::Result result = mPlayStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Error starting playback stream. Error: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }

    result = mRecordStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Error starting record stream. Error: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }

    return true;
}

void AudioEngine::stop() {
    closeStreams();
}

bool AudioEngine::openPlaybackStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(2); // Stereo

    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(mPlayStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open playback stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    return true;
}

bool AudioEngine::openRecordStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(mPlayStream->getFormat());
    builder.setChannelCount(mPlayStream->getChannelCount());
    builder.setSampleRate(mPlayStream->getSampleRate());
    builder.setErrorCallback(this);

    oboe::Result result = builder.openStream(mRecordStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open record stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    return true;
}

void AudioEngine::closeStreams() {
    if (mRecordStream) {
        mRecordStream->close();
        mRecordStream.reset();
    }
    if (mPlayStream) {
        mPlayStream->close();
        mPlayStream.reset();
    }
}

bool AudioEngine::loadNamModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    try {
        mNamModel = nam::get_dsp(std::filesystem::path(path));
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to load NAM model: %s", e.what());
        mNamModel.reset();
        return false;
    }
}

bool AudioEngine::loadIrModel(const std::string& path) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    try {
        double sampleRate = 48000.0;
        if (mPlayStream) {
            sampleRate = mPlayStream->getSampleRate();
        }
        mIrModel = std::make_unique<dsp::ImpulseResponse>(path.c_str(), sampleRate);
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to load IR model: %s", e.what());
        mIrModel.reset();
        return false;
    }
}

void AudioEngine::setInputGain(float gain) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    mInputGain = gain;
}

void AudioEngine::setOutputGain(float gain) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    mOutputGain = gain;
}

void AudioEngine::setNamBypass(bool bypass) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    mNamBypass = bypass;
}

void AudioEngine::setIrBypass(bool bypass) {
    std::lock_guard<std::mutex> lock(mDspMutex);
    mIrBypass = bypass;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {

    if (!mRecordStream || !mPlayStream) {
        return oboe::DataCallbackResult::Stop;
    }

    auto *outputData = static_cast<float *>(audioData);
    int32_t channels = mPlayStream->getChannelCount(); // Should be 2

    oboe::ResultWithValue<int32_t> result = mRecordStream->read(outputData, numFrames, 0);
    int32_t framesRead = (result == oboe::Result::OK) ? result.value() : 0;

    if (framesRead == 0) {
        std::fill_n(outputData, numFrames * channels, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    std::vector<float> monoData(framesRead);
    for (int32_t i = 0; i < framesRead; ++i) {
        float sample = 0.0f;
        for (int32_t c = 0; c < channels; ++c) {
            sample += outputData[i * channels + c];
        }
        monoData[i] = (sample / channels) * mInputGain;
    }

    {
        std::lock_guard<std::mutex> lock(mDspMutex);

        float* channelPointers[1] = { monoData.data() };

        std::vector<float> namOutput(framesRead, 0.0f);
        float* namOutputPointers[1] = { namOutput.data() };

        if (mNamModel && !mNamBypass) {
            mNamModel->process(channelPointers, namOutputPointers, framesRead);
            std::copy(namOutput.begin(), namOutput.end(), monoData.begin());
        }

        if (mIrModel && !mIrBypass) {
            float** irOutput = mIrModel->Process(channelPointers, 1, framesRead);
            if (irOutput && irOutput[0]) {
                std::copy_n(irOutput[0], framesRead, monoData.begin());
            }
        }
    }

    for (int32_t i = 0; i < framesRead; ++i) {
        float sample = monoData[i] * mOutputGain;
        if (sample > 1.0f) sample = 1.0f;
        else if (sample < -1.0f) sample = -1.0f;

        for (int32_t c = 0; c < channels; ++c) {
            outputData[i * channels + c] = sample;
        }
    }

    if (framesRead < numFrames) {
        std::fill_n(outputData + framesRead * channels, (numFrames - framesRead) * channels, 0.0f);
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) {
    LOGE("Error was %s", oboe::convertToText(error));
}
