#include "StreamifyDB.h"
#include "../util/stlog.h"
#include <random>
#include <sstream>
#include <iomanip>
#include <functional>
#include <algorithm>
#include <utility>
#include <set>

static std::string hashPin(const std::string& username, const std::string& pin) {
    std::hash<std::string> hasher;
    size_t val = hasher("streamify_salt_" + username + "_" + pin);
    std::stringstream ss;
    ss << std::hex << val;
    return ss.str();
}

static std::string generateRandomToken() {
    static std::random_device rd;
    static std::mt19937_64 gen(rd());
    static std::uniform_int_distribution<uint64_t> dis;
    std::stringstream ss;
    ss << std::hex << std::setfill('0') << std::setw(16) << dis(gen) << std::setw(16) << dis(gen);
    return ss.str();
}

StreamifyDB& StreamifyDB::getInstance() {
    static StreamifyDB instance;
    return instance;
}

StreamifyDB::~StreamifyDB() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    finalizeStatements();
    if (shared_db_) {
        sqlite3_close_v2(shared_db_);
        shared_db_ = nullptr;
    }
}

bool StreamifyDB::init(const std::string& db_path) {
    db_path_ = db_path;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* schema_init = R"(
        CREATE TABLE IF NOT EXISTS tracks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            filepath TEXT UNIQUE NOT NULL,
            title TEXT,
            artist TEXT,
            album TEXT DEFAULT 'Single',
            duration_sec INTEGER DEFAULT 180,
            bpm REAL DEFAULT 120.0,
            key TEXT DEFAULT 'C',
            vector_offset INTEGER DEFAULT -1,
            cover_art_path TEXT,
            lyrics_path TEXT,
            source TEXT DEFAULT 'local',
            is_processed INTEGER DEFAULT 0,
            download_quality TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            pin_hash TEXT NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS user_sessions (
            token TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS user_liked_songs (
            user_id INTEGER NOT NULL,
            track_id INTEGER NOT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (user_id, track_id),
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY (track_id) REFERENCES tracks(id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS user_transitions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            from_track_id INTEGER NOT NULL,
            to_track_id INTEGER NOT NULL,
            event_type TEXT NOT NULL, -- 'play' or 'skip'
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS user_circadian_patterns (
            user_id INTEGER,
            hour_of_day INTEGER,
            avg_bpm REAL DEFAULT 120.0,
            play_count INTEGER DEFAULT 0,
            PRIMARY KEY (user_id, hour_of_day)
        );
        CREATE TABLE IF NOT EXISTS user_engagement_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            track_id INTEGER NOT NULL,
            duration_played_sec INTEGER DEFAULT 0,
            completion_ratio REAL DEFAULT 0.0,
            hour_of_day INTEGER DEFAULT 12,
            action_type TEXT DEFAULT 'PLAY',
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS track_hook_telemetry (
            track_id INTEGER PRIMARY KEY,
            favorite_seek_ms INTEGER DEFAULT 0,
            lyrics_dwell_sec INTEGER DEFAULT 0,
            volume_flare_count INTEGER DEFAULT 0,
            satiation_score REAL DEFAULT 0.0,
            last_played_timestamp INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS track_cooccurrence (
            track_a_id INTEGER NOT NULL,
            track_b_id INTEGER NOT NULL,
            weight REAL DEFAULT 1.0,
            pair_count INTEGER DEFAULT 1,
            last_paired_timestamp INTEGER DEFAULT 0,
            PRIMARY KEY (track_a_id, track_b_id)
        );
        CREATE TABLE IF NOT EXISTS markov_transitions (
            from_track_id INTEGER,
            to_track_id INTEGER,
            transition_count INTEGER DEFAULT 1,
            PRIMARY KEY (from_track_id, to_track_id)
        );
        CREATE TABLE IF NOT EXISTS markov_transitions_2nd (
            track_a_id INTEGER,
            track_b_id INTEGER,
            track_c_id INTEGER,
            transition_count INTEGER DEFAULT 1,
            PRIMARY KEY (track_a_id, track_b_id, track_c_id)
        );
        CREATE TABLE IF NOT EXISTS vector_clusters (
            cluster_id INTEGER PRIMARY KEY,
            centroid BLOB,
            track_count INTEGER DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS track_clusters (
            track_id INTEGER,
            cluster_id INTEGER,
            distance_to_centroid REAL,
            PRIMARY KEY (track_id, cluster_id)
        );
        CREATE TABLE IF NOT EXISTS similar_tracks (
            track_id INTEGER,
            similar_track_mbid TEXT,
            similar_title TEXT,
            similar_artist TEXT,
            lastfm_weight REAL,
            is_resolved INTEGER DEFAULT 0,
            cached_at INTEGER,
            PRIMARY KEY (track_id, similar_title, similar_artist)
        );
        CREATE INDEX IF NOT EXISTS idx_tracks_filepath ON tracks(filepath);
        CREATE INDEX IF NOT EXISTS idx_tracks_title ON tracks(title);
        CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist);
        CREATE INDEX IF NOT EXISTS idx_tracks_vector_offset ON tracks(vector_offset);
        CREATE INDEX IF NOT EXISTS idx_transitions_user ON user_transitions(user_id, from_track_id, event_type);
        CREATE INDEX IF NOT EXISTS idx_tracks_play_count ON tracks(play_count DESC, last_played_timestamp DESC);
        CREATE INDEX IF NOT EXISTS idx_engagement_hour ON user_engagement_log(hour_of_day, action_type);
        CREATE INDEX IF NOT EXISTS idx_cooccur_a ON track_cooccurrence(track_a_id, weight DESC);
        CREATE INDEX IF NOT EXISTS idx_markov_from ON markov_transitions(from_track_id, transition_count DESC);
        CREATE INDEX IF NOT EXISTS idx_markov_2nd ON markov_transitions_2nd(track_a_id, track_b_id, transition_count DESC);
        CREATE INDEX IF NOT EXISTS idx_track_clusters_cid ON track_clusters(cluster_id, distance_to_centroid ASC);
        CREATE INDEX IF NOT EXISTS idx_similar_tracks_tid ON similar_tracks(track_id, lastfm_weight DESC);
    )";

    char* err = nullptr;
    if (sqlite3_exec(db, schema_init, nullptr, nullptr, &err) != SQLITE_OK) {
        if (err) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[StreamifyDB] Migration error: %s", err);
            sqlite3_free(err);
        }
    }

    // Safe column migrations for existing databases
    sqlite3_exec(db, "ALTER TABLE tracks ADD COLUMN play_count INTEGER DEFAULT 1;", nullptr, nullptr, nullptr);
    sqlite3_exec(db, "ALTER TABLE tracks ADD COLUMN last_played_timestamp INTEGER DEFAULT 0;", nullptr, nullptr, nullptr);
    sqlite3_exec(db, "ALTER TABLE tracks ADD COLUMN embedding BLOB;", nullptr, nullptr, nullptr);
    sqlite3_exec(db, "ALTER TABLE tracks ADD COLUMN mbid TEXT DEFAULT '';", nullptr, nullptr, nullptr);

    // Ensure default user ID=1 exists
    const char* bootstrap_user = "INSERT OR IGNORE INTO users (id, username, pin_hash) VALUES (1, 'default_user', 'default_hash');";
    sqlite3_exec(db, bootstrap_user, nullptr, nullptr, nullptr);

    return true;
}

void StreamifyDB::finalizeStatements() {
    if (stmt_get_track_by_id_) { sqlite3_finalize(stmt_get_track_by_id_); stmt_get_track_by_id_ = nullptr; }
    if (stmt_get_track_by_vec_) { sqlite3_finalize(stmt_get_track_by_vec_); stmt_get_track_by_vec_ = nullptr; }
    if (stmt_record_play_) { sqlite3_finalize(stmt_record_play_); stmt_record_play_ = nullptr; }
    if (stmt_user_liked_ids_) { sqlite3_finalize(stmt_user_liked_ids_); stmt_user_liked_ids_ = nullptr; }
}

sqlite3* StreamifyDB::getConnection() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    if (shared_db_ == nullptr) {
        if (sqlite3_open_v2(db_path_.c_str(), &shared_db_, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nullptr) != SQLITE_OK) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyNative", "[StreamifyDB] Cannot open database");
            return nullptr;
        }
        char* err = nullptr;
        sqlite3_exec(shared_db_, "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000; PRAGMA cache_size = -8000; PRAGMA temp_store = MEMORY; PRAGMA mmap_size = 268435456;", nullptr, nullptr, &err);
        if (err) sqlite3_free(err);
    }
    return shared_db_;
}

