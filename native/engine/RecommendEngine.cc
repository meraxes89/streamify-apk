#include "RecommendEngine.h"
#include "StreamifyDB.h"
#include "VectorStore.h"
#include "ChronosProfiler.h"
#include "TelemetryEngine.h"
#include <unordered_set>
#include <unordered_map>
#include <algorithm>
#include <cmath>
#include <ctime>
#include <cctype>

static bool equalsIgnoreCase(const std::string& a, const std::string& b) {
    if (a.length() != b.length()) return false;
    for (size_t i = 0; i < a.length(); ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

static uint64_t computeFnv1aRootHash(const std::string& title) {
    if (title.empty()) return 0;
    std::vector<std::string> tokens;
    std::string current;
    for (char c : title) {
        if (std::isalnum(static_cast<unsigned char>(c))) {
            current.push_back(std::tolower(static_cast<unsigned char>(c)));
        } else {
            if (current.length() > 2 && current != "the" && current != "and" &&
                current != "official" && current != "video" && current != "audio" &&
                current != "lyric" && current != "lyrics" && current != "live" &&
                current != "remaster" && current != "remastered" && current != "slowed" &&
                current != "acoustic" && current != "feat" && current != "ft") {
                tokens.push_back(current);
            }
            current.clear();
        }
    }
    if (current.length() > 2 && current != "the" && current != "and" &&
        current != "official" && current != "video" && current != "audio" &&
        current != "lyric" && current != "lyrics" && current != "live" &&
        current != "remaster" && current != "remastered" && current != "slowed" &&
        current != "acoustic" && current != "feat" && current != "ft") {
        tokens.push_back(current);
    }
    if (tokens.empty()) return 0;

    std::sort(tokens.begin(), tokens.end());

    uint64_t hash = 0xcbf29ce484222325ULL;
    for (const auto& token : tokens) {
        for (char c : token) {
            hash ^= static_cast<uint64_t>(c);
            hash *= 0x100000001b3ULL;
        }
    }
    return hash;
}

RecommendEngine& RecommendEngine::getInstance() {
    static RecommendEngine instance;
    return instance;
}

std::vector<Recommendation> RecommendEngine::getNextTracks(int currentTrackId, const std::vector<int>& recentHistory, int limit) {
    auto& db = StreamifyDB::getInstance();
    auto curTrack = db.getTrackById(currentTrackId);
    if (!curTrack) {
        // Cold-start Fallback: If current track not found by ID, return top tracks in DB
        auto allTracks = db.getAllTracks();
        std::vector<Recommendation> fallback;
        for (const auto& t : allTracks) {
            if (t.id != currentTrackId) {
                fallback.push_back({t.id, 1.0f});
                if (fallback.size() >= static_cast<size_t>(limit)) break;
            }
        }
        return fallback;
    }

    std::unordered_set<int> excluded_tracks(recentHistory.begin(), recentHistory.end());
    excluded_tracks.insert(currentTrackId);

    // Layer 3: O(1) FNV-1a Root Title Exclusion Set
    std::unordered_set<uint64_t> selected_title_hashes;
    uint64_t curTitleHash = computeFnv1aRootHash(curTrack->title);
    if (curTitleHash != 0) selected_title_hashes.insert(curTitleHash);

    for (int histId : recentHistory) {
        auto hTrack = db.getTrackById(histId);
        if (hTrack) {
            uint64_t hHash = computeFnv1aRootHash(hTrack->title);
            if (hHash != 0) selected_title_hashes.insert(hHash);
        }
    }

    // Fetch user liked tracks for affinity boosting (user_id = 1)
    std::vector<int> likedIdsVec = db.getUserLikedTrackIds(1);
    std::unordered_set<int> liked_set(likedIdsVec.begin(), likedIdsVec.end());

    auto& vecStore = VectorStore::getInstance();
    int target_offset = curTrack->vector_offset;

    std::vector<float> session_vec = vecStore.getVectorAt(target_offset);
    if (!session_vec.empty()) {
        for (float& val : session_vec) val *= 0.70f;
        
        float weights[] = {0.45f, 0.15f, 0.05f}; // temporal decay
        for (size_t h = 0; h < recentHistory.size() && h < 3; ++h) {
            int hist_id = recentHistory[h];
            if (hist_id == currentTrackId) continue;
            auto histTrack = db.getTrackById(hist_id);
            if (histTrack) {
                auto v = vecStore.getVectorAt(histTrack->vector_offset);
                if (v.size() == session_vec.size()) {
                    for (size_t i = 0; i < session_vec.size(); ++i) {
                        session_vec[i] += weights[h] * v[i];
                    }
                }
            }
        }
        float norm = 0.0f;
        for (float val : session_vec) norm += val * val;
        if (norm > 1e-9f) {
            norm = std::sqrt(norm);
            for (float& val : session_vec) val /= norm;
        }
    }

    std::vector<SearchResult> nearestResults;
    if (!session_vec.empty()) {
        nearestResults = vecStore.searchNearest(session_vec, 100);
    } else if (target_offset >= 0) {
        nearestResults = vecStore.searchNearest(target_offset, 100);
    }

    std::vector<Recommendation> candidates;
    const float beta_bpm = 0.20f;
    const float artist_boost = 0.25f;
    const float key_boost = 0.15f;
    const float liked_boost = 0.35f;
    const float skip_penalty_factor = 0.15f;

    std::unordered_set<int> candidate_ids_added;

    // HOISTED out of the candidate loop: this query is IDENTICAL for every
    // candidate (same anchor, same limit) but was previously re-executed —
    // statement prepare + full scan — once per candidate (~100x per call).
    const std::vector<int> cooccur_for_anchor = db.getCooccurrenceCandidates(currentTrackId, 10);

    if (!nearestResults.empty()) {
        for (const auto& res : nearestResults) {
            auto candidateTrack = db.getTrackByVectorOffset(res.vector_offset);
            if (!candidateTrack) continue;

            int track_id = candidateTrack->id;
            if (excluded_tracks.count(track_id) > 0) continue;
            if (candidate_ids_added.count(track_id) > 0) continue;

            // Zero-Allocation Duplicate Version Filter
            uint64_t candTitleHash = computeFnv1aRootHash(candidateTrack->title);
            if (candTitleHash != 0 && selected_title_hashes.count(candTitleHash) > 0) {
                continue; // Hard nuke duplicate versions (Live/Audio/Remaster/Lyric video)
            }

            float cosine_sim = res.similarity;
            
            // BPM Tempo Match
            float bpm_score = 1.0f;
            if (curTrack->bpm > 0 && candidateTrack->bpm > 0) {
                float bpm_diff = std::abs(curTrack->bpm - candidateTrack->bpm);
                float ratio = bpm_diff / curTrack->bpm;
                bpm_score = std::max(0.0f, 1.0f - (ratio * 5.0f)); 
            }

            // Artist Similarity Bonus
            float a_boost = 0.0f;
            if (!curTrack->artist.empty() && equalsIgnoreCase(curTrack->artist, candidateTrack->artist)) {
                a_boost = artist_boost;
            }

            // Key Compatibility Bonus
            float k_boost = 0.0f;
            if (!curTrack->key.empty() && equalsIgnoreCase(curTrack->key, candidateTrack->key)) {
                k_boost = key_boost;
            }

            // Liked Song Affinity Boost
            float l_boost = 0.0f;
            if (liked_set.count(track_id) > 0) {
                l_boost = liked_boost;
            }

            // Transition & 1st/2nd-Order Markov Chain Probability
            float transition_prob = db.getTransitionProbability(1, currentTrackId, track_id);
            float markov_prob = db.getMarkovProbability(currentTrackId, track_id);
            float markov_2nd = 0.0f;
            if (!recentHistory.empty()) {
                int trackA = recentHistory.back();
                markov_2nd = db.get2ndOrderMarkovProbability(trackA, currentTrackId, track_id, 0.1f);
            }
            float effective_markov = std::max(transition_prob, std::max(markov_prob, markov_2nd * 1.5f));

            int skip_count = db.getTrackTotalSkipCount(1, track_id);
            float skip_penalty = std::min(1.0f, skip_count * skip_penalty_factor);

            // Project Nexus & Chronos: Co-occurrence graph boost & Satiation burnout penalty
            float cooccur_boost = 0.0f;
            if (std::find(cooccur_for_anchor.begin(), cooccur_for_anchor.end(), track_id) != cooccur_for_anchor.end()) {
                cooccur_boost = 0.35f;
            }
            float satiation_penalty = ChronosProfiler::getInstance().calculateSatiationPenalty(track_id, std::time(nullptr) * 1000LL);

            float final_score = (0.45f * cosine_sim) + (0.25f * effective_markov) + (0.15f * bpm_score) + a_boost + k_boost + l_boost + (0.15f * cooccur_boost) - skip_penalty - (0.15f * satiation_penalty);
            candidates.push_back({track_id, final_score});
            candidate_ids_added.insert(track_id);
        }
    }

    // COLD-START / METADATA FALLBACK:
    if (candidates.size() < static_cast<size_t>(limit)) {
        auto allTracks = db.getAllTracks();
        for (const auto& candidateTrack : allTracks) {
            int track_id = candidateTrack.id;
            if (excluded_tracks.count(track_id) > 0) continue;
            if (candidate_ids_added.count(track_id) > 0) continue;

            uint64_t candTitleHash = computeFnv1aRootHash(candidateTrack.title);
            if (candTitleHash != 0 && selected_title_hashes.count(candTitleHash) > 0) {
                continue;
            }

            float base_score = 0.50f;
            if (!curTrack->artist.empty() && equalsIgnoreCase(curTrack->artist, candidateTrack.artist)) {
                base_score += artist_boost;
            }
            if (!curTrack->key.empty() && equalsIgnoreCase(curTrack->key, candidateTrack.key)) {
                base_score += key_boost;
            }
            if (liked_set.count(track_id) > 0) {
                base_score += liked_boost;
            }

            candidates.push_back({track_id, base_score});
            candidate_ids_added.insert(track_id);
        }
    }

    // Ultra-Fast Top-K Selection: O(N log K) using std::partial_sort instead of full O(N log N) std::sort
    size_t targetTopK = std::min(candidates.size(), static_cast<size_t>(limit * 3));
    if (targetTopK > 0) {
        std::partial_sort(
            candidates.begin(),
            candidates.begin() + targetTopK,
            candidates.end(),
            [](const Recommendation& a, const Recommendation& b) {
                return a.score > b.score;
            }
        );
        candidates.resize(targetTopK);
    }

    // Layer 3: Maximal Marginal Relevance (MMR) & Artist Saturation Cap
    std::vector<Recommendation> diverse_candidates;
    std::unordered_map<std::string, int> artist_counts;
    
    for (const auto& rec : candidates) {
        auto cTrack = db.getTrackById(rec.trackId);
        if (!cTrack) continue;
        
        std::string lowerArtist = cTrack->artist;
        std::transform(lowerArtist.begin(), lowerArtist.end(), lowerArtist.begin(), ::tolower);
        
        uint64_t cTitleHash = computeFnv1aRootHash(cTrack->title);
        if (cTitleHash != 0 && selected_title_hashes.count(cTitleHash) > 0) {
            continue; // Duplicate version check
        }

        int currentCount = artist_counts[lowerArtist];
        if (currentCount >= 2 && candidates.size() > static_cast<size_t>(limit)) {
            continue; // Artist saturation cap: max 2 songs per artist in top queue
        }
        
        diverse_candidates.push_back(rec);
        if (cTitleHash != 0) selected_title_hashes.insert(cTitleHash);
        artist_counts[lowerArtist] = currentCount + 1;
        
        if (diverse_candidates.size() >= static_cast<size_t>(limit)) break;
    }
    
    // Fallback if diversity filtering removed too many
    if (diverse_candidates.size() < static_cast<size_t>(limit)) {
        std::unordered_set<int> added_ids;
        for (const auto& r : diverse_candidates) added_ids.insert(r.trackId);
        for (const auto& rec : candidates) {
            if (added_ids.insert(rec.trackId).second) {
                diverse_candidates.push_back(rec);
                if (diverse_candidates.size() >= static_cast<size_t>(limit)) break;
            }
        }
    }

    return diverse_candidates;
}

void RecommendEngine::updateSessionVector(int trackId, float alpha) {
    auto& db = StreamifyDB::getInstance();
    auto track = db.getTrackById(trackId);
    if (!track || track->vector_offset < 0) return;

    auto& vecStore = VectorStore::getInstance();
    std::vector<float> newVec = vecStore.getVectorAt(track->vector_offset);
    if (newVec.empty()) return;

    std::lock_guard<std::mutex> lock(session_mutex_);
    if (session_vector_.empty() || session_vector_.size() != newVec.size()) {
        session_vector_ = newVec;
    } else {
        // Exponential Moving Average (EMA)
        float norm = 0.0f;
        for (size_t i = 0; i < session_vector_.size(); ++i) {
            session_vector_[i] = (alpha * newVec[i]) + ((1.0f - alpha) * session_vector_[i]);
            norm += session_vector_[i] * session_vector_[i];
        }
        if (norm > 1e-9f) {
            norm = std::sqrt(norm);
            for (float& val : session_vector_) val /= norm;
        }
    }
}

std::vector<Recommendation> RecommendEngine::getSessionRecommendations(int limit) {
    std::vector<float> target_vec;
    {
        std::lock_guard<std::mutex> lock(session_mutex_);
        target_vec = session_vector_;
    }

    if (target_vec.empty()) {
        return getLongTermRecommendations(1, limit);
    }

    auto& vecStore = VectorStore::getInstance();
    auto nearestResults = vecStore.searchNearest(target_vec, limit * 2);

    auto& db = StreamifyDB::getInstance();
    std::vector<Recommendation> recs;
    std::unordered_set<int> seen;

    for (const auto& res : nearestResults) {
        auto t = db.getTrackByVectorOffset(res.vector_offset);
        if (!t || seen.count(t->id) > 0) continue;
        seen.insert(t->id);
        recs.push_back({t->id, res.similarity});
        if (recs.size() >= static_cast<size_t>(limit)) break;
    }

    if (recs.empty()) {
        auto allTracks = db.getAllTracks();
        for (const auto& t : allTracks) {
            recs.push_back({t.id, 0.5f});
            if (recs.size() >= static_cast<size_t>(limit)) break;
        }
    }

    return recs;
}

std::vector<Recommendation> RecommendEngine::getLongTermRecommendations(int userId, int limit) {
    auto& db = StreamifyDB::getInstance();
    auto& vecStore = VectorStore::getInstance();

    std::vector<int> likedIds = db.getUserLikedTrackIds(userId);
    std::vector<StreamifyTrack> topTracks = db.getTopPlayedTracks(10);

    std::vector<float> centroid;
    int count = 0;

    auto accumulateVec = [&](int trackId, float weight) {
        auto t = db.getTrackById(trackId);
        if (t && t->vector_offset >= 0) {
            auto v = vecStore.getVectorAt(t->vector_offset);
            if (!v.empty()) {
                if (centroid.empty()) centroid.resize(v.size(), 0.0f);
                for (size_t i = 0; i < v.size(); ++i) {
                    centroid[i] += weight * v[i];
                }
                count++;
            }
        }
    };

    for (int id : likedIds) accumulateVec(id, 2.0f);
    for (const auto& t : topTracks) accumulateVec(t.id, 1.5f);

    if (count > 0 && !centroid.empty()) {
        float norm = 0.0f;
        for (float val : centroid) norm += val * val;
        if (norm > 1e-9f) {
            norm = std::sqrt(norm);
            for (float& val : centroid) val /= norm;
        }

        auto nearestResults = vecStore.searchNearest(centroid, limit * 2);
        std::vector<Recommendation> recs;
        std::unordered_set<int> seen;

        for (const auto& res : nearestResults) {
            auto t = db.getTrackByVectorOffset(res.vector_offset);
            if (!t || seen.count(t->id) > 0) continue;
            seen.insert(t->id);
            recs.push_back({t->id, res.similarity});
            if (recs.size() >= static_cast<size_t>(limit)) break;
        }
        if (!recs.empty()) return recs;
    }

    // Cold-start fallback
    auto allTracks = db.getAllTracks();
    std::vector<Recommendation> fallback;
    for (const auto& t : allTracks) {
        fallback.push_back({t.id, 0.5f});
        if (fallback.size() >= static_cast<size_t>(limit)) break;
    }
    return fallback;
}

std::vector<Recommendation> RecommendEngine::getCircadianRecommendations(int hour_of_day, int limit) {
    auto& db = StreamifyDB::getInstance();
    auto& vecStore = VectorStore::getInstance();

    float targetBpm = db.getCircadianAvgBPM(hour_of_day);
    std::vector<StreamifyTrack> allTracks = db.getAllTracks();
    if (allTracks.empty()) return {};

    std::vector<int> likedIds = db.getUserLikedTrackIds(1);
    std::unordered_set<int> likedSet(likedIds.begin(), likedIds.end());

    std::vector<Recommendation> candidates;
    for (const auto& track : allTracks) {
        float bpm_score = 0.5f;
        if (track.bpm > 40.0f && targetBpm > 40.0f) {
            float diff = std::abs(static_cast<float>(track.bpm) - targetBpm);
            float ratio = diff / targetBpm;
            bpm_score = std::max(0.0f, 1.0f - (ratio * 3.5f));
        }

        float liked_bonus = (likedSet.count(track.id) > 0) ? 0.35f : 0.0f;
        float play_count_bonus = std::min(0.20f, track.play_count * 0.02f);
        
        float final_score = (bpm_score * 0.55f) + liked_bonus + play_count_bonus;
        candidates.push_back({track.id, final_score});
    }

    size_t targetTopK = std::min(candidates.size(), static_cast<size_t>(limit));
    if (targetTopK > 0) {
        std::partial_sort(
            candidates.begin(),
            candidates.begin() + targetTopK,
            candidates.end(),
            [](const Recommendation& a, const Recommendation& b) {
                return a.score > b.score;
            }
        );
        candidates.resize(targetTopK);
    }

    return candidates;
}

// ═══════════════════════════════════════════════════════════════
// HYBRID ASYMMETRIC ENGINE: CONTEXTUAL VECTOR, CLUSTERS & RANKING
// ═══════════════════════════════════════════════════════════════

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

RecommendEngine::RecommendEngine() {
    time_prototype_vector_.resize(512);
    device_prototype_vector_.resize(512);
    for (int i = 0; i < 512; ++i) {
        time_prototype_vector_[i] = std::sin(i * 0.05f) * 0.5f;
        device_prototype_vector_[i] = std::cos(i * 0.03f) * 0.5f;
    }
}

void RecommendEngine::ensureCentroidsLoaded() {
    if (centroids_loaded_) return;
    auto& db = StreamifyDB::getInstance();
    auto loaded = db.getClusterCentroids();
    if (loaded.size() >= 16) {
        for (const auto& item : loaded) {
            if (item.first >= 0 && item.first < 16 && item.second.size() == 512) {
                cluster_centroids_[item.first] = item.second;
            }
        }
        centroids_loaded_ = true;
    } else {
        for (int c = 0; c < 16; ++c) {
            cluster_centroids_[c].resize(512);
            float norm = 0.0f;
            for (int i = 0; i < 512; ++i) {
                float val = std::sin((i + c * 32) * 0.1f) + std::cos((i * (c + 1)) * 0.05f);
                cluster_centroids_[c][i] = val;
                norm += val * val;
            }
            norm = std::sqrt(norm) + 1e-7f;
            for (int i = 0; i < 512; ++i) {
                cluster_centroids_[c][i] /= norm;
            }
            db.saveClusterCentroid(c, cluster_centroids_[c], 0);
        }
        centroids_loaded_ = true;
    }
}

std::vector<float> RecommendEngine::computeContextualVector(const std::vector<float>& sessionVector, float timeWeight, float deviceWeight) {
    std::vector<float> result(512, 0.0f);
    if (sessionVector.size() < 512) return result;

    float wSession = 0.70f;
    float wTime = 0.15f * timeWeight;
    float wDevice = 0.15f * deviceWeight;

    float totalWeight = wSession + wTime + wDevice;
    if (totalWeight <= 0.0f) totalWeight = 1.0f;
    wSession /= totalWeight;
    wTime /= totalWeight;
    wDevice /= totalWeight;

#if defined(__ARM_NEON) || defined(__aarch64__)
    for (int i = 0; i < 512; i += 4) {
        float32x4_t v_session = vld1q_f32(&sessionVector[i]);
        float32x4_t v_time = vld1q_f32(&time_prototype_vector_[i]);
        float32x4_t v_device = vld1q_f32(&device_prototype_vector_[i]);

        float32x4_t v_res = vmulq_n_f32(v_session, wSession);
        v_res = vmlaq_n_f32(v_res, v_time, wTime);
        v_res = vmlaq_n_f32(v_res, v_device, wDevice);

        vst1q_f32(&result[i], v_res);
    }
#else
    for (int i = 0; i < 512; ++i) {
        result[i] = (sessionVector[i] * wSession) + (time_prototype_vector_[i] * wTime) + (device_prototype_vector_[i] * wDevice);
    }
#endif

    float norm = 0.0f;
    for (float val : result) norm += val * val;
    norm = std::sqrt(norm) + 1e-7f;
    for (float& val : result) val /= norm;

    return result;
}

std::vector<int> RecommendEngine::findClosestClusters(const std::vector<float>& queryVector, int topK) {
    ensureCentroidsLoaded();
    if (queryVector.size() < 512) return {0, 1};

    std::vector<std::pair<float, int>> clusterScores;
    clusterScores.reserve(16);

    for (int c = 0; c < 16; ++c) {
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;

#if defined(__ARM_NEON) || defined(__aarch64__)
        for (int i = 0; i < 512; i += 4) {
            float32x4_t v_query = vld1q_f32(&queryVector[i]);
            float32x4_t v_centroid = vld1q_f32(&cluster_centroids_[c][i]);

            float32x4_t v_dot = vmulq_f32(v_query, v_centroid);
            float32x2_t v_dot2 = vpadd_f32(vget_low_f32(v_dot), vget_high_f32(v_dot));
            dotProduct += vget_lane_f32(v_dot2, 0) + vget_lane_f32(v_dot2, 1);

            float32x4_t v_sqA = vmulq_f32(v_query, v_query);
            float32x2_t v_sqA2 = vpadd_f32(vget_low_f32(v_sqA), vget_high_f32(v_sqA));
            normA += vget_lane_f32(v_sqA2, 0) + vget_lane_f32(v_sqA2, 1);

            float32x4_t v_sqB = vmulq_f32(v_centroid, v_centroid);
            float32x2_t v_sqB2 = vpadd_f32(vget_low_f32(v_sqB), vget_high_f32(v_sqB));
            normB += vget_lane_f32(v_sqB2, 0) + vget_lane_f32(v_sqB2, 1);
        }
#else
        for (int i = 0; i < 512; ++i) {
            dotProduct += queryVector[i] * cluster_centroids_[c][i];
            normA += queryVector[i] * queryVector[i];
            normB += cluster_centroids_[c][i] * cluster_centroids_[c][i];
        }
#endif

        float cosineSim = dotProduct / (std::sqrt(normA) * std::sqrt(normB) + 1e-7f);
        clusterScores.push_back({cosineSim, c});
    }

    std::sort(clusterScores.begin(), clusterScores.end(), [](const auto& a, const auto& b) {
        return a.first > b.first;
    });

    std::vector<int> result;
    for (int i = 0; i < topK && i < static_cast<int>(clusterScores.size()); ++i) {
        result.push_back(clusterScores[i].second);
    }
    return result;
}

std::vector<Recommendation> RecommendEngine::rankHybridCandidates(
    const std::vector<float>& queryVector,
    const std::vector<int>& candidateTrackIds,
    float bpmTarget,
    float satiationPenaltyWeight,
    int limit
) {
    auto& db = StreamifyDB::getInstance();
    auto& vecStore = VectorStore::getInstance();

    float queryNorm = 0.0f;
    for (float v : queryVector) queryNorm += v * v;
    queryNorm = std::sqrt(queryNorm) + 1e-7f;

    std::vector<int> likedIds = db.getUserLikedTrackIds(1);
    std::unordered_set<int> likedSet(likedIds.begin(), likedIds.end());

    std::vector<Recommendation> scored;
    scored.reserve(candidateTrackIds.size());

    for (int tid : candidateTrackIds) {
        auto optTrack = db.getTrackById(tid);
        if (!optTrack) continue;

        // Try getting embedding from db blob or vecStore
        std::vector<float> trackVec = db.getTrackEmbedding(tid);
        if (trackVec.empty() && optTrack->vector_offset >= 0) {
            trackVec = vecStore.getVectorAt(optTrack->vector_offset);
        }

        float cosineSim = 0.0f;
        if (trackVec.size() >= 512 && queryVector.size() >= 512) {
            float dot = 0.0f;
            float normB = 0.0f;
#if defined(__ARM_NEON) || defined(__aarch64__)
            for (int i = 0; i < 512; i += 4) {
                float32x4_t v_q = vld1q_f32(&queryVector[i]);
                float32x4_t v_t = vld1q_f32(&trackVec[i]);

                float32x4_t v_dot = vmulq_f32(v_q, v_t);
                float32x2_t v_dot2 = vpadd_f32(vget_low_f32(v_dot), vget_high_f32(v_dot));
                dot += vget_lane_f32(v_dot2, 0) + vget_lane_f32(v_dot2, 1);

                float32x4_t v_sq = vmulq_f32(v_t, v_t);
                float32x2_t v_sq2 = vpadd_f32(vget_low_f32(v_sq), vget_high_f32(v_sq));
                normB += vget_lane_f32(v_sq2, 0) + vget_lane_f32(v_sq2, 1);
            }
#else
            for (int i = 0; i < 512; ++i) {
                dot += queryVector[i] * trackVec[i];
                normB += trackVec[i] * trackVec[i];
            }
#endif
            cosineSim = dot / (queryNorm * (std::sqrt(normB) + 1e-7f));
        }

        // Gaussian BPM Alignment Score (sigma = 20 BPM)
        float bpmDiff = std::abs(static_cast<float>(optTrack->bpm) - bpmTarget);
        float bpmScore = std::exp(-0.5f * std::pow(bpmDiff / 20.0f, 2.0f));

        // Satiation Penalty
        float satiation = db.getTrackSatiationPenalty(tid);
        float baseAffinity = (likedSet.count(tid) > 0) ? 0.15f : 0.05f;

        // 60% Vector + 25% BPM + 15% Base - Satiation
        float finalScore = (0.60f * cosineSim) + (0.25f * bpmScore) + baseAffinity - (satiation * satiationPenaltyWeight);

        Recommendation rec;
        rec.trackId = tid;
        rec.score = finalScore;
        rec.vectorScore = cosineSim;
        rec.bpmMatchScore = bpmScore;
        scored.push_back(rec);
    }

    size_t targetK = std::min(scored.size(), static_cast<size_t>(limit));
    if (targetK > 0) {
        std::partial_sort(
            scored.begin(),
            scored.begin() + targetK,
            scored.end(),
            [](const Recommendation& a, const Recommendation& b) {
                return a.score > b.score;
            }
        );
        scored.resize(targetK);
    }

    return scored;
}

float RecommendEngine::getTargetBpmForTimeSlot(int slotOrdinal) {
    switch (slotOrdinal) {
        case 0: return 130.0f; // Morning (Energy)
        case 1: return 115.0f; // Afternoon (Focus)
        case 2: return 100.0f; // Evening (Unwind)
        case 3: return 85.0f;  // Night (Mellow)
        default: return 115.0f;
    }
}

