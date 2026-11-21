#ifndef AUDIO_PIPELINE_H
#define AUDIO_PIPELINE_H

#include <string>
#include <vector>
#include <mutex>
#include <kiss_fftr.h>

class AudioPipeline {
public:
    static AudioPipeline& getInstance();

    struct TrackMetadata {
        std::string title;
        std::string artist;
        std::string key;
        float bpm;
    };

    bool init(const std::string& model_path = "");
    std::vector<float> processAudio(const std::string& filepath);
    TrackMetadata extractMetadata(const std::string& filepath);
    float extractBPM(const std::string& filepath);
    std::string extractKey(const std::string& filepath);
    std::string extractAcousticDNAFromPcm(const float* pcm, int length, int sampleRate, float* outResults, std::vector<float>* outEmbedding = nullptr);
    static std::string keyToCamelot(const std::string& key);

private:
    AudioPipeline();
    ~AudioPipeline();
    AudioPipeline(const AudioPipeline&) = delete;
    AudioPipeline& operator=(const AudioPipeline&) = delete;

    void initPrecomputedTables();

    std::mutex pipeline_mutex_;

    // Pre-allocated scratch tables & FFT setups (Zero-alloc arena)
    kiss_fftr_cfg cfg_2048_{nullptr};
    kiss_fftr_cfg cfg_1024_{nullptr};
    kiss_fft_scalar* in_2048_{nullptr};
    kiss_fft_cpx* out_2048_{nullptr};
    kiss_fft_scalar* in_1024_{nullptr};
    kiss_fft_cpx* out_1024_{nullptr};

    std::vector<float> window_2048_;
    std::vector<float> window_1024_;
    std::vector<std::vector<float>> krumhansl_profiles_24_;
    std::vector<std::string> key_names_24_;
};

#endif // AUDIO_PIPELINE_H