std::optional<StreamifyTrack> StreamifyDB::getTrackById(int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    if (!stmt_get_track_by_id_) {
        const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE id = ?;";
        if (sqlite3_prepare_v2(db, sql, -1, &stmt_get_track_by_id_, nullptr) != SQLITE_OK) return std::nullopt;
    }

    sqlite3_reset(stmt_get_track_by_id_);
    sqlite3_clear_bindings(stmt_get_track_by_id_);
    sqlite3_bind_int(stmt_get_track_by_id_, 1, track_id);

    std::optional<StreamifyTrack> track;
    if (sqlite3_step(stmt_get_track_by_id_) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt_get_track_by_id_, 0);
        t.filepath = sqlite3_column_text(stmt_get_track_by_id_, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 1)) : "";
        t.title = sqlite3_column_text(stmt_get_track_by_id_, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 2)) : "";
        t.artist = sqlite3_column_text(stmt_get_track_by_id_, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 3)) : "";
        t.album = sqlite3_column_text(stmt_get_track_by_id_, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt_get_track_by_id_, 5);
        t.bpm = sqlite3_column_double(stmt_get_track_by_id_, 6);
        t.key = sqlite3_column_text(stmt_get_track_by_id_, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt_get_track_by_id_, 8);
        t.cover_art_path = sqlite3_column_text(stmt_get_track_by_id_, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt_get_track_by_id_, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 10)) : "";
        t.source = sqlite3_column_text(stmt_get_track_by_id_, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt_get_track_by_id_, 12);
        t.download_quality = sqlite3_column_text(stmt_get_track_by_id_, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_id_, 13)) : "";
        track = t;
    }
    sqlite3_reset(stmt_get_track_by_id_);
    return track;
}

std::optional<StreamifyTrack> StreamifyDB::getTrackByVectorOffset(int offset) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return std::nullopt;

    if (!stmt_get_track_by_vec_) {
        const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE vector_offset = ?;";
        if (sqlite3_prepare_v2(db, sql, -1, &stmt_get_track_by_vec_, nullptr) != SQLITE_OK) return std::nullopt;
    }

    sqlite3_reset(stmt_get_track_by_vec_);
    sqlite3_clear_bindings(stmt_get_track_by_vec_);
    sqlite3_bind_int(stmt_get_track_by_vec_, 1, offset);

    std::optional<StreamifyTrack> track;
    if (sqlite3_step(stmt_get_track_by_vec_) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt_get_track_by_vec_, 0);
        t.filepath = sqlite3_column_text(stmt_get_track_by_vec_, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 1)) : "";
        t.title = sqlite3_column_text(stmt_get_track_by_vec_, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 2)) : "";
        t.artist = sqlite3_column_text(stmt_get_track_by_vec_, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 3)) : "";
        t.album = sqlite3_column_text(stmt_get_track_by_vec_, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt_get_track_by_vec_, 5);
        t.bpm = sqlite3_column_double(stmt_get_track_by_vec_, 6);
        t.key = sqlite3_column_text(stmt_get_track_by_vec_, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt_get_track_by_vec_, 8);
        t.cover_art_path = sqlite3_column_text(stmt_get_track_by_vec_, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt_get_track_by_vec_, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 10)) : "";
        t.source = sqlite3_column_text(stmt_get_track_by_vec_, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt_get_track_by_vec_, 12);
        t.download_quality = sqlite3_column_text(stmt_get_track_by_vec_, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt_get_track_by_vec_, 13)) : "";
        track = t;
    }
    sqlite3_reset(stmt_get_track_by_vec_);
    return track;
}

