#include "AudioEngine.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <thread>

AudioEngine::~AudioEngine() { stop(); }

std::string AudioEngine::lastError() const {
    if (mProcessingFailed.load()) return "Audio processing failed. Restart the engine and reload the model.";
    const auto error = streamError();
    if (error != oboe::Result::OK)
        return std::string("Audio stream stopped: ") + oboe::convertToText(error);
    return mLastError;
}

bool AudioEngine::start() {
    if (isRunning()) return true;
    closeStreams();
    // A fresh stream may negotiate a different sample rate. Never reuse DSP
    // configured for an old stream.
    mNamModel.reset();
    mIrModel.reset();
    mLastError.clear();
    mStreamState.store(static_cast<uint64_t>(++mStreamEpoch) << 32);
    mProcessingFailed.store(false);
    if (!openPlaybackStream() || !openRecordStream()) {
        closeStreams();
        return false;
    }
    auto result = mRecordStream->requestStart();
    if (result == oboe::Result::OK) {
        mRunning.store(true);
        result = mPlayStream->requestStart();
    }
    if (result != oboe::Result::OK) {
        mLastError = std::string("Cannot start audio: ") + oboe::convertToText(result);
        closeStreams();
        return false;
    }
    return true;
}

void AudioEngine::stop() {
    closeStreams();
    mNamModel.reset();
    mIrModel.reset();
}

bool AudioEngine::openPlaybackStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setAudioApi(oboe::AudioApi::AAudio);
    builder.setDeviceId(mOutputDeviceId);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(2);
    builder.setFramesPerDataCallback(kBlockSize);
    builder.setDataCallback(this);
    builder.setErrorCallback(std::make_shared<StreamErrors>(*this, streamToken()));
    const auto result = builder.openStream(mPlayStream);
    if (result != oboe::Result::OK) {
        mLastError = std::string("Cannot open audio output: ") + oboe::convertToText(result);
        return false;
    }
    if ((mOutputDeviceId != 0 && mPlayStream->getDeviceId() != mOutputDeviceId) ||
        mPlayStream->getFormat() != oboe::AudioFormat::Float ||
        mPlayStream->getChannelCount() != 2) {
        mLastError = "Audio output must support stereo floating-point samples.";
        return false;
    }
    return true;
}

bool AudioEngine::openRecordStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setAudioApi(oboe::AudioApi::AAudio);
    builder.setDeviceId(mInputDeviceId);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Exclusive);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setChannelCount(2);
    builder.setSampleRate(mPlayStream->getSampleRate());
    builder.setErrorCallback(std::make_shared<StreamErrors>(*this, streamToken()));
    const auto result = builder.openStream(mRecordStream);
    if (result != oboe::Result::OK) {
        mLastError = std::string("Cannot open microphone: ") + oboe::convertToText(result);
        return false;
    }
    if ((mInputDeviceId != 0 && mRecordStream->getDeviceId() != mInputDeviceId) ||
        mRecordStream->getFormat() != oboe::AudioFormat::Float ||
        mRecordStream->getChannelCount() != 2 ||
        mRecordStream->getSampleRate() != mPlayStream->getSampleRate()) {
        mLastError = "Audio input and output formats do not match.";
        return false;
    }
    return true;
}

void AudioEngine::closeStreams() {
    mRunning.store(false);
    // Invalidate old callbacks before touching the stream pointers.
    mStreamState.store((static_cast<uint64_t>(++mStreamEpoch) << 32) |
                       static_cast<uint32_t>(streamError()));
    // Closing output first joins its callback before the input pointer or DSP
    // objects can be changed by a control operation.
    if (mPlayStream) {
        mPlayStream->close();
        mPlayStream.reset();
    }
    if (mRecordStream) {
        mRecordStream->close();
        mRecordStream.reset();
    }
}

