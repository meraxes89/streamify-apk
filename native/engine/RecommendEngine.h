#ifndef RECOMMEND_ENGINE_H
#define RECOMMEND_ENGINE_H

#include <vector>
#include <array>
#include <mutex>

struct Recommendation {
    int trackId;
    float score;
    float bpmMatchScore = 0.5f;
    float vectorScore = 0.0f;
};

class RecommendEngine {
public:
    static RecommendEngine& getInstance();

    void updateSessionVector(int trackId, float alpha = 0.45f);
    std::vector<Recommendation> getSessionRecommendations(int limit = 50);
    std::vector<Recommendation> getLongTermRecommendations(int userId = 1, int limit = 50);
    std::vector<Recommendation> getCircadianRecommendations(int hour_of_day, int limit = 20);
    std::vector<Recommendation> getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit);

    // Hybrid Asymmetric Recommendation Engine (Layer 2)
    std::vector<float> computeContextualVector(const std::vector<float>& sessionVector, float timeWeight, float deviceWeight);
    std::vector<int> findClosestClusters(const std::vector<float>& queryVector, int topK = 2);
    std::vector<Recommendation> rankHybridCandidates(const std::vector<float>& queryVector, const std::vector<int>& candidateTrackIds, float bpmTarget, float satiationPenaltyWeight = 0.20f, int limit = 20);
    float getTargetBpmForTimeSlot(int slotOrdinal);

private:
    RecommendEngine();
    ~RecommendEngine() = default;
    RecommendEngine(const RecommendEngine&) = delete;
    RecommendEngine& operator=(const RecommendEngine&) = delete;

    void ensureCentroidsLoaded();

    std::vector<float> session_vector_;
    std::mutex session_mutex_;

    std::array<std::vector<float>, 16> cluster_centroids_;
    bool centroids_loaded_{false};
    std::vector<float> time_prototype_vector_;
    std::vector<float> device_prototype_vector_;
};

#endif // RECOMMEND_ENGINE_H