std::vector<StreamifyTrack> StreamifyDB::getAllTracks() {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks ORDER BY id ASC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

std::vector<StreamifyTrack> StreamifyDB::searchTracks(const std::string& query) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality FROM tracks WHERE title LIKE ? OR artist LIKE ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    std::string pattern = "%" + query + "%";
    sqlite3_bind_text(stmt, 1, pattern.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, pattern.c_str(), -1, SQLITE_TRANSIENT);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

int StreamifyDB::insertTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, double bpm) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return -1;

    std::string source = "local";
    std::string path_lower = filepath;
    std::transform(path_lower.begin(), path_lower.end(), path_lower.begin(), ::tolower);
    std::string album_lower = album;
    std::transform(album_lower.begin(), album_lower.end(), album_lower.begin(), ::tolower);

    if (filepath.rfind("http://", 0) == 0 || filepath.rfind("https://", 0) == 0 || filepath.rfind("online://", 0) == 0) {
        source = "online";
    } else if (album_lower == "streamify" || path_lower.find("streamify") != std::string::npos) {
        source = "streamify_download";
    }

    const char* sql = "INSERT INTO tracks (filepath, title, artist, album, duration_sec, bpm, source) VALUES (?, ?, ?, ?, ?, ?, ?) "
                      "ON CONFLICT(filepath) DO UPDATE SET title=excluded.title, artist=excluded.artist, album=excluded.album, duration_sec=excluded.duration_sec, bpm=excluded.bpm, source=excluded.source;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return -1;

    sqlite3_bind_text(stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 2, title.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 3, artist.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(stmt, 4, album.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 5, duration_sec);
    sqlite3_bind_double(stmt, 6, bpm);
    sqlite3_bind_text(stmt, 7, source.c_str(), -1, SQLITE_TRANSIENT);

    sqlite3_step(stmt);
    sqlite3_finalize(stmt);

    int id = -1;
    const char* sel_sql = "SELECT id FROM tracks WHERE filepath = ?;";
    sqlite3_stmt* sel_stmt = nullptr;
    if (sqlite3_prepare_v2(db, sel_sql, -1, &sel_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(sel_stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(sel_stmt) == SQLITE_ROW) {
            id = sqlite3_column_int(sel_stmt, 0);
        }
        sqlite3_finalize(sel_stmt);
    }
    return id;
}

int StreamifyDB::upsertStreamedTrack(const std::string& filepath, const std::string& title, const std::string& artist, const std::string& album, int duration_sec, const std::string& cover_art_path, const std::string& lyrics_path, double bpm, const std::string& key) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return -1;

    int64_t now = static_cast<int64_t>(std::time(nullptr));

    // 1. Check if track already exists by title + artist or filepath
    const char* check_sql = "SELECT id, play_count FROM tracks WHERE (title = ? AND artist = ?) OR filepath = ? LIMIT 1;";
    sqlite3_stmt* check_stmt = nullptr;
    int existing_id = -1;
    int play_count = 1;

    if (sqlite3_prepare_v2(db, check_sql, -1, &check_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(check_stmt, 1, title.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(check_stmt, 2, artist.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(check_stmt, 3, filepath.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(check_stmt) == SQLITE_ROW) {
            existing_id = sqlite3_column_int(check_stmt, 0);
            play_count = sqlite3_column_int(check_stmt, 1) + 1;
        }
        sqlite3_finalize(check_stmt);
    }

    if (existing_id > 0) {
        // Update play_count, last_played_timestamp, and latest stream URL
        const char* update_sql = "UPDATE tracks SET play_count = ?, last_played_timestamp = ?, filepath = ?, cover_art_path = CASE WHEN (cover_art_path IS NULL OR cover_art_path = '') THEN ? ELSE cover_art_path END WHERE id = ?;";
        sqlite3_stmt* upd_stmt = nullptr;
        if (sqlite3_prepare_v2(db, update_sql, -1, &upd_stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(upd_stmt, 1, play_count);
            sqlite3_bind_int64(upd_stmt, 2, now);
            sqlite3_bind_text(upd_stmt, 3, filepath.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(upd_stmt, 4, cover_art_path.c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_int(upd_stmt, 5, existing_id);
            sqlite3_step(upd_stmt);
            sqlite3_finalize(upd_stmt);
        }
        return existing_id;
    }

    // 2. Insert new streamed track
    const char* ins_sql = "INSERT INTO tracks (filepath, title, artist, album, duration_sec, bpm, key, cover_art_path, lyrics_path, source, play_count, last_played_timestamp, is_processed) "
                          "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'online_stream', 1, ?, ?);";
    sqlite3_stmt* ins_stmt = nullptr;
    if (sqlite3_prepare_v2(db, ins_sql, -1, &ins_stmt, nullptr) != SQLITE_OK) return -1;

    sqlite3_bind_text(ins_stmt, 1, filepath.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(ins_stmt, 2, title.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(ins_stmt, 3, artist.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(ins_stmt, 4, album.empty() ? "Online Stream" : album.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(ins_stmt, 5, duration_sec);
    sqlite3_bind_double(ins_stmt, 6, bpm);
    sqlite3_bind_text(ins_stmt, 7, key.empty() ? "C" : key.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(ins_stmt, 8, cover_art_path.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_text(ins_stmt, 9, lyrics_path.c_str(), -1, SQLITE_TRANSIENT);
    sqlite3_bind_int64(ins_stmt, 10, now);
    sqlite3_bind_int(ins_stmt, 11, bpm > 0 ? 1 : 0);

    sqlite3_step(ins_stmt);
    sqlite3_finalize(ins_stmt);

    return static_cast<int>(sqlite3_last_insert_rowid(db));
}

bool StreamifyDB::recordTrackPlay(int track_id) {
    if (track_id <= 0) return false;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    int64_t now = static_cast<int64_t>(std::time(nullptr));
    const char* sql = "UPDATE tracks SET play_count = play_count + 1, last_played_timestamp = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int64(stmt, 1, now);
    sqlite3_bind_int(stmt, 2, track_id);
    sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    return true;
}

std::vector<StreamifyTrack> StreamifyDB::getTopPlayedTracks(int limit) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality, play_count, last_played_timestamp FROM tracks WHERE play_count > 0 ORDER BY play_count DESC, last_played_timestamp DESC LIMIT ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    sqlite3_bind_int(stmt, 1, limit);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        t.play_count = sqlite3_column_int(stmt, 14);
        t.last_played_timestamp = sqlite3_column_int64(stmt, 15);
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

std::optional<StreamifyUser> StreamifyDB::registerOrLoginUser(const std::string& username, const std::string& pin) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || username.empty() || pin.empty()) return std::nullopt;

    std::string expected_hash = hashPin(username, pin);
    const char* select_sql = "SELECT id, username, pin_hash FROM users WHERE username = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, select_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, username.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            std::string stored_hash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
            if (stored_hash == expected_hash) {
                StreamifyUser user;
                user.id = sqlite3_column_int(stmt, 0);
                user.username = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
                user.pin_hash = stored_hash;
                sqlite3_finalize(stmt);
                return user;
            } else {
                sqlite3_finalize(stmt);
                return std::nullopt;
            }
        }
        sqlite3_finalize(stmt);
    }

    const char* insert_sql = "INSERT INTO users (username, pin_hash) VALUES (?, ?);";
    if (sqlite3_prepare_v2(db, insert_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, username.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, expected_hash.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            int new_id = sqlite3_last_insert_rowid(db);
            sqlite3_finalize(stmt);
            return StreamifyUser{new_id, username, expected_hash};
        }
        sqlite3_finalize(stmt);
    }
    return std::nullopt;
}

std::string StreamifyDB::createSession(int user_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return "";
    std::string token = generateRandomToken();
    const char* sql = "INSERT INTO user_sessions (token, user_id) VALUES (?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, user_id);
        if (sqlite3_step(stmt) == SQLITE_DONE) {
            sqlite3_finalize(stmt);
            return token;
        }
        sqlite3_finalize(stmt);
    }
    return "";
}

std::optional<StreamifyUser> StreamifyDB::validateSession(const std::string& token) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || token.empty()) return std::nullopt;
    const char* sql = R"(
        SELECT u.id, u.username, u.pin_hash 
        FROM user_sessions s 
        JOIN users u ON s.user_id = u.id 
        WHERE s.token = ?;
    )";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            StreamifyUser u;
            u.id = sqlite3_column_int(stmt, 0);
            u.username = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
            u.pin_hash = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2));
            sqlite3_finalize(stmt);
            return u;
        }
        sqlite3_finalize(stmt);
    }
    return std::nullopt;
}