bool AudioEngine::publish(ModelUpdate& update) {
    // The worker owns this update, including the retired model after the swap.
    // The callback only swaps pointers and acknowledges; it never frees models.
    mPending.store(&update);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    while (!update.applied.load()) {
        if (!isRunning() || std::chrono::steady_clock::now() >= deadline) {
            auto* expected = &update;
            if (mPending.compare_exchange_strong(expected, nullptr)) {
                mLastError = "The audio callback is unavailable. Restart audio and try again.";
                return false;
            }
            // The callback already claimed the update. Its acknowledgment is
            // required before the worker can destroy the retired model.
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    mLastError.clear();
    return true;
}

void AudioEngine::applyPendingUpdate() {
    if (auto* update = mPending.exchange(nullptr)) {
        if (update->isNam) mNamModel.swap(update->nam);
        else mIrModel.swap(update->ir);
        update->applied.store(true);
    }
}

bool AudioEngine::loadNamModel(const std::string& path) {
    try {
        if (!isRunning()) throw std::runtime_error("Start audio before loading an amp model.");
        ModelUpdate update;
        update.isNam = true;
        // Empty paths explicitly unload a slot through the same safe handoff.
        if (path.empty()) return publish(update);
        update.nam = nam::get_dsp(std::filesystem::path(path));
        if (!update.nam) throw std::runtime_error("The amp model could not be decoded.");
        if (update.nam->NumInputChannels() != 1 || update.nam->NumOutputChannels() != 1)
            throw std::runtime_error("Only mono NAM models are supported.");
        const double rate = mPlayStream->getSampleRate();
        const double expected = update.nam->GetExpectedSampleRate();
        if (expected > 0 && std::abs(expected - rate) > 0.5 &&
            !update.nam->SupportsArbitrarySampleRate())
            throw std::runtime_error("Model requires " + std::to_string(static_cast<int>(expected)) +
                                     " Hz; audio is running at " + std::to_string(static_cast<int>(rate)) + " Hz.");
        update.nam->Reset(rate, kBlockSize);
        std::array<float, kBlockSize> silence{}, output{};
        float* inputs[] = {silence.data()};
        float* outputs[] = {output.data()};
        update.nam->process(inputs, outputs, kBlockSize);
        return publish(update);
    } catch (const std::exception& e) {
        mLastError = std::string("Amp loading failed: ") + e.what();
        return false;
    }
}

bool AudioEngine::loadIrModel(const std::string& path) {
    try {
        if (!isRunning()) throw std::runtime_error("Start audio before loading a cabinet response.");
        if (path.empty()) {
            ModelUpdate update;
            update.isNam = false;
            return publish(update);
        }
        // Validate raw data before constructing the IR: empty data would
        // underflow the upstream convolution history length.
        dsp::ImpulseResponse::IRData data;
        const auto result = dsp::wav::Load(path.c_str(), data.mRawAudio, data.mRawAudioSampleRate);
        if (result != dsp::wav::LoadReturnCode::SUCCESS)
            throw std::runtime_error(dsp::wav::GetMsgForLoadReturnCode(result));
        if (data.mRawAudio.empty() || !std::isfinite(data.mRawAudioSampleRate) ||
            data.mRawAudioSampleRate <= 0 ||
            !std::all_of(data.mRawAudio.begin(), data.mRawAudio.end(),
                         [](float value) { return std::isfinite(value); }))
            throw std::runtime_error("The IR must contain finite, nonempty mono audio.");
        ModelUpdate update;
        update.isNam = false;
        update.ir = std::make_unique<dsp::ImpulseResponse>(data, mPlayStream->getSampleRate());
        std::array<float, kBlockSize> silence{};
        float* channels[] = {silence.data()};
        // Allocate output pointers and history at the maximum processing size.
        update.ir->Process(channels, 1, kBlockSize);
        return publish(update);
    } catch (const std::exception& e) {
        mLastError = std::string("Cabinet loading failed: ") + e.what();
        return false;
    }
}

void AudioEngine::setInputGain(float gain) {
    mInputGain.store(std::isfinite(gain) ? std::clamp(gain, 0.0f, 1.0f) : 0.0f);
}
void AudioEngine::setOutputGain(float gain) {
    mOutputGain.store(std::isfinite(gain) ? std::clamp(gain, 0.0f, 1.0f) : 0.0f);
}
void AudioEngine::setNamBypass(bool bypass) { mNamBypass.store(bypass); }
void AudioEngine::setIrBypass(bool bypass) { mIrBypass.store(bypass); }

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    auto* output = static_cast<float*>(audioData);
    const int channels = stream->getChannelCount();
    std::fill_n(output, numFrames * channels, 0.0f);
    if (!isRunning()) return oboe::DataCallbackResult::Stop;
    // Oboe's callback adapter supplies this exact quantum. Keeping it fixed
    // also prevents shape-dependent reallocations in upstream NAM processors.
    if (numFrames != kBlockSize || channels != 2) {
        mProcessingFailed.store(true);
        mRunning.store(false);
        return oboe::DataCallbackResult::Stop;
    }
    applyPendingUpdate();
    const float inputGain = mInputGain.load();
    const float outputGain = mOutputGain.load();
    const bool namBypass = mNamBypass.load();
    const bool irBypass = mIrBypass.load();
    try {
        const auto result = mRecordStream->read(output, kBlockSize, 0);
        if (result != oboe::Result::OK) {
            std::fill_n(output, numFrames * channels, 0.0f);
            reportError(streamToken(), result.error());
            mRunning.store(false);
            return oboe::DataCallbackResult::Stop;
        }
        const int framesRead = result.value();
        mMono.fill(0.0f);
        for (int i = 0; i < framesRead; ++i)
            mMono[i] = (output[i * 2] + output[i * 2 + 1]) * 0.5f * inputGain;
        // Missing input is silence for this time interval. Process the entire
        // quantum so DSP history and delay tails still advance on underruns.
        float* input[] = {mMono.data()};
        float* namOutput[] = {mNamOutput.data()};
        if (mNamModel && !namBypass) {
            mNamModel->process(input, namOutput, kBlockSize);
            std::copy_n(mNamOutput.data(), kBlockSize, mMono.data());
        }
        if (mIrModel && !irBypass) {
            auto irOutput = mIrModel->Process(input, 1, kBlockSize);
            std::copy_n(irOutput[0], kBlockSize, mMono.data());
        }
        for (int i = 0; i < kBlockSize; ++i) {
            const float value = mMono[i] * outputGain;
            const float sample = std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
            output[i * 2] = sample;
            output[i * 2 + 1] = sample;
        }
    } catch (...) {
        // Never let a third-party DSP exception cross the C audio callback.
        std::fill_n(output, numFrames * channels, 0.0f);
        mProcessingFailed.store(true);
        mRunning.store(false);
        return oboe::DataCallbackResult::Stop;
    }
    if (mMuted.load() || !isRunning()) std::fill_n(output, numFrames * channels, 0.0f);
    return oboe::DataCallbackResult::Continue;
}
