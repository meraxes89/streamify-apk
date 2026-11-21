#ifndef STREAMIFY_DB_H
#define STREAMIFY_DB_H

#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include "../third_party/sqlite3/sqlite3.h"

struct StreamifyTrack {
    int id;
    std::string filepath;
    std::string title;
    std::string artist;
    std::string album;
    int duration_sec;
    double bpm;
    std::string key;
    int vector_offset;
    std::string cover_art_path;
    std::string lyrics_path;
    std::string source;
    int is_processed;
    std::string download_quality;
    int play_count;
    int64_t last_played_timestamp;
};

struct StreamifyUser {
    int id;
    std::string username;
    std::string pin_hash;
};

class StreamifyDB {
public:
    static StreamifyDB& getInstance();

    bool init(const std::string& db_path);
    std::optional<StreamifyTrack> getTrackById(int track_id);
    std::optional<StreamifyTrack> getTrackByVectorOffset(int offset);
    std::vector<StreamifyTrack> getAllTracks();
    std::vector<StreamifyTrack> searchTracks(const std::string& query);
    int insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, double bpm);
    int upsertStreamedTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, const std::string& cover_art_path, const std::string& lyrics_path, double bpm, const std::string& key);
    bool recordTrackPlay(int track_id);
    std::vector<StreamifyTrack> getTopPlayedTracks(int limit = 20);
    int findFuzzyTrackMatch(const std::string& title, const std::string& artist);
    std::vector<StreamifyTrack> getTracksBatch(int offset, int limit);

    // Multi-User Profile & Session Methods
    std::optional<StreamifyUser> registerOrLoginUser(const std::string& username, const std::string& pin);
    std::string createSession(int user_id);
    std::optional<StreamifyUser> validateSession(const std::string& token);
    bool deleteSession(const std::string& token);

    // Per-User Liked Songs Methods
    std::vector<int> getUserLikedTrackIds(int user_id);
    std::vector<StreamifyTrack> getUserLikedTracks(int user_id);
    bool toggleUserLikedTrack(int user_id, int track_id, bool& out_is_liked);

    // Track metadata & vector updates
    bool updateTrackVectorOffset(int track_id, int offset);
    bool updateTrackCoverArt(int track_id, const std::string& cover_art_path);
    bool updateTrackMetadata(int track_id, const std::string& title, const std::string& artist, const std::string& album);
    bool updateTrackBPM(int track_id, double bpm);
    bool updateTrackKey(int track_id, const std::string& key);

    // Behavioral Transition & Circadian Pattern Methods
    bool insertTransition(int user_id, int from_track_id, int to_track_id, const std::string& type);
    float getTransitionProbability(int user_id, int from_track_id, int to_track_id);
    int getSkipCount(int user_id, int from_track_id, int to_track_id);
    int getTrackTotalSkipCount(int user_id, int track_id);
    bool logEngagement(int track_id, int duration_sec, float completion_ratio, int hour_of_day);
    float getCircadianAvgBPM(int hour_of_day);
    std::string getCircadianSlot(int hour_of_day);

    // Project Nexus: Scrubber Hook Telemetry, Co-occurrence & Markov Chains
    bool logHookTelemetry(int track_id, int64_t favorite_seek_ms, int lyrics_dwell_sec, int volume_flare);
    bool recordTrackCooccurrence(int track_a_id, int track_b_id);
    std::vector<int> getCooccurrenceCandidates(int track_id, int limit = 10);
    int64_t getFavoriteSeekMs(int track_id);
    float getTrackSatiationPenalty(int track_id);
    bool recordMarkovTransition(int from_track_id, int to_track_id);
    float getMarkovProbability(int from_track_id, int to_track_id);
    bool record2ndOrderMarkovTransition(int track_a, int track_b, int track_c);
    float get2ndOrderMarkovProbability(int track_a, int track_b, int track_c, float alpha = 0.1f);
    int getTotalUniqueTracks();
    int getRecentPlayCount(int track_id, int64_t window_ms);
    int64_t getLastPlayedMs(int track_id);

    // Hybrid Asymmetric Recommendation Engine (K-Means & Last.fm Similarity)
    bool saveClusterCentroid(int cluster_id, const std::vector<float>& centroid, int track_count);
    std::vector<std::pair<int, std::vector<float>>> getClusterCentroids();
    bool assignTrackToCluster(int track_id, int cluster_id, float distance_to_centroid);
    std::vector<int> getTracksInClusters(const std::vector<int>& cluster_ids, int limit = 100);
    bool updateTrackEmbedding(int track_id, const float* embedding_512);
    std::vector<float> getTrackEmbedding(int track_id);
    bool cacheSimilarTracks(int track_id, const std::vector<std::string>& titles, const std::vector<std::string>& artists, const std::vector<std::string>& mbids, const std::vector<float>& weights);
    std::vector<std::pair<std::string, float>> getCachedSimilarTracks(int track_id);

    // Atomic Database Purge
    bool nukeDatabase();

private:
    StreamifyDB() = default;
    ~StreamifyDB();
    sqlite3* getConnection();
    void finalizeStatements();

    std::string db_path_{"streamify.db"};
    sqlite3* shared_db_{nullptr};
    std::recursive_mutex db_mutex_;

    // High-performance prepared statement caches
    sqlite3_stmt* stmt_get_track_by_id_{nullptr};
    sqlite3_stmt* stmt_get_track_by_vec_{nullptr};
    sqlite3_stmt* stmt_record_play_{nullptr};
    sqlite3_stmt* stmt_user_liked_ids_{nullptr};
};

#endif // STREAMIFY_DB_H