bool StreamifyDB::deleteSession(const std::string& token) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "DELETE FROM user_sessions WHERE token = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, token.c_str(), -1, SQLITE_TRANSIENT);
        bool success = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return success;
    }
    return false;
}

std::vector<int> StreamifyDB::getUserLikedTrackIds(int user_id) {
    std::vector<int> ids;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return ids;
    const char* sql = "SELECT track_id FROM user_liked_songs WHERE user_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            ids.push_back(sqlite3_column_int(stmt, 0));
        }
        sqlite3_finalize(stmt);
    }
    return ids;
}

std::vector<StreamifyTrack> StreamifyDB::getUserLikedTracks(int user_id) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;
    const char* sql = R"(
        SELECT t.id, t.filepath, t.title, t.artist, t.album, t.duration_sec, t.bpm, t.key, t.vector_offset, t.cover_art_path, t.lyrics_path, t.source, t.is_processed, t.download_quality
        FROM user_liked_songs l 
        JOIN tracks t ON l.track_id = t.id 
        WHERE l.user_id = ? 
        ORDER BY l.created_at DESC;
    )";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        while (sqlite3_step(stmt) == SQLITE_ROW) {
            StreamifyTrack t;
            t.id = sqlite3_column_int(stmt, 0);
            t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
            t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
            t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
            t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
            t.duration_sec = sqlite3_column_int(stmt, 5);
            t.bpm = sqlite3_column_double(stmt, 6);
            t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
            t.vector_offset = sqlite3_column_int(stmt, 8);
            t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
            t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
            t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
            t.is_processed = sqlite3_column_int(stmt, 12);
            t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
            tracks.push_back(t);
        }
        sqlite3_finalize(stmt);
    }
    return tracks;
}

bool StreamifyDB::toggleUserLikedTrack(int user_id, int track_id, bool& out_is_liked) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* check_sql = "SELECT 1 FROM user_liked_songs WHERE user_id = ? AND track_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    bool exists = false;
    if (sqlite3_prepare_v2(db, check_sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            exists = true;
        }
        sqlite3_finalize(stmt);
    }

    if (exists) {
        const char* del_sql = "DELETE FROM user_liked_songs WHERE user_id = ? AND track_id = ?;";
        if (sqlite3_prepare_v2(db, del_sql, -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(stmt, 1, user_id);
            sqlite3_bind_int(stmt, 2, track_id);
            sqlite3_step(stmt);
            sqlite3_finalize(stmt);
        }
        out_is_liked = false;
    } else {
        const char* add_sql = "INSERT INTO user_liked_songs (user_id, track_id) VALUES (?, ?);";
        if (sqlite3_prepare_v2(db, add_sql, -1, &stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(stmt, 1, user_id);
            sqlite3_bind_int(stmt, 2, track_id);
            sqlite3_step(stmt);
            sqlite3_finalize(stmt);
        }
        out_is_liked = true;
    }
    return true;
}

bool StreamifyDB::updateTrackVectorOffset(int track_id, int offset) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET vector_offset = ?, is_processed = 1 WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, offset);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackCoverArt(int track_id, const std::string& cover_art_path) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET cover_art_path = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, cover_art_path.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackBPM(int track_id, double bpm) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET bpm = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_double(stmt, 1, bpm);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackKey(int track_id, const std::string& key) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET key = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, key.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 2, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::updateTrackMetadata(int track_id, const std::string& title, const std::string& artist, const std::string& album) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "UPDATE tracks SET title = ?, artist = ?, album = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(stmt, 1, title.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, artist.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 3, album.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_int(stmt, 4, track_id);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

bool StreamifyDB::insertTransition(int user_id, int from_track_id, int to_track_id, const std::string& type) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;
    const char* sql = "INSERT INTO user_transitions (user_id, from_track_id, to_track_id, event_type) VALUES (?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        sqlite3_bind_text(stmt, 4, type.c_str(), -1, SQLITE_TRANSIENT);
        bool res = (sqlite3_step(stmt) == SQLITE_DONE);
        sqlite3_finalize(stmt);
        return res;
    }
    return false;
}

float StreamifyDB::getTransitionProbability(int user_id, int from_track_id, int to_track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0.0f;
    
    int transitionCount = 0;
    int totalFromCount = 0;

    const char* sqlCount = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND to_track_id = ? AND event_type = 'play';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sqlCount, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) transitionCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }

    const char* sqlTotal = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND event_type = 'play';";
    if (sqlite3_prepare_v2(db, sqlTotal, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) totalFromCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }

    return (totalFromCount > 0) ? (static_cast<float>(transitionCount) / static_cast<float>(totalFromCount)) : 0.0f;
}

