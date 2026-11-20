#include "VectorStore.h"
#include <cmath>
#include <algorithm>
#include <queue>
#include <fstream>
#include <mutex>
#include <shared_mutex>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

VectorStore& VectorStore::getInstance() {
    static VectorStore instance;
    return instance;
}

VectorStore::VectorStore() {
    records_.reserve(10000);
}

void VectorStore::insert(uint64_t track_id, const float* embedding_data) {
    VectorRecord record;
    record.track_id = track_id;
    float sum_sq = 0.0f;
    for (size_t i = 0; i < VECTOR_DIM; ++i) {
        record.embedding[i] = embedding_data[i];
        sum_sq += embedding_data[i] * embedding_data[i];
    }
    record.norm = std::sqrt(sum_sq);
    records_.push_back(record);
}

float VectorStore::computeCosineSimilarityNEON(const float* a, const float* b, float norm_b) {
    float dot = 0.0f;
    float norm_a_sq = 0.0f;
    size_t i = 0;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    float32x4_t dot_vec = vdupq_n_f32(0.0f);
    float32x4_t norm_a_vec = vdupq_n_f32(0.0f);
    for (; i + 4 <= VECTOR_DIM; i += 4) {
        float32x4_t va = vld1q_f32(a + i);
        float32x4_t vb = vld1q_f32(b + i);
        dot_vec = vmlaq_f32(dot_vec, va, vb);
        norm_a_vec = vmlaq_f32(norm_a_vec, va, va);
    }
    dot = vgetq_lane_f32(dot_vec, 0) + vgetq_lane_f32(dot_vec, 1) +
          vgetq_lane_f32(dot_vec, 2) + vgetq_lane_f32(dot_vec, 3);
    norm_a_sq = vgetq_lane_f32(norm_a_vec, 0) + vgetq_lane_f32(norm_a_vec, 1) +
                vgetq_lane_f32(norm_a_vec, 2) + vgetq_lane_f32(norm_a_vec, 3);
#endif

    for (; i < VECTOR_DIM; ++i) {
        dot += a[i] * b[i];
        norm_a_sq += a[i] * a[i];
    }

    float norm_a = std::sqrt(norm_a_sq);
    if (norm_a <= 1e-6f || norm_b <= 1e-6f) return 0.0f;
    return dot / (norm_a * norm_b);
}

std::vector<QueryResult> VectorStore::queryTopK(const float* target_embedding, size_t k) const {
    auto comp = [](const QueryResult& a, const QueryResult& b) {
        return a.similarity > b.similarity; // Min-heap
    };
    std::priority_queue<QueryResult, std::vector<QueryResult>, decltype(comp)> min_heap(comp);

    for (const auto& record : records_) {
        float sim = computeCosineSimilarityNEON(target_embedding, record.embedding, record.norm);
        if (min_heap.size() < k) {
            min_heap.push({record.track_id, sim});
        } else if (sim > min_heap.top().similarity) {
            min_heap.pop();
            min_heap.push({record.track_id, sim});
        }
    }

    std::vector<QueryResult> results;
    results.reserve(min_heap.size());
    while (!min_heap.empty()) {
        results.push_back(min_heap.top());
        min_heap.pop();
    }
    std::reverse(results.begin(), results.end());
    return results;
}

void VectorStore::clear() {
    records_.clear();
}

bool VectorStore::init(const std::string& bin_path, int dim) {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    bin_path_ = bin_path;
    dim_ = dim;
    vector_data_.clear();

    std::ifstream file(bin_path, std::ios::binary);
    if (!file.is_open()) return true;

    file.seekg(0, std::ios::end);
    size_t size = file.tellg();
    file.seekg(0, std::ios::beg);

    if (size > 0 && size % sizeof(float) == 0) {
        vector_data_.resize(size / sizeof(float));
        file.read(reinterpret_cast<char*>(vector_data_.data()), size);
    }
    return true;
}

int VectorStore::addVector(const std::vector<float>& vec) {
    std::unique_lock<std::shared_mutex> lock(mutex_);
    if (vec.size() != static_cast<size_t>(dim_)) return -1;
    int offset = static_cast<int>(vector_data_.size() / dim_);
    vector_data_.insert(vector_data_.end(), vec.begin(), vec.end());
    save();
    return offset;
}

void VectorStore::save() {
    if (bin_path_.empty()) return;
    std::ofstream file(bin_path_, std::ios::binary | std::ios::trunc);
    if (file.is_open() && !vector_data_.empty()) {
        file.write(reinterpret_cast<const char*>(vector_data_.data()), vector_data_.size() * sizeof(float));
    }
}

std::vector<SearchResult> VectorStore::searchNearest(const std::vector<float>& query_vec, int top_k) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    std::vector<SearchResult> results;
    if (query_vec.size() != static_cast<size_t>(dim_) || vector_data_.empty()) return results;

    int total_vectors = static_cast<int>(vector_data_.size() / dim_);
    results.reserve(total_vectors);

    float q_norm = 0.0f;
    for (float v : query_vec) q_norm += v * v;
    q_norm = std::sqrt(q_norm);
    if (q_norm < 1e-6f) return results;

    for (int i = 0; i < total_vectors; ++i) {
        const float* vec_ptr = &vector_data_[i * dim_];
        float dot = 0.0f;
        float v_norm = 0.0f;
        for (int d = 0; d < dim_; ++d) {
            dot += query_vec[d] * vec_ptr[d];
            v_norm += vec_ptr[d] * vec_ptr[d];
        }
        v_norm = std::sqrt(v_norm);
        float sim = (v_norm > 1e-6f) ? (dot / (q_norm * v_norm)) : 0.0f;
        results.push_back({i, sim});
    }

    std::partial_sort(results.begin(), results.begin() + std::min<size_t>(top_k, results.size()), results.end(),
        [](const SearchResult& a, const SearchResult& b) { return a.similarity > b.similarity; });

    if (results.size() > static_cast<size_t>(top_k)) {
        results.resize(top_k);
    }
    return results;
}

std::vector<SearchResult> VectorStore::searchNearest(int target_offset, int top_k) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (target_offset < 0 || target_offset * dim_ + dim_ > static_cast<int>(vector_data_.size())) {
        return {};
    }
    std::vector<float> query_vec(vector_data_.begin() + target_offset * dim_, vector_data_.begin() + (target_offset + 1) * dim_);
    return searchNearest(query_vec, top_k);
}

std::vector<float> VectorStore::getVectorAt(int offset) {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    if (offset < 0 || offset * dim_ + dim_ > static_cast<int>(vector_data_.size())) return {};
    return std::vector<float>(vector_data_.begin() + offset * dim_, vector_data_.begin() + (offset + 1) * dim_);
}

int VectorStore::getVectorCount() {
    std::shared_lock<std::shared_mutex> lock(mutex_);
    return dim_ > 0 ? static_cast<int>(vector_data_.size() / dim_) : 0;
}
