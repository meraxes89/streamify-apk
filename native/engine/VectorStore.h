#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>
#include <shared_mutex>
#include <optional>

constexpr size_t VECTOR_DIM = 128;

struct VectorRecord {
    uint64_t track_id;
    alignas(16) float embedding[VECTOR_DIM];
    float norm;
};

struct QueryResult {
    uint64_t track_id;
    float similarity;
};

struct SearchResult {
    int vector_offset;
    float similarity;
};

class VectorStore {
public:
    VectorStore();
    ~VectorStore() = default;

    void insert(uint64_t track_id, const float* embedding_data);
    std::vector<QueryResult> queryTopK(const float* target_embedding, size_t k) const;
    void clear();

    // Backward compatibility singleton & methods
    static VectorStore& getInstance();
    bool init(const std::string& bin_path, int dim = 512);
    int addVector(const std::vector<float>& vec);
    std::vector<SearchResult> searchNearest(const std::vector<float>& query_vec, int top_k = 20);
    std::vector<SearchResult> searchNearest(int target_offset, int top_k = 20);
    std::vector<float> getVectorAt(int offset);
    int getVectorCount();

private:
    std::vector<VectorRecord> records_;
    static float computeCosineSimilarityNEON(const float* a, const float* b, float norm_b);

    // Legacy store state
    std::string bin_path_;
    int dim_{512};
    std::vector<float> vector_data_;
    mutable std::shared_mutex mutex_;
    void save();
};