int StreamifyDB::getSkipCount(int user_id, int from_track_id, int to_track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    int skipCount = 0;
    const char* sql = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND from_track_id = ? AND to_track_id = ? AND event_type = 'skip';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, from_track_id);
        sqlite3_bind_int(stmt, 3, to_track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) skipCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return skipCount;
}

int StreamifyDB::getTrackTotalSkipCount(int user_id, int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    int skipCount = 0;
    const char* sql = "SELECT COUNT(*) FROM user_transitions WHERE user_id = ? AND to_track_id = ? AND event_type = 'skip';";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt, 1, user_id);
        sqlite3_bind_int(stmt, 2, track_id);
        if (sqlite3_step(stmt) == SQLITE_ROW) skipCount = sqlite3_column_int(stmt, 0);
        sqlite3_finalize(stmt);
    }
    return skipCount;
}

int StreamifyDB::findFuzzyTrackMatch(const std::string& title, const std::string& artist) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || title.empty()) return -1;

    std::string cleanTitle = title;
    size_t dashPos = cleanTitle.find(" - ");
    if (dashPos != std::string::npos) {
        cleanTitle = cleanTitle.substr(0, dashPos);
    }
    size_t parenPos = cleanTitle.find(" (");
    if (parenPos != std::string::npos) {
        cleanTitle = cleanTitle.substr(0, parenPos);
    }

    const char* sql = "SELECT id FROM tracks WHERE title LIKE ? OR title LIKE ? LIMIT 1;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        std::string pattern1 = "%" + cleanTitle + "%";
        std::string pattern2 = "%" + title + "%";
        sqlite3_bind_text(stmt, 1, pattern1.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(stmt, 2, pattern2.c_str(), -1, SQLITE_TRANSIENT);
        if (sqlite3_step(stmt) == SQLITE_ROW) {
            int foundId = sqlite3_column_int(stmt, 0);
            sqlite3_finalize(stmt);
            return foundId;
        }
        sqlite3_finalize(stmt);
    }
    return -1;
}

std::vector<StreamifyTrack> StreamifyDB::getTracksBatch(int offset, int limit) {
    std::vector<StreamifyTrack> tracks;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return tracks;

    const char* sql = "SELECT id, filepath, title, artist, album, duration_sec, bpm, key, vector_offset, cover_art_path, lyrics_path, source, is_processed, download_quality, play_count, last_played_timestamp FROM tracks ORDER BY id ASC LIMIT ? OFFSET ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return tracks;

    sqlite3_bind_int(stmt, 1, limit);
    sqlite3_bind_int(stmt, 2, offset);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        StreamifyTrack t;
        t.id = sqlite3_column_int(stmt, 0);
        t.filepath = sqlite3_column_text(stmt, 1) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1)) : "";
        t.title = sqlite3_column_text(stmt, 2) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 2)) : "";
        t.artist = sqlite3_column_text(stmt, 3) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 3)) : "";
        t.album = sqlite3_column_text(stmt, 4) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 4)) : "Single";
        t.duration_sec = sqlite3_column_int(stmt, 5);
        t.bpm = sqlite3_column_double(stmt, 6);
        t.key = sqlite3_column_text(stmt, 7) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 7)) : "C";
        t.vector_offset = sqlite3_column_int(stmt, 8);
        t.cover_art_path = sqlite3_column_text(stmt, 9) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 9)) : "";
        t.lyrics_path = sqlite3_column_text(stmt, 10) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 10)) : "";
        t.source = sqlite3_column_text(stmt, 11) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 11)) : "";
        t.is_processed = sqlite3_column_int(stmt, 12);
        t.download_quality = sqlite3_column_text(stmt, 13) ? reinterpret_cast<const char*>(sqlite3_column_text(stmt, 13)) : "";
        t.play_count = sqlite3_column_int(stmt, 14);
        t.last_played_timestamp = sqlite3_column_int64(stmt, 15);
        tracks.push_back(t);
    }
    sqlite3_finalize(stmt);
    return tracks;
}

std::string StreamifyDB::getCircadianSlot(int hour_of_day) {
    if (hour_of_day >= 6 && hour_of_day < 11) return "MORNING";
    if (hour_of_day >= 11 && hour_of_day < 17) return "AFTERNOON";
    if (hour_of_day >= 17 && hour_of_day < 22) return "EVENING";
    return "NIGHT";
}

bool StreamifyDB::logEngagement(int track_id, int duration_sec, float completion_ratio, int hour_of_day) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    std::string action = "COMPLETED";
    if (completion_ratio < 0.10f || duration_sec < 8) action = "SKIP_IMMEDIATE";
    else if (completion_ratio < 0.50f) action = "SKIP_PARTIAL";
    else if (completion_ratio >= 0.85f) action = "COMPLETED";

    const char* sql = "INSERT INTO user_engagement_log (track_id, duration_played_sec, completion_ratio, hour_of_day, action_type) VALUES (?, ?, ?, ?, ?);";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int(stmt, 2, duration_sec);
    sqlite3_bind_double(stmt, 3, static_cast<double>(completion_ratio));
    sqlite3_bind_int(stmt, 4, hour_of_day);
    sqlite3_bind_text(stmt, 5, action.c_str(), -1, SQLITE_STATIC);

    bool ok = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);

    // Update hourly aggregate BPM
    auto track = getTrackById(track_id);
    if (track && track->bpm > 40.0 && action != "SKIP_IMMEDIATE") {
        const char* bpm_sql = "INSERT INTO user_circadian_patterns (user_id, hour_of_day, avg_bpm, play_count) VALUES (1, ?, ?, 1) "
                              "ON CONFLICT(user_id, hour_of_day) DO UPDATE SET avg_bpm = (user_circadian_patterns.avg_bpm * 0.8) + (EXCLUDED.avg_bpm * 0.2), play_count = user_circadian_patterns.play_count + 1;";
        sqlite3_stmt* b_stmt = nullptr;
        if (sqlite3_prepare_v2(db, bpm_sql, -1, &b_stmt, nullptr) == SQLITE_OK) {
            sqlite3_bind_int(b_stmt, 1, hour_of_day);
            sqlite3_bind_double(b_stmt, 2, track->bpm);
            sqlite3_step(b_stmt);
            sqlite3_finalize(b_stmt);
        }
    }

    return ok;
}

float StreamifyDB::getCircadianAvgBPM(int hour_of_day) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 120.0f;

    const char* sql = "SELECT avg_bpm FROM user_circadian_patterns WHERE user_id = 1 AND hour_of_day = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return 120.0f;

    sqlite3_bind_int(stmt, 1, hour_of_day);
    float bpm = 120.0f;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        bpm = static_cast<float>(sqlite3_column_double(stmt, 0));
    }
    sqlite3_finalize(stmt);

    if (bpm <= 0.0f) {
        if (hour_of_day >= 6 && hour_of_day < 11) bpm = 130.0f; // High energy morning
        else if (hour_of_day >= 11 && hour_of_day < 17) bpm = 85.0f; // Focus afternoon
        else if (hour_of_day >= 17 && hour_of_day < 22) bpm = 118.0f; // Evening upbeat
        else bpm = 95.0f; // Chill night
    }
    return bpm;
}

bool StreamifyDB::logHookTelemetry(int track_id, int64_t favorite_seek_ms, int lyrics_dwell_sec, int volume_flare) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || track_id <= 0) return false;

    int now = static_cast<int>(std::time(nullptr));
    const char* sql = "INSERT INTO track_hook_telemetry (track_id, favorite_seek_ms, lyrics_dwell_sec, volume_flare_count, last_played_timestamp) "
                      "VALUES (?, ?, ?, ?, ?) "
                      "ON CONFLICT(track_id) DO UPDATE SET "
                      "favorite_seek_ms = CASE WHEN EXCLUDED.favorite_seek_ms > 0 THEN EXCLUDED.favorite_seek_ms ELSE track_hook_telemetry.favorite_seek_ms END, "
                      "lyrics_dwell_sec = track_hook_telemetry.lyrics_dwell_sec + EXCLUDED.lyrics_dwell_sec, "
                      "volume_flare_count = track_hook_telemetry.volume_flare_count + EXCLUDED.volume_flare_count, "
                      "last_played_timestamp = EXCLUDED.last_played_timestamp;";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int64(stmt, 2, favorite_seek_ms);
    sqlite3_bind_int(stmt, 3, lyrics_dwell_sec);
    sqlite3_bind_int(stmt, 4, volume_flare);
    sqlite3_bind_int(stmt, 5, now);

    bool ok = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return ok;
}

bool StreamifyDB::recordTrackCooccurrence(int track_a_id, int track_b_id) {
    if (track_a_id <= 0 || track_b_id <= 0 || track_a_id == track_b_id) return false;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    int now = static_cast<int>(std::time(nullptr));
    const char* sql = "INSERT INTO track_cooccurrence (track_a_id, track_b_id, weight, pair_count, last_paired_timestamp) "
                      "VALUES (?, ?, 1.0, 1, ?) "
                      "ON CONFLICT(track_a_id, track_b_id) DO UPDATE SET "
                      "weight = track_cooccurrence.weight + 1.0, "
                      "pair_count = track_cooccurrence.pair_count + 1, "
                      "last_paired_timestamp = EXCLUDED.last_paired_timestamp;";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, track_a_id);
    sqlite3_bind_int(stmt, 2, track_b_id);
    sqlite3_bind_int(stmt, 3, now);

    bool ok = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return ok;
}

std::vector<int> StreamifyDB::getCooccurrenceCandidates(int track_id, int limit) {
    std::vector<int> candidates;
    if (track_id <= 0) return candidates;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return candidates;

    const char* sql = "SELECT track_b_id FROM track_cooccurrence WHERE track_a_id = ? ORDER BY weight DESC LIMIT ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return candidates;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int(stmt, 2, limit);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        candidates.push_back(sqlite3_column_int(stmt, 0));
    }
    sqlite3_finalize(stmt);
    return candidates;
}

int64_t StreamifyDB::getFavoriteSeekMs(int track_id) {
    if (track_id <= 0) return 0;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    const char* sql = "SELECT favorite_seek_ms FROM track_hook_telemetry WHERE track_id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return 0;

    sqlite3_bind_int(stmt, 1, track_id);
    int64_t seek_ms = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        seek_ms = sqlite3_column_int64(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return seek_ms;
}

float StreamifyDB::getTrackSatiationPenalty(int track_id) {
    if (track_id <= 0) return 0.0f;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0.0f;

    // Count plays in the last 72 hours (3 days)
    int now = static_cast<int>(std::time(nullptr));
    int three_days_ago = now - (72 * 3600);

    const char* sql = "SELECT COUNT(*) FROM user_engagement_log WHERE track_id = ? AND created_at >= datetime(?, 'unixepoch');";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return 0.0f;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int(stmt, 2, three_days_ago);

    int count = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        count = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);

    // If played more than 12 times in 3 days, apply burnout cooldown penalty
    if (count >= 20) return 0.85f; // Heavy penalty
    if (count >= 12) return 0.40f; // Moderate cooldown
    return 0.0f;
}

bool StreamifyDB::recordMarkovTransition(int from_track_id, int to_track_id) {
    if (from_track_id <= 0 || to_track_id <= 0 || from_track_id == to_track_id) return false;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* sql = "INSERT INTO markov_transitions (from_track_id, to_track_id, transition_count) VALUES (?, ?, 1) "
                      "ON CONFLICT(from_track_id, to_track_id) DO UPDATE SET transition_count = markov_transitions.transition_count + 1;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, from_track_id);
    sqlite3_bind_int(stmt, 2, to_track_id);

    bool ok = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return ok;
}

float StreamifyDB::getMarkovProbability(int from_track_id, int to_track_id) {
    if (from_track_id <= 0 || to_track_id <= 0) return 0.0f;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0.0f;

    // Get specific transition count
    int pairCount = 0;
    const char* pair_sql = "SELECT transition_count FROM markov_transitions WHERE from_track_id = ? AND to_track_id = ?;";
    sqlite3_stmt* p_stmt = nullptr;
    if (sqlite3_prepare_v2(db, pair_sql, -1, &p_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(p_stmt, 1, from_track_id);
        sqlite3_bind_int(p_stmt, 2, to_track_id);
        if (sqlite3_step(p_stmt) == SQLITE_ROW) {
            pairCount = sqlite3_column_int(p_stmt, 0);
        }
        sqlite3_finalize(p_stmt);
    }
    if (pairCount <= 0) return 0.0f;

    // Get total transitions from this track
    int totalTransitions = 0;
    const char* total_sql = "SELECT SUM(transition_count) FROM markov_transitions WHERE from_track_id = ?;";
    sqlite3_stmt* t_stmt = nullptr;
    if (sqlite3_prepare_v2(db, total_sql, -1, &t_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(t_stmt, 1, from_track_id);
        if (sqlite3_step(t_stmt) == SQLITE_ROW) {
            totalTransitions = sqlite3_column_int(t_stmt, 0);
        }
        sqlite3_finalize(t_stmt);
    }

    if (totalTransitions <= 0) return 0.0f;
    return static_cast<float>(pairCount) / static_cast<float>(totalTransitions);
}

int StreamifyDB::getRecentPlayCount(int track_id, int64_t window_ms) {
    if (track_id <= 0) return 0;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0;

    int now = static_cast<int>(std::time(nullptr));
    int window_sec = static_cast<int>(window_ms / 1000);
    int cutoff = now - window_sec;

    const char* sql = "SELECT COUNT(*) FROM user_engagement_log WHERE track_id = ? AND created_at >= datetime(?, 'unixepoch');";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return 0;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int(stmt, 2, cutoff);

    int count = 0;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        count = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return count;
}

int64_t StreamifyDB::getLastPlayedMs(int track_id) {
    auto track = getTrackById(track_id);
    if (!track) return 0;
    return track->last_played_timestamp * 1000;
}

int StreamifyDB::getTotalUniqueTracks() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 1000;

    const char* sql = "SELECT COUNT(*) FROM tracks;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return 1000;

    int count = 1000;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        count = sqlite3_column_int(stmt, 0);
    }
    sqlite3_finalize(stmt);
    return std::max(10, count);
}

bool StreamifyDB::record2ndOrderMarkovTransition(int track_a, int track_b, int track_c) {
    if (track_a <= 0 || track_b <= 0 || track_c <= 0) return false;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* sql = "INSERT INTO markov_transitions_2nd (track_a_id, track_b_id, track_c_id, transition_count) "
                      "VALUES (?, ?, ?, 1) "
                      "ON CONFLICT(track_a_id, track_b_id, track_c_id) DO UPDATE SET transition_count = markov_transitions_2nd.transition_count + 1;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, track_a);
    sqlite3_bind_int(stmt, 2, track_b);
    sqlite3_bind_int(stmt, 3, track_c);

    bool ok = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return ok;
}

float StreamifyDB::get2ndOrderMarkovProbability(int track_a, int track_b, int track_c, float alpha) {
    if (track_a <= 0 || track_b <= 0 || track_c <= 0) return 0.0f;
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return 0.0f;

    // 1. Get count(A, B, C)
    int countABC = 0;
    const char* abc_sql = "SELECT transition_count FROM markov_transitions_2nd WHERE track_a_id = ? AND track_b_id = ? AND track_c_id = ?;";
    sqlite3_stmt* stmt1 = nullptr;
    if (sqlite3_prepare_v2(db, abc_sql, -1, &stmt1, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt1, 1, track_a);
        sqlite3_bind_int(stmt1, 2, track_b);
        sqlite3_bind_int(stmt1, 3, track_c);
        if (sqlite3_step(stmt1) == SQLITE_ROW) {
            countABC = sqlite3_column_int(stmt1, 0);
        }
        sqlite3_finalize(stmt1);
    }

    // 2. Get total count(A, B, *)
    int countAB_total = 0;
    const char* ab_sql = "SELECT SUM(transition_count) FROM markov_transitions_2nd WHERE track_a_id = ? AND track_b_id = ?;";
    sqlite3_stmt* stmt2 = nullptr;
    if (sqlite3_prepare_v2(db, ab_sql, -1, &stmt2, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(stmt2, 1, track_a);
        sqlite3_bind_int(stmt2, 2, track_b);
        if (sqlite3_step(stmt2) == SQLITE_ROW) {
            countAB_total = sqlite3_column_int(stmt2, 0);
        }
        sqlite3_finalize(stmt2);
    }

    int vocabSize = getTotalUniqueTracks();

    // Laplace Smoothing: P(C | A, B) = (countABC + alpha) / (countAB_total + alpha * |V|)
    float numerator = static_cast<float>(countABC) + alpha;
    float denominator = static_cast<float>(countAB_total) + (alpha * static_cast<float>(vocabSize));

    if (denominator <= 0.0f) return 0.0f;
    return numerator / denominator;
}

// ═══════════════════════════════════════════════════════════════
// HYBRID ASYMMETRIC RECOMMENDATION ENGINE (K-MEANS & LAST.FM)
// ═══════════════════════════════════════════════════════════════

bool StreamifyDB::saveClusterCentroid(int cluster_id, const std::vector<float>& centroid, int track_count) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || centroid.empty()) return false;

    const char* sql = "INSERT INTO vector_clusters (cluster_id, centroid, track_count) VALUES (?, ?, ?) "
                      "ON CONFLICT(cluster_id) DO UPDATE SET centroid=excluded.centroid, track_count=excluded.track_count;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, cluster_id);
    sqlite3_bind_blob(stmt, 2, centroid.data(), centroid.size() * sizeof(float), SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 3, track_count);

    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return success;
}

std::vector<std::pair<int, std::vector<float>>> StreamifyDB::getClusterCentroids() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    std::vector<std::pair<int, std::vector<float>>> result;
    sqlite3* db = getConnection();
    if (!db) return result;

    const char* sql = "SELECT cluster_id, centroid FROM vector_clusters ORDER BY cluster_id ASC;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return result;

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        int cluster_id = sqlite3_column_int(stmt, 0);
        const void* blob_data = sqlite3_column_blob(stmt, 1);
        int blob_bytes = sqlite3_column_bytes(stmt, 1);
        int float_count = blob_bytes / sizeof(float);

        if (blob_data && float_count == 512) {
            const float* float_ptr = static_cast<const float*>(blob_data);
            std::vector<float> vec(float_ptr, float_ptr + float_count);
            result.push_back({cluster_id, std::move(vec)});
        }
    }
    sqlite3_finalize(stmt);
    return result;
}

bool StreamifyDB::assignTrackToCluster(int track_id, int cluster_id, float distance_to_centroid) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    const char* sql = "INSERT INTO track_clusters (track_id, cluster_id, distance_to_centroid) VALUES (?, ?, ?) "
                      "ON CONFLICT(track_id, cluster_id) DO UPDATE SET distance_to_centroid=excluded.distance_to_centroid;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_int(stmt, 1, track_id);
    sqlite3_bind_int(stmt, 2, cluster_id);
    sqlite3_bind_double(stmt, 3, static_cast<double>(distance_to_centroid));

    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return success;
}

std::vector<int> StreamifyDB::getTracksInClusters(const std::vector<int>& cluster_ids, int limit) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    std::vector<int> result;
    if (cluster_ids.empty()) return result;

    sqlite3* db = getConnection();
    if (!db) return result;

    std::string sql = "SELECT DISTINCT track_id FROM track_clusters WHERE cluster_id IN (";
    for (size_t i = 0; i < cluster_ids.size(); ++i) {
        sql += (i == 0) ? "?" : ", ?";
    }
    sql += ") ORDER BY distance_to_centroid ASC LIMIT ?;";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql.c_str(), -1, &stmt, nullptr) != SQLITE_OK) return result;

    int bind_idx = 1;
    for (int cid : cluster_ids) {
        sqlite3_bind_int(stmt, bind_idx++, cid);
    }
    sqlite3_bind_int(stmt, bind_idx, limit);

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        result.push_back(sqlite3_column_int(stmt, 0));
    }
    sqlite3_finalize(stmt);
    return result;
}

bool StreamifyDB::updateTrackEmbedding(int track_id, const float* embedding_512) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db || !embedding_512) return false;

    const char* sql = "UPDATE tracks SET embedding = ? WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return false;

    sqlite3_bind_blob(stmt, 1, embedding_512, 512 * sizeof(float), SQLITE_TRANSIENT);
    sqlite3_bind_int(stmt, 2, track_id);

    bool success = (sqlite3_step(stmt) == SQLITE_DONE);
    sqlite3_finalize(stmt);
    return success;
}

std::vector<float> StreamifyDB::getTrackEmbedding(int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    std::vector<float> result;
    sqlite3* db = getConnection();
    if (!db) return result;

    const char* sql = "SELECT embedding FROM tracks WHERE id = ?;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return result;

    sqlite3_bind_int(stmt, 1, track_id);
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        const void* blob_data = sqlite3_column_blob(stmt, 0);
        int blob_bytes = sqlite3_column_bytes(stmt, 0);
        int float_count = blob_bytes / sizeof(float);
        if (blob_data && float_count == 512) {
            const float* float_ptr = static_cast<const float*>(blob_data);
            result.assign(float_ptr, float_ptr + 512);
        }
    }
    sqlite3_finalize(stmt);
    return result;
}

bool StreamifyDB::cacheSimilarTracks(int track_id, const std::vector<std::string>& titles, const std::vector<std::string>& artists, const std::vector<std::string>& mbids, const std::vector<float>& weights) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    sqlite3_exec(db, "BEGIN TRANSACTION;", nullptr, nullptr, nullptr);

    const char* sql = "INSERT INTO similar_tracks (track_id, similar_track_mbid, similar_title, similar_artist, lastfm_weight, is_resolved, cached_at) "
                      "VALUES (?, ?, ?, ?, ?, 0, strftime('%s', 'now')) "
                      "ON CONFLICT(track_id, similar_title, similar_artist) DO UPDATE SET lastfm_weight=excluded.lastfm_weight, cached_at=excluded.cached_at;";

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) == SQLITE_OK) {
        for (size_t i = 0; i < titles.size(); ++i) {
            sqlite3_bind_int(stmt, 1, track_id);
            sqlite3_bind_text(stmt, 2, i < mbids.size() ? mbids[i].c_str() : "", -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, 3, titles[i].c_str(), -1, SQLITE_TRANSIENT);
            sqlite3_bind_text(stmt, 4, i < artists.size() ? artists[i].c_str() : "", -1, SQLITE_TRANSIENT);
            sqlite3_bind_double(stmt, 5, i < weights.size() ? static_cast<double>(weights[i]) : 0.0);

            sqlite3_step(stmt);
            sqlite3_reset(stmt);
        }
        sqlite3_finalize(stmt);
    }

    sqlite3_exec(db, "COMMIT;", nullptr, nullptr, nullptr);
    return true;
}

std::vector<std::pair<std::string, float>> StreamifyDB::getCachedSimilarTracks(int track_id) {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    std::vector<std::pair<std::string, float>> result;
    sqlite3* db = getConnection();
    if (!db) return result;

    const char* sql = "SELECT similar_title, similar_artist, lastfm_weight FROM similar_tracks WHERE track_id = ? ORDER BY lastfm_weight DESC LIMIT 50;";
    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, sql, -1, &stmt, nullptr) != SQLITE_OK) return result;

    sqlite3_bind_int(stmt, 1, track_id);
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        const char* title = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 0));
        const char* artist = reinterpret_cast<const char*>(sqlite3_column_text(stmt, 1));
        float weight = static_cast<float>(sqlite3_column_double(stmt, 2));

        std::string key = (title ? std::string(title) : "") + "::" + (artist ? std::string(artist) : "");
        result.push_back({key, weight});
    }
    sqlite3_finalize(stmt);
    return result;
}

bool StreamifyDB::nukeDatabase() {
    std::lock_guard<std::recursive_mutex> lock(db_mutex_);
    sqlite3* db = getConnection();
    if (!db) return false;

    finalizeStatements();

    char* errMsg = nullptr;
    const char* nuke_sql = R"(
        PRAGMA foreign_keys = OFF;
        DELETE FROM user_liked_songs;
        DELETE FROM tracks;
        DELETE FROM user_transitions;
        DELETE FROM user_circadian_patterns;
        DELETE FROM user_engagement_log;
        DELETE FROM track_hook_telemetry;
        DELETE FROM track_cooccurrence;
        DELETE FROM markov_transitions;
        DELETE FROM markov_transitions_2nd;
        DELETE FROM vector_clusters;
        DELETE FROM track_clusters;
        DELETE FROM similar_tracks;
        DELETE FROM user_sessions;
        DELETE FROM users;
        VACUUM;
        PRAGMA foreign_keys = ON;
    )";

    int rc = sqlite3_exec(db, nuke_sql, nullptr, nullptr, &errMsg);
    if (rc != SQLITE_OK) {
        if (errMsg) {
            __android_log_print(ANDROID_LOG_ERROR, "StreamifyDB", "Nuke error: %s", errMsg);
            sqlite3_free(errMsg);
        }
        return false;
    }
    return true;
}
