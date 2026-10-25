#include <jni.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <ctime>
#include <cctype>
#include <cstdio>
#include "../engine/StreamifyDB.h"
#include "../engine/TaskOrchestrator.h"
#include "../engine/TelemetryEngine.h"
#include "../engine/ChronosProfiler.h"
#include "../engine/PtpEngine.h"
#include "../engine/AirDropPhysicsEngine.h"
#include "../dsp/LufsNormalizer.h"
#include "../dsp/SoftKneeLimiter.h"
#include "../dsp/LyricAligner.h"

// Cached Global JNI References for zero lookup overhead
static jclass g_trackClass = nullptr;
static jmethodID g_trackConstructor = nullptr;
static jclass g_recClass = nullptr;
static jmethodID g_recConstructor = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localTrackClass = env->FindClass("com/streamify/app/data/models/TrackNative");
    if (localTrackClass) {
        g_trackClass = reinterpret_cast<jclass>(env->NewGlobalRef(localTrackClass));
        g_trackConstructor = env->GetMethodID(g_trackClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IFLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
        env->DeleteLocalRef(localTrackClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jclass localRecClass = env->FindClass("com/streamify/app/data/models/RecommendationNative");
    if (localRecClass) {
        g_recClass = reinterpret_cast<jclass>(env->NewGlobalRef(localRecClass));
        g_recConstructor = env->GetMethodID(g_recClass, "<init>", "(IFFF)V");
        if (!g_recConstructor) {
            env->ExceptionClear();
            g_recConstructor = env->GetMethodID(g_recClass, "<init>", "(IF)V");
        }
        env->DeleteLocalRef(localRecClass);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (g_trackClass) env->DeleteGlobalRef(g_trackClass);
        if (g_recClass) env->DeleteGlobalRef(g_recClass);
    }
}

// Helper to convert C++ Track to Java TrackNative using pre-cached descriptors
jobject convertTrack(JNIEnv* env, jclass trackClass, jmethodID constructor, const StreamifyTrack& t) {
    jstring filepath = env->NewStringUTF(t.filepath.c_str());
    jstring title = env->NewStringUTF(t.title.c_str());
    jstring artist = env->NewStringUTF(t.artist.c_str());
    jstring album = env->NewStringUTF(t.album.c_str());
    jstring key = env->NewStringUTF(t.key.c_str());
    jstring coverArtPath = env->NewStringUTF(t.cover_art_path.c_str());
    jstring lyricsPath = env->NewStringUTF(t.lyrics_path.c_str());
    jstring source = env->NewStringUTF(t.source.c_str());
    jstring downloadQuality = env->NewStringUTF(t.download_quality.c_str());

    jobject trackObj = env->NewObject(trackClass, constructor,
        t.id, filepath, title, artist, album,
        t.duration_sec, static_cast<jfloat>(t.bpm), key, t.vector_offset,
        coverArtPath, lyricsPath, source, t.is_processed, downloadQuality);

    env->DeleteLocalRef(filepath);
    env->DeleteLocalRef(title);
    env->DeleteLocalRef(artist);
    env->DeleteLocalRef(album);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(coverArtPath);
    env->DeleteLocalRef(lyricsPath);
    env->DeleteLocalRef(source);
    env->DeleteLocalRef(downloadQuality);

    return trackObj;
}

jobjectArray convertTrackList(JNIEnv* env, const std::vector<StreamifyTrack>& tracks) {
    jclass trackClass = g_trackClass ? g_trackClass : env->FindClass("com/streamify/app/data/models/TrackNative");
    jmethodID constructor = g_trackConstructor ? g_trackConstructor : env->GetMethodID(trackClass, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IFLjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
    
    jobjectArray trackArray = env->NewObjectArray(tracks.size(), trackClass, nullptr);
    for (size_t i = 0; i < tracks.size(); ++i) {
        jobject trackObj = convertTrack(env, trackClass, constructor, tracks[i]);
        env->SetObjectArrayElement(trackArray, i, trackObj);
        env->DeleteLocalRef(trackObj);
    }
    return trackArray;
}

#include "../engine/RecommendEngine.h"
#include "../engine/VectorStore.h"

jobjectArray convertRecList(JNIEnv* env, const std::vector<Recommendation>& recs) {
    jclass recClass = g_recClass ? g_recClass : env->FindClass("com/streamify/app/data/models/RecommendationNative");
    jmethodID constructor = g_recConstructor;
    if (!constructor && recClass) {
        constructor = env->GetMethodID(recClass, "<init>", "(IFFF)V");
        if (!constructor) {
            env->ExceptionClear();
            constructor = env->GetMethodID(recClass, "<init>", "(IF)V");
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    jobjectArray resultArray = env->NewObjectArray(recs.size(), recClass, nullptr);
    for (size_t i = 0; i < recs.size(); ++i) {
        jobject obj = nullptr;
        if (constructor) {
            obj = env->NewObject(recClass, constructor, recs[i].trackId, recs[i].score, recs[i].vectorScore, recs[i].bpmMatchScore);
        }
        if (obj) {
            env->SetObjectArrayElement(resultArray, i, obj);
            env->DeleteLocalRef(obj);
        }
    }
    return resultArray;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_stringFromJNI(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF("Streamify C++ Core Initialized");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initDatabase(JNIEnv* env, jobject /* this */, jstring dbPath) {
    const char* path = env->GetStringUTFChars(dbPath, 0);
    bool success = StreamifyDB::getInstance().init(path);
    env->ReleaseStringUTFChars(dbPath, path);
    return success;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getAllTracks(JNIEnv* env, jobject /* this */) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getAllTracks();
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_searchTracks(JNIEnv* env, jobject /* this */, jstring query) {
    const char* q = env->GetStringUTFChars(query, 0);
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().searchTracks(q);
    env->ReleaseStringUTFChars(query, q);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_insertTrack(JNIEnv* env, jobject /* this */,
    jstring filepath, jstring title, jstring artist, jstring album, jint durationSec, jfloat bpm) {
    const char* cFilepath = env->GetStringUTFChars(filepath, 0);
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);

    int id = StreamifyDB::getInstance().insertTrack(cFilepath, cTitle, cArtist, cAlbum, durationSec, static_cast<double>(bpm));

    env->ReleaseStringUTFChars(filepath, cFilepath);
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_toggleLike(JNIEnv* env, jobject /* this */, jint userId, jint trackId) {
    bool isLiked = false;
    StreamifyDB::getInstance().toggleUserLikedTrack(userId, trackId, isLiked);
    return isLiked;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getLikedTracks(JNIEnv* env, jobject /* this */, jint userId) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getUserLikedTracks(userId);
    return convertTrackList(env, tracks);
}

#include "../engine/VectorStore.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initVectorStore(JNIEnv* env, jobject /* this */, jstring binPath) {
    const char* path = env->GetStringUTFChars(binPath, 0);
    bool success = VectorStore::getInstance().init(path);
    env->ReleaseStringUTFChars(binPath, path);
    return success;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_streamify_app_data_NativeBridge_searchSimilarTracks(JNIEnv* env, jobject /* this */, jint trackId, jint topK) {
    std::optional<StreamifyTrack> track = StreamifyDB::getInstance().getTrackById(trackId);
    if (!track.has_value() || track->vector_offset < 0) {
        return env->NewIntArray(0);
    }

    std::vector<SearchResult> results = VectorStore::getInstance().searchNearest(track->vector_offset, topK);
    
    // We only need the track IDs which correspond to the vector offsets (assuming 1:1 mapping in simple case, or we query DB).
    // In legacy, vector_offset == track_id - 1 usually, but here we can just query the DB for tracks with these vector_offsets.
    // For simplicity, let's return the vector_offsets as track IDs directly (assuming vector_offset = trackId).
    // Actually, StreamifyDB stores vector_offset. We should return an array of offsets.
    jintArray resultArray = env->NewIntArray(results.size());
    if (results.size() > 0) {
        jint* elements = env->GetIntArrayElements(resultArray, 0);
        for (size_t i = 0; i < results.size(); ++i) {
            elements[i] = results[i].vector_offset; 
        }
        env->ReleaseIntArrayElements(resultArray, elements, 0);
    }
    return resultArray;
}

#include "../engine/RecommendEngine.h"
#include "../engine/EventTracker.h"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getRecommendations(JNIEnv* env, jobject /* this */, jint trackId, jintArray recentHistory, jint userId, jint limit) {
    jsize len = env->GetArrayLength(recentHistory);
    jint* historyElements = env->GetIntArrayElements(recentHistory, 0);
    std::vector<int> history(historyElements, historyElements + len);
    env->ReleaseIntArrayElements(recentHistory, historyElements, 0);

    std::vector<Recommendation> recs = RecommendEngine::getInstance().getNextTracks(trackId, history, limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_logPlayEvent(JNIEnv* env, jobject /* this */, jint fromTrackId, jint toTrackId, jint userId) {
    EventTracker::getInstance().logPlay(fromTrackId, toTrackId, userId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_logSkipEvent(JNIEnv* env, jobject /* this */, jint fromTrackId, jint toTrackId, jint userId) {
    EventTracker::getInstance().logSkip(fromTrackId, toTrackId, userId);
}

#include "../ingest/AudioPipeline.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_initAudioPipeline(JNIEnv* env, jobject /* this */, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, 0);
    bool success = AudioPipeline::getInstance().init(path);
    env->ReleaseStringUTFChars(modelPath, path);
    return success;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_processAudioFile(JNIEnv* env, jobject /* this */, jint trackId, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, 0);
    std::string trackPathStr(path);
    TaskOrchestrator::getInstance().notifyAiTaskStarted(trackPathStr);
    
    std::vector<float> vec = AudioPipeline::getInstance().processAudio(path);
    
    if (!vec.empty() && trackId > 0) {
        float bpm = AudioPipeline::getInstance().extractBPM(path);
        std::string key = AudioPipeline::getInstance().extractKey(path);
        StreamifyDB::getInstance().updateTrackBPM(trackId, bpm);
        StreamifyDB::getInstance().updateTrackKey(trackId, key);
    }
    
    env->ReleaseStringUTFChars(filePath, path);
    TaskOrchestrator::getInstance().notifyAiTaskCompleted();

    if (vec.empty()) return -1;

    int offset = VectorStore::getInstance().addVector(vec);
    if (offset >= 0 && trackId > 0) {
        StreamifyDB::getInstance().updateTrackVectorOffset(trackId, offset);
    }
    return offset;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_extractBPM(JNIEnv* env, jobject /* this */, jint trackId, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, 0);
    float bpm = AudioPipeline::getInstance().extractBPM(path);
    if (trackId > 0) {
        StreamifyDB::getInstance().updateTrackBPM(trackId, bpm);
    }
    env->ReleaseStringUTFChars(filePath, path);
    return bpm;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackCoverArt(JNIEnv* env, jobject /* this */, jint trackId, jstring coverArtPath) {
    const char* path = env->GetStringUTFChars(coverArtPath, 0);
    bool res = StreamifyDB::getInstance().updateTrackCoverArt(trackId, path);
    env->ReleaseStringUTFChars(coverArtPath, path);
    return res;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackMetadata(JNIEnv* env, jobject /* this */, jint trackId, jstring title, jstring artist, jstring album) {
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);
    
    bool res = StreamifyDB::getInstance().updateTrackMetadata(trackId, cTitle, cArtist, cAlbum);
    
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setHighPriorityActive(JNIEnv* /* env */, jobject /* this */, jboolean active) {
    TaskOrchestrator::getInstance().setHighPriorityActive(active);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setTotalAiTasks(JNIEnv* /* env */, jobject /* this */, jint total) {
    TaskOrchestrator::getInstance().setTotalAiTasks(total);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_streamify_app_data_NativeBridge_getOrchestratorStatus(JNIEnv* env, jobject /* this */) {
    OrchestratorMetrics m = TaskOrchestrator::getInstance().getMetrics();
    
    jclass statusClass = env->FindClass("com/streamify/app/data/models/OrchestratorStatusNative");
    if (!statusClass) return nullptr;
    
    jmethodID constructor = env->GetMethodID(statusClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;IIIIIZIZZ)V");
    if (!constructor) return nullptr;
    
    jstring stateStr = env->NewStringUTF(m.state.c_str());
    jstring actionStr = env->NewStringUTF(m.currentAction.c_str());
    
    jobject obj = env->NewObject(statusClass, constructor,
        stateStr, actionStr, m.activeAiTasks, m.completedAiTasks, m.totalAiTasks,
        m.cpuCoreBudget, m.activeThreads, m.isThrottled,
        m.cpuTemp, m.isThermallyThrottled, m.isBatterySaver);
        
    env->DeleteLocalRef(stateStr);
    env->DeleteLocalRef(actionStr);
    return obj;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_setBatterySaverActive(JNIEnv* /* env */, jobject /* this */, jboolean active) {
    TaskOrchestrator::getInstance().setBatterySaverActive(active);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_upsertStreamedTrack(JNIEnv* env, jobject /* this */,
    jstring filepath, jstring title, jstring artist, jstring album, jint durationSec,
    jstring coverArtPath, jstring lyricsPath, jfloat bpm, jstring key) {
    
    const char* cFilepath = env->GetStringUTFChars(filepath, 0);
    const char* cTitle = env->GetStringUTFChars(title, 0);
    const char* cArtist = env->GetStringUTFChars(artist, 0);
    const char* cAlbum = env->GetStringUTFChars(album, 0);
    const char* cCover = env->GetStringUTFChars(coverArtPath, 0);
    const char* cLyrics = env->GetStringUTFChars(lyricsPath, 0);
    const char* cKey = env->GetStringUTFChars(key, 0);

    int id = StreamifyDB::getInstance().upsertStreamedTrack(
        cFilepath, cTitle, cArtist, cAlbum, durationSec, cCover, cLyrics, static_cast<double>(bpm), cKey
    );

    env->ReleaseStringUTFChars(filepath, cFilepath);
    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    env->ReleaseStringUTFChars(album, cAlbum);
    env->ReleaseStringUTFChars(coverArtPath, cCover);
    env->ReleaseStringUTFChars(lyricsPath, cLyrics);
    env->ReleaseStringUTFChars(key, cKey);

    return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_recordTrackPlay(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return StreamifyDB::getInstance().recordTrackPlay(trackId);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTopPlayedTracks(JNIEnv* env, jobject /* this */, jint limit) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getTopPlayedTracks(limit);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_updateSessionVector(JNIEnv* /* env */, jobject /* this */, jint trackId, jfloat alpha) {
    RecommendEngine::getInstance().updateSessionVector(trackId, alpha);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getSessionRecommendations(JNIEnv* env, jobject /* this */, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getSessionRecommendations(limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getLongTermRecommendations(JNIEnv* env, jobject /* this */, jint userId, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getLongTermRecommendations(userId, limit);
    return convertRecList(env, recs);
}

#include "../dsp/SoftKneeLimiter.h"

static streamify::dsp::SoftKneeLimiter g_limiter(0.90f, 0.15f);

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLimiterShorts(JNIEnv* env, jobject /* this */, jshortArray buffer, jint length, jfloat threshold, jfloat kneeWidth) {
    if (!buffer || length <= 0) return;
    // Never trust the declared length over the real JVM array bounds.
    const jint actual = env->GetArrayLength(buffer);
    const jint safeLen = std::min< jint>(length, actual);
    if (safeLen <= 0) return;
    g_limiter.setParameters(threshold, kneeWidth);
    jshort* pcm = env->GetShortArrayElements(buffer, nullptr);
    if (pcm) {
        g_limiter.processShorts(pcm, safeLen);
        env->ReleaseShortArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLimiterFloats(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jfloat threshold, jfloat kneeWidth) {
    if (!buffer || length <= 0) return;
    const jint actual = env->GetArrayLength(buffer);
    const jint safeLen = std::min<jint>(length, actual);
    if (safeLen <= 0) return;
    g_limiter.setParameters(threshold, kneeWidth);
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (pcm) {
        g_limiter.processFloats(pcm, safeLen);
        env->ReleaseFloatArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_findFuzzyTrackMatch(JNIEnv* env, jobject /* this */, jstring title, jstring artist) {
    if (!title || !artist) return -1;
    const char* cTitle = env->GetStringUTFChars(title, nullptr);
    const char* cArtist = env->GetStringUTFChars(artist, nullptr);
    if (!cTitle || !cArtist) {
        if (cTitle) env->ReleaseStringUTFChars(title, cTitle);
        if (cArtist) env->ReleaseStringUTFChars(artist, cArtist);
        return -1;
    }

    int id = StreamifyDB::getInstance().findFuzzyTrackMatch(cTitle, cArtist);

    env->ReleaseStringUTFChars(title, cTitle);
    env->ReleaseStringUTFChars(artist, cArtist);
    return id;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTracksBatch(JNIEnv* env, jobject /* this */, jint offset, jint limit) {
    std::vector<StreamifyTrack> tracks = StreamifyDB::getInstance().getTracksBatch(offset, limit);
    return convertTrackList(env, tracks);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_logEngagementEvent(JNIEnv* /* env */, jobject /* this */, jint trackId, jint durationSec, jfloat completionRatio, jint hourOfDay) {
    return StreamifyDB::getInstance().logEngagement(trackId, durationSec, completionRatio, hourOfDay);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getCircadianRecommendations(JNIEnv* env, jobject /* this */, jint hourOfDay, jint limit) {
    std::vector<Recommendation> recs = RecommendEngine::getInstance().getCircadianRecommendations(hourOfDay, limit);
    return convertRecList(env, recs);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_getCircadianSlot(JNIEnv* env, jobject /* this */, jint hourOfDay) {
    std::string slot = StreamifyDB::getInstance().getCircadianSlot(hourOfDay);
    return env->NewStringUTF(slot.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_logHookTelemetry(JNIEnv* /* env */, jobject /* this */, jint trackId, jlong favoriteSeekMs, jint lyricsDwellSec, jint volumeFlare) {
    return StreamifyDB::getInstance().logHookTelemetry(trackId, favoriteSeekMs, lyricsDwellSec, volumeFlare);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_recordTrackCooccurrence(JNIEnv* /* env */, jobject /* this */, jint trackAId, jint trackBId) {
    return StreamifyDB::getInstance().recordTrackCooccurrence(trackAId, trackBId);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getFavoriteSeekMs(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return StreamifyDB::getInstance().getFavoriteSeekMs(trackId);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_streamify_app_data_NativeBridge_getCooccurrenceRecommendations(JNIEnv* env, jobject /* this */, jint trackId, jint limit) {
    std::vector<int> candidates = StreamifyDB::getInstance().getCooccurrenceCandidates(trackId, limit);
    jintArray result = env->NewIntArray(candidates.size());
    if (result && !candidates.empty()) {
        env->SetIntArrayRegion(result, 0, candidates.size(), candidates.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_pushTelemetryEvent(JNIEnv* /* env */, jobject /* this */, jint type, jlong trackId, jfloat value) {
    TelemetryEngine::getInstance().pushEvent(static_cast<TelemetryEventType>(type), trackId, value);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getMarkovProbability(JNIEnv* /* env */, jobject /* this */, jint fromTrackId, jint toTrackId) {
    return StreamifyDB::getInstance().getMarkovProbability(fromTrackId, toTrackId);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getSatiationPenalty(JNIEnv* /* env */, jobject /* this */, jint trackId) {
    return ChronosProfiler::getInstance().calculateSatiationPenalty(trackId, std::time(nullptr) * 1000LL);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_get2ndOrderMarkovProbability(JNIEnv* /* env */, jobject /* this */, jint trackA, jint trackB, jint trackC, jfloat alpha) {
    return StreamifyDB::getInstance().get2ndOrderMarkovProbability(trackA, trackB, trackC, alpha);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLufsNormalizerFloats(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jfloat targetLufs) {
    if (!buffer || length <= 0) return;
    const jint safeLen = std::min<jint>(length, env->GetArrayLength(buffer));
    if (safeLen <= 0) return;
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (pcm) {
        LufsNormalizer::getInstance().processFloats(pcm, safeLen, targetLufs);
        env->ReleaseFloatArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_processLufsNormalizerShorts(JNIEnv* env, jobject /* this */, jshortArray buffer, jint length, jfloat targetLufs) {
    if (!buffer || length <= 0) return;
    const jint safeLen = std::min<jint>(length, env->GetArrayLength(buffer));
    if (safeLen <= 0) return;
    jshort* pcm = env->GetShortArrayElements(buffer, nullptr);
    if (pcm) {
        LufsNormalizer::getInstance().processShorts(pcm, safeLen, targetLufs);
        env->ReleaseShortArrayElements(buffer, pcm, 0);
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getDynamicTargetLufs(JNIEnv* /* env */, jobject /* this */) {
    return TelemetryEngine::getInstance().getDynamicTargetLufs();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_generateProofOfCompute(JNIEnv* env, jobject /* this */, jfloatArray buffer, jint length, jstring nonce) {
    if (!buffer || length <= 0) return env->NewStringUTF("");
    const jint safeLen = std::min<jint>(length, env->GetArrayLength(buffer));
    if (safeLen <= 0) return env->NewStringUTF("");
    jfloat* pcm = env->GetFloatArrayElements(buffer, nullptr);
    if (!pcm) return env->NewStringUTF("");

    const char* nonceStr = env->GetStringUTFChars(nonce, nullptr);
    std::string nonceCpp(nonceStr ? nonceStr : "");
    if (nonceStr) env->ReleaseStringUTFChars(nonce, nonceStr);

    std::string proof = TelemetryEngine::getInstance().generateProofOfCompute(pcm, safeLen, nonceCpp);
    env->ReleaseFloatArrayElements(buffer, pcm, 0);
    return env->NewStringUTF(proof.c_str());
}

// ═══════════════════════════════════════════════════════════════
// HYBRID ASYMMETRIC RECOMMENDATION ENGINE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_getTargetBpmForTimeSlot(JNIEnv* /* env */, jobject /* this */, jint slotOrdinal) {
    return RecommendEngine::getInstance().getTargetBpmForTimeSlot(slotOrdinal);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_streamify_app_data_NativeBridge_getVectorRecommendations(
    JNIEnv* env,
    jobject /* this */,
    jint currentTrackId,
    jfloat timeWeight,
    jfloat deviceWeight,
    jfloat bpmTarget,
    jint limit
) {
    auto& db = StreamifyDB::getInstance();
    auto& recEngine = RecommendEngine::getInstance();
    auto& vecStore = VectorStore::getInstance();

    std::vector<float> baseVec = db.getTrackEmbedding(currentTrackId);
    if (baseVec.empty()) {
        auto optTrack = db.getTrackById(currentTrackId);
        if (optTrack && optTrack->vector_offset >= 0) {
            baseVec = vecStore.getVectorAt(optTrack->vector_offset);
        }
    }

    if (baseVec.empty()) {
        auto all = db.getAllTracks();
        std::vector<Recommendation> fallback;
        for (const auto& t : all) {
            if (t.id != currentTrackId) {
                Recommendation r;
                r.trackId = t.id;
                r.score = 1.0f;
                r.vectorScore = 0.5f;
                r.bpmMatchScore = 0.5f;
                fallback.push_back(r);
                if (fallback.size() >= static_cast<size_t>(limit)) break;
            }
        }
        return convertRecList(env, fallback);
    }

    // 1. Compute Contextual Vector
    std::vector<float> ctxVec = recEngine.computeContextualVector(baseVec, timeWeight, deviceWeight);

    // 2. K-Means: Find 2 closest clusters
    std::vector<int> topClusters = recEngine.findClosestClusters(ctxVec, 2);

    // 3. Get candidate tracks in those clusters
    std::vector<int> candidates = db.getTracksInClusters(topClusters, 100);
    if (candidates.size() < 10) {
        auto all = db.getAllTracks();
        for (const auto& t : all) {
            if (t.id != currentTrackId) candidates.push_back(t.id);
        }
    }

    // 4. NEON SIMD Ranking + Ellis-Gaussian BPM + Satiation
    auto scoredRecs = recEngine.rankHybridCandidates(ctxVec, candidates, bpmTarget, 0.20f, limit);
    return convertRecList(env, scoredRecs);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_updateTrackEmbedding(JNIEnv* env, jobject /* this */, jint trackId, jfloatArray embedding) {
    if (!embedding) return JNI_FALSE;
    jsize len = env->GetArrayLength(embedding);
    if (len < 512) return JNI_FALSE;

    jfloat* ptr = env->GetFloatArrayElements(embedding, nullptr);
    if (!ptr) return JNI_FALSE;

    bool ok = StreamifyDB::getInstance().updateTrackEmbedding(trackId, ptr);
    env->ReleaseFloatArrayElements(embedding, ptr, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_streamify_app_data_NativeBridge_getTrackEmbedding(JNIEnv* env, jobject /* this */, jint trackId) {
    std::vector<float> vec = StreamifyDB::getInstance().getTrackEmbedding(trackId);
    if (vec.size() < 512) return nullptr;

    jfloatArray result = env->NewFloatArray(512);
    env->SetFloatArrayRegion(result, 0, 512, vec.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_cacheSimilarTracks(
    JNIEnv* env,
    jobject /* this */,
    jint trackId,
    jobjectArray titles,
    jobjectArray artists,
    jobjectArray mbids,
    jfloatArray weights
) {
    if (!titles || !artists || !weights) return JNI_FALSE;
    jsize count = env->GetArrayLength(titles);
    if (count <= 0) return JNI_FALSE;

    std::vector<std::string> titleVec(count);
    std::vector<std::string> artistVec(count);
    std::vector<std::string> mbidVec(count);
    std::vector<float> weightVec(count);

    jfloat* wPtr = env->GetFloatArrayElements(weights, nullptr);
    for (jsize i = 0; i < count; ++i) {
        jstring tStr = static_cast<jstring>(env->GetObjectArrayElement(titles, i));
        jstring aStr = static_cast<jstring>(env->GetObjectArrayElement(artists, i));
        jstring mStr = mbids ? static_cast<jstring>(env->GetObjectArrayElement(mbids, i)) : nullptr;

        const char* tChars = tStr ? env->GetStringUTFChars(tStr, nullptr) : "";
        const char* aChars = aStr ? env->GetStringUTFChars(aStr, nullptr) : "";
        const char* mChars = mStr ? env->GetStringUTFChars(mStr, nullptr) : "";

        titleVec[i] = tChars ? tChars : "";
        artistVec[i] = aChars ? aChars : "";
        mbidVec[i] = mChars ? mChars : "";
        weightVec[i] = wPtr ? wPtr[i] : 0.0f;

        if (tStr && tChars) env->ReleaseStringUTFChars(tStr, tChars);
        if (aStr && aChars) env->ReleaseStringUTFChars(aStr, aChars);
        if (mStr && mChars) env->ReleaseStringUTFChars(mStr, mChars);

        if (tStr) env->DeleteLocalRef(tStr);
        if (aStr) env->DeleteLocalRef(aStr);
        if (mStr) env->DeleteLocalRef(mStr);
    }
    if (wPtr) env->ReleaseFloatArrayElements(weights, wPtr, 0);

    bool ok = StreamifyDB::getInstance().cacheSimilarTracks(trackId, titleVec, artistVec, mbidVec, weightVec);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Project Pulse: Sub-15ms Precision Time Protocol (IEEE 1588)
extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_processPtpTimestamps(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong t0,
    jlong t1,
    jlong t2,
    jlong t3
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().processTimestamps(t0, t1, t2, t3));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getSynchronizedClockMs(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getSynchronizedClockMs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getPtpClockOffsetNanos(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getClockOffsetNanos());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_getPtpRttNanos(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return static_cast<jlong>(streamify::PtpEngine::getInstance().getLastRttNanos());
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_resetPtpState(
    JNIEnv* /* env */,
    jobject /* this */
) {
    streamify::PtpEngine::getInstance().reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_getZhipuKey(
    JNIEnv* env,
    jobject /* this */,
    jint index
) {
    static const char* ZHIPU_KEYS[] = {
        "57bd4b727f404046b17204dc95a657e8.IMJI6yrCLcZDBl1y",
        "0758ad943a784d728f17cd5d98b5330d.sn0EnMWZ1kLCleir",
        "29a71f29e0bf45cbb0e61ae1fcdb0127.BbgwmouOPz7JQWyp",
        "2f444b74e35c4d7ebae62471309b8b9e.5OzYzb9uP9v0uNzz",
        "85aa0d0ac2f845579dfc58ae355d855d.yQrUOUVlGG0Xe2q2"
    };
    int safeIdx = (index >= 0 && index < 5) ? index : (static_cast<unsigned int>(index) % 5u);
    return env->NewStringUTF(ZHIPU_KEYS[safeIdx]);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_nukeLocalDatabase(JNIEnv* env, jobject /* this */) {
    bool success = StreamifyDB::getInstance().nukeDatabase();
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_stepAirDropPhysics(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray inOutBuffer,
    jfloat targetX,
    jfloat targetY,
    jfloat initialDist,
    jfloat dt
) {
    if (!inOutBuffer) return;
    jsize len = env->GetArrayLength(inOutBuffer);
    if (len < 13) return;

    jfloat* buf = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(inOutBuffer, nullptr));
    if (!buf) return;

    streamify::AirDropState state;
    state.x = buf[0];
    state.y = buf[1];
    state.z = buf[2];
    state.vx = buf[3];
    state.vy = buf[4];
    state.vz = buf[5];
    state.stretch_parallel = buf[6];
    state.stretch_perp = buf[7];
    state.rotation_rad = buf[8];
    state.pitch_deg = buf[9];
    state.roll_deg = buf[10];
    state.impact_progress = buf[11];
    state.is_docked = (buf[12] > 0.5f);
    state.is_ready_to_dock = (len >= 14) ? (buf[13] > 0.5f) : true;

    streamify::TargetDock target;
    target.x = targetX;
    target.y = targetY;
    target.initial_dist = initialDist;

    streamify::AirDropPhysicsEngine::stepRK4(state, target, dt);

    buf[0] = state.x;
    buf[1] = state.y;
    buf[2] = state.z;
    buf[3] = state.vx;
    buf[4] = state.vy;
    buf[5] = state.vz;
    buf[6] = state.stretch_parallel;
    buf[7] = state.stretch_perp;
    buf[8] = state.rotation_rad;
    buf[9] = state.pitch_deg;
    buf[10] = state.roll_deg;
    buf[11] = state.impact_progress;
    buf[12] = state.is_docked ? 1.0f : 0.0f;
    if (len >= 14) {
        buf[13] = state.is_ready_to_dock ? 1.0f : 0.0f;
    }

    env->ReleasePrimitiveArrayCritical(inOutBuffer, buf, 0);
}

// ═══════════════════════════════════════════════════════════════
// PROJECT NEXUS: CLOSED-LOOP EDGE MESH COMPUTE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_pinToLittleCores(JNIEnv* /* env */, jobject /* this */) {
    TaskOrchestrator::getInstance().pinThreadToLittleCores();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_analyzePcmAcousticDNA(
    JNIEnv* env,
    jobject /* this */,
    jobject directByteBuffer,
    jint byteCount,
    jint sampleRate,
    jint channelCount,
    jfloatArray outResults
) {
    if (!directByteBuffer || byteCount <= 0 || !outResults) {
        return env->NewStringUTF("8B");
    }

    // Clamp against real capacities: direct buffer size AND the out-array
    // (AudioPipeline writes exactly 4 result floats).
    const jlong bufCapacity = env->GetDirectBufferCapacity(directByteBuffer);
    if (bufCapacity < static_cast<jlong>(byteCount)) {
        return env->NewStringUTF("8B");
    }
    if (env->GetArrayLength(outResults) < 4) {
        return env->NewStringUTF("8B");
    }

    void* rawPtr = env->GetDirectBufferAddress(directByteBuffer);
    if (!rawPtr) {
        return env->NewStringUTF("8B");
    }

    jfloat* results = env->GetFloatArrayElements(outResults, nullptr);
    if (!results) {
        return env->NewStringUTF("8B");
    }

    int numFloats = byteCount / sizeof(float);
    const float* pcm = reinterpret_cast<const float*>(rawPtr);

    // If stereo or multi-channel, downmix to mono in temporary stack/heap buffer
    std::vector<float> monoPcm;
    if (channelCount > 1 && numFloats > 0) {
        int monoFrames = numFloats / channelCount;
        monoPcm.resize(monoFrames);
        for (int i = 0; i < monoFrames; ++i) {
            float sum = 0.0f;
            for (int ch = 0; ch < channelCount; ++ch) {
                sum += pcm[i * channelCount + ch];
            }
            monoPcm[i] = sum / static_cast<float>(channelCount);
        }
        pcm = monoPcm.data();
        numFloats = monoFrames;
    }

    std::string camelotKey = AudioPipeline::getInstance().extractAcousticDNAFromPcm(
        pcm,
        numFloats,
        sampleRate > 0 ? sampleRate : 44100,
        results
    );

    env->ReleaseFloatArrayElements(outResults, results, 0);
    return env->NewStringUTF(camelotKey.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_calculateLyricDrift(
    JNIEnv* env,
    jobject /* this */,
    jobject directPcmBuffer,
    jint pcmByteCount,
    jlongArray textOnsetsMs,
    jint onsetCount,
    jint sampleRate,
    jint channelCount
) {
    if (!directPcmBuffer || pcmByteCount <= 0 || !textOnsetsMs || onsetCount <= 0) {
        return 0;
    }

    // Clamp PCM byte count against real buffer capacity, and the onset count
    // against the real array length (previously trusted -> OOB reads).
    const jlong bufCapacity = env->GetDirectBufferCapacity(directPcmBuffer);
    if (bufCapacity < static_cast<jlong>(pcmByteCount)) {
        return 0;
    }
    const jint actualOnsets = env->GetArrayLength(textOnsetsMs);
    const jint safeOnsetCount = std::min<jint>(onsetCount, actualOnsets);
    if (safeOnsetCount <= 0) {
        return 0;
    }

    void* rawPcm = env->GetDirectBufferAddress(directPcmBuffer);
    if (!rawPcm) {
        return 0;
    }

    jlong* onsets = env->GetLongArrayElements(textOnsetsMs, nullptr);
    if (!onsets) {
        return 0;
    }

    std::vector<uint32_t> onsetsU32(static_cast<size_t>(safeOnsetCount));
    for (int i = 0; i < safeOnsetCount; ++i) {
        onsetsU32[static_cast<size_t>(i)] = static_cast<uint32_t>(std::max<jlong>(0, onsets[i]));
    }
    env->ReleaseLongArrayElements(textOnsetsMs, onsets, JNI_ABORT);

    int numFloats = pcmByteCount / sizeof(float);
    const float* pcm = reinterpret_cast<const float*>(rawPcm);

    int32_t drift = LyricAligner::getInstance().calculateDriftMs(
        pcm,
        numFloats,
        sampleRate > 0 ? sampleRate : 44100,
        channelCount > 0 ? channelCount : 2,
        onsetsU32.data(),
        safeOnsetCount
    );

    return drift;
}

// ═══════════════════════════════════════════════════════════════
// PROJECT TITAN: HIGH-PERFORMANCE RUST CORE ENGINE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

#include <dlfcn.h>

static void* get_rust_core_handle() {
    static void* handle = nullptr;
    static bool initialized = false;
    if (!initialized) {
        handle = dlopen("libstreamify_core_rs.so", RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            handle = dlopen("libstreamify_core.so", RTLD_NOW | RTLD_GLOBAL);
        }
        initialized = true;
    }
    return handle;
}

template <typename Func>
static Func get_rust_symbol(const char* name) {
    // SYMBOL CACHE: dlsym is a linear walk of the .dynsym table (~µs) and the
    // audio path / fuzzy loops hit these trampolines thousands of times per
    // second. Each symbol is resolved exactly once; failed lookups are cached
    // too (a missing symbol stays missing — get_rust_core_handle's own result
    // is likewise permanent).
    static std::mutex cache_mutex;
    static std::unordered_map<std::string, void*> cache;
    {
        std::lock_guard<std::mutex> lock(cache_mutex);
        auto it = cache.find(name);
        if (it != cache.end()) return reinterpret_cast<Func>(it->second);
    }
    void* h = get_rust_core_handle();
    if (!h) return nullptr;
    void* sym = dlsym(h, name);
    {
        std::lock_guard<std::mutex> lock(cache_mutex);
        cache.emplace(name, sym);
    }
    return reinterpret_cast<Func>(sym);
}

typedef void (*RustFreeStringFn)(char*);

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustFuzzyRankCandidates(
    JNIEnv* env,
    jobject /* this */,
    jstring query,
    jstring candidatesJson
) {
    if (!query || !candidatesJson) return nullptr;
    typedef char* (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_fuzzy_rank_candidates");
    if (!fn) return nullptr;

    const char* q = env->GetStringUTFChars(query, nullptr);
    const char* c = env->GetStringUTFChars(candidatesJson, nullptr);

    char* res = fn(q, c);

    env->ReleaseStringUTFChars(query, q);
    env->ReleaseStringUTFChars(candidatesJson, c);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustParseInnertubeCandidates(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray jsonBytes
) {
    if (!jsonBytes) return nullptr;
    jsize len = env->GetArrayLength(jsonBytes);
    if (len <= 0) return nullptr;

    // Byte-array input (not String): raw UTF-8 crosses once, no modified-UTF8
    // round trip, and the Rust side takes ptr+len directly.
    typedef char* (*RustFn)(const unsigned char*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_parse_innertube_candidates");
    if (!fn) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(jsonBytes, nullptr);
    if (!bytes) return nullptr;

    char* res = fn(reinterpret_cast<const unsigned char*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jsonBytes, bytes, JNI_ABORT);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_rustCalculateSimilarity(
    JNIEnv* env,
    jobject /* this */,
    jstring s1,
    jstring s2
) {
    if (!s1 || !s2) return 0.0f;
    typedef float (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_calculate_string_similarity");
    if (!fn) return 0.0f;

    const char* c1 = env->GetStringUTFChars(s1, nullptr);
    const char* c2 = env->GetStringUTFChars(s2, nullptr);

    float sim = fn(c1, c2);

    env->ReleaseStringUTFChars(s1, c1);
    env->ReleaseStringUTFChars(s2, c2);
    return sim;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustParseYouTubePlaylist(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray jsonBytes
) {
    if (!jsonBytes) return nullptr;
    jsize len = env->GetArrayLength(jsonBytes);
    if (len <= 0) return nullptr;

    typedef char* (*RustFn)(const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_parse_youtube_playlist");
    if (!fn) return nullptr;

    jbyte* bytes = env->GetByteArrayElements(jsonBytes, nullptr);
    char* res = fn(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(jsonBytes, bytes, JNI_ABORT);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustComputeFftSpectrum(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray pcmFloats,
    jint barCount,
    jfloatArray outBars
) {
    if (!pcmFloats || !outBars || barCount <= 0) return -1;
    jsize pcmLen = env->GetArrayLength(pcmFloats);
    jsize outLen = env->GetArrayLength(outBars);
    if (pcmLen <= 0 || outLen < barCount) return -1;

    typedef int32_t (*RustFn)(const float*, size_t, size_t, float*);
    auto fn = get_rust_symbol<RustFn>("rust_compute_fft_spectrum");
    if (!fn) return -1;

    jfloat* pcm = env->GetFloatArrayElements(pcmFloats, nullptr);
    jfloat* bars = env->GetFloatArrayElements(outBars, nullptr);

    int32_t code = fn(pcm, pcmLen, barCount, bars);

    env->ReleaseFloatArrayElements(pcmFloats, pcm, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBars, bars, 0);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustProcessEqualizerFrame(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray pcmFloats,
    jint channels,
    jfloatArray gains
) {
    if (!pcmFloats || channels <= 0) return -1;
    jsize pcmLen = env->GetArrayLength(pcmFloats);
    if (pcmLen <= 0) return -1;

    typedef int32_t (*RustFn)(float*, size_t, size_t, const float*);
    auto fn = get_rust_symbol<RustFn>("rust_process_equalizer_frame");
    if (!fn) return -1;

    jfloat* pcm = env->GetFloatArrayElements(pcmFloats, nullptr);
    jfloat* g = gains ? env->GetFloatArrayElements(gains, nullptr) : nullptr;

    int32_t code = fn(pcm, pcmLen, channels, g);

    env->ReleaseFloatArrayElements(pcmFloats, pcm, 0);
    if (gains && g) {
        env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
    }
    return code;
}

// 1. Thread-safe persistent DSP instances for float mastering (EQ -> LUFS -> Limiter)
//    ADDITIVE EXTENSION (audit #22 item 9): nativeConfigureAudioDSP(
//    sampleRate, channels) -> Boolean re-configures the float DSP
//    singletons (and the PCM-tap pair) for the ACTUAL stream geometry
//    — they were pinned at 48 kHz, wrong for 44.1 kHz content.
static SoftKneeLimiter* g_floatLimiter = nullptr;
static LufsNormalizer* g_floatLufs = nullptr;
static std::mutex g_floatDspMutex;

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeInitAudioDSP(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_floatDspMutex);
    if (!g_floatLimiter) {
        g_floatLimiter = new SoftKneeLimiter(-1.0f, 2.0f, 20.0f);
    }
    if (!g_floatLufs) {
        g_floatLufs = new LufsNormalizer();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeResetAudioDSP(JNIEnv* /* env */, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_floatDspMutex);
    if (g_floatLufs) g_floatLufs->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeProcessFloatAudio(
    JNIEnv* env,
    jobject /* this */,
    jobject byte_buffer,
    jint num_frames,
    jint channels,
    jfloatArray eq_gains
) {
    if (!byte_buffer || num_frames <= 0 || channels <= 0) return;

    float* pcm_data = reinterpret_cast<float*>(env->GetDirectBufferAddress(byte_buffer));
    if (pcm_data == nullptr) return;

    int total_samples = num_frames * channels;

    // A. Apply Rust Parametric Studio Equalizer (if 10-band gains provided)
    if (eq_gains) {
        jsize gainLen = env->GetArrayLength(eq_gains);
        if (gainLen == 10) {
            typedef int32_t (*RustFn)(float*, size_t, size_t, const float*);
            auto eq_fn = get_rust_symbol<RustFn>("rust_process_equalizer_frame");
            if (eq_fn) {
                jfloat* g = env->GetFloatArrayElements(eq_gains, nullptr);
                eq_fn(pcm_data, total_samples, channels, g);
                env->ReleaseFloatArrayElements(eq_gains, g, JNI_ABORT);
            }
        }
    }

    std::lock_guard<std::mutex> lock(g_floatDspMutex);
    if (!g_floatLimiter) {
        g_floatLimiter = new SoftKneeLimiter(-1.0f, 2.0f, 20.0f);
    }
    if (!g_floatLufs) {
        g_floatLufs = new LufsNormalizer();
    }

    // B. LUFS Normalization
    g_floatLufs->processFloats(pcm_data, total_samples, -14.0f);

    // C. Soft-Knee Limiter (Safety ceiling at -1dB)
    g_floatLimiter->processFloats(pcm_data, total_samples);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_streamify_app_data_NativeBridge_rustCompileToSlyr(
    JNIEnv* env,
    jobject /* this */,
    jstring lrcContent
) {
    if (!lrcContent) return nullptr;
    typedef uint8_t* (*RustFn)(const uint8_t*, size_t, size_t*);
    typedef void (*RustFreeFn)(uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_compile_to_slyr");
    auto free_fn = get_rust_symbol<RustFreeFn>("rust_free_slyr_buffer");
    if (!fn) return nullptr;

    const char* utf = env->GetStringUTFChars(lrcContent, nullptr);
    jsize len = env->GetStringUTFLength(lrcContent);

    size_t out_len = 0;
    uint8_t* slyr_bytes = fn(reinterpret_cast<const uint8_t*>(utf), static_cast<size_t>(len), &out_len);
    env->ReleaseStringUTFChars(lrcContent, utf);

    if (!slyr_bytes || out_len == 0) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out_len));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out_len), reinterpret_cast<const jbyte*>(slyr_bytes));

    if (free_fn) free_fn(slyr_bytes, out_len);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustDownloadStreamDirect(
    JNIEnv* env,
    jobject /* this */,
    jstring streamUrl,
    jstring destPath
) {
    if (!streamUrl || !destPath) return nullptr;
    typedef char* (*RustFn)(const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_download_stream_direct");
    if (!fn) return nullptr;

    const char* url = env->GetStringUTFChars(streamUrl, nullptr);
    const char* path = env->GetStringUTFChars(destPath, nullptr);

    char* res = fn(url, path);

    env->ReleaseStringUTFChars(streamUrl, url);
    env->ReleaseStringUTFChars(destPath, path);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustScoreAndRankRadioCandidates(
    JNIEnv* env,
    jobject /* this */,
    jstring candidatesJson,
    jfloat seedBpm,
    jstring seedKey,
    jint seedDurSec,
    jstring seedSig,
    jstring queueJson
) {
    if (!candidatesJson) return nullptr;
    typedef char* (*RustFn)(const char*, float, const char*, int32_t, const char*, const char*);
    auto fn = get_rust_symbol<RustFn>("rust_score_and_rank_radio_candidates");
    if (!fn) return nullptr;

    const char* cJson = env->GetStringUTFChars(candidatesJson, nullptr);
    const char* sKey = seedKey ? env->GetStringUTFChars(seedKey, nullptr) : "";
    const char* sSig = seedSig ? env->GetStringUTFChars(seedSig, nullptr) : "";
    const char* qJson = queueJson ? env->GetStringUTFChars(queueJson, nullptr) : "[]";

    char* res = fn(cJson, seedBpm, sKey, seedDurSec, sSig, qJson);

    env->ReleaseStringUTFChars(candidatesJson, cJson);
    if (seedKey) env->ReleaseStringUTFChars(seedKey, sKey);
    if (seedSig) env->ReleaseStringUTFChars(seedSig, sSig);
    if (queueJson) env->ReleaseStringUTFChars(queueJson, qJson);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustProcessCrossfadePcm(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray outgoingBuf,
    jfloatArray incomingBuf,
    jfloatArray mixedBuf,
    jfloat progress
) {
    if (!outgoingBuf || !incomingBuf || !mixedBuf) return -1;
    jsize len = env->GetArrayLength(mixedBuf);
    if (len <= 0) return -1;

    typedef int32_t (*RustFn)(const float*, const float*, float*, size_t, float);
    auto fn = get_rust_symbol<RustFn>("rust_process_crossfade_pcm");
    if (!fn) return -1;

    jfloat* out = env->GetFloatArrayElements(outgoingBuf, nullptr);
    jfloat* in = env->GetFloatArrayElements(incomingBuf, nullptr);
    jfloat* mixed = env->GetFloatArrayElements(mixedBuf, nullptr);

    int32_t code = fn(out, in, mixed, static_cast<size_t>(len), progress);

    env->ReleaseFloatArrayElements(outgoingBuf, out, JNI_ABORT);
    env->ReleaseFloatArrayElements(incomingBuf, in, JNI_ABORT);
    env->ReleaseFloatArrayElements(mixedBuf, mixed, 0);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustEncryptVaultFile(
    JNIEnv* env,
    jobject /* this */,
    jstring srcPath,
    jstring destPath,
    jbyteArray masterKey
) {
    if (!srcPath || !destPath || !masterKey) return -1;
    typedef int32_t (*RustFn)(const char*, const char*, const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_encrypt_vault_file");
    if (!fn) return -1;

    const char* src = env->GetStringUTFChars(srcPath, nullptr);
    const char* dest = env->GetStringUTFChars(destPath, nullptr);
    jsize keyLen = env->GetArrayLength(masterKey);
    jbyte* key = env->GetByteArrayElements(masterKey, nullptr);

    int32_t code = fn(src, dest, reinterpret_cast<const uint8_t*>(key), static_cast<size_t>(keyLen));

    env->ReleaseStringUTFChars(srcPath, src);
    env->ReleaseStringUTFChars(destPath, dest);
    env->ReleaseByteArrayElements(masterKey, key, JNI_ABORT);
    return code;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_rustDecryptVaultFile(
    JNIEnv* env,
    jobject /* this */,
    jstring srcPath,
    jstring destPath,
    jbyteArray masterKey
) {
    if (!srcPath || !destPath || !masterKey) return -1;
    typedef int32_t (*RustFn)(const char*, const char*, const uint8_t*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_decrypt_vault_file");
    if (!fn) return -1;

    const char* src = env->GetStringUTFChars(srcPath, nullptr);
    const char* dest = env->GetStringUTFChars(destPath, nullptr);
    jsize keyLen = env->GetArrayLength(masterKey);
    jbyte* key = env->GetByteArrayElements(masterKey, nullptr);

    int32_t code = fn(src, dest, reinterpret_cast<const uint8_t*>(key), static_cast<size_t>(keyLen));

    env->ReleaseStringUTFChars(srcPath, src);
    env->ReleaseStringUTFChars(destPath, dest);
    env->ReleaseByteArrayElements(masterKey, key, JNI_ABORT);
    return code;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustParseBackupCsv(
    JNIEnv* env,
    jobject /* this */,
    jstring csvContent
) {
    if (!csvContent) return nullptr;
    typedef char* (*RustFn)(const char*);
    auto fn = get_rust_symbol<RustFn>("rust_parse_backup_csv");
    if (!fn) return nullptr;

    const char* csv = env->GetStringUTFChars(csvContent, nullptr);
    char* res = fn(csv);
    env->ReleaseStringUTFChars(csvContent, csv);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustAlignAndCompileLyrics(
    JNIEnv* env,
    jobject /* this */,
    jstring rawLyrics,
    jint durationMs,
    jfloatArray energyFloats
) {
    if (!rawLyrics) return nullptr;
    typedef char* (*RustFn)(const char*, uint32_t, const float*, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_align_and_compile_lyrics");
    if (!fn) return nullptr;

    const char* text = env->GetStringUTFChars(rawLyrics, nullptr);
    jfloat* energy = energyFloats ? env->GetFloatArrayElements(energyFloats, nullptr) : nullptr;
    jsize energyLen = energyFloats ? env->GetArrayLength(energyFloats) : 0;

    char* res = fn(text, static_cast<uint32_t>(durationMs), energy, static_cast<size_t>(energyLen));

    env->ReleaseStringUTFChars(rawLyrics, text);
    if (energyFloats && energy) {
        env->ReleaseFloatArrayElements(energyFloats, energy, JNI_ABORT);
    }

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_rustGenerateNeuroQueue(
    JNIEnv* env,
    jobject /* this */,
    jstring seedJson,
    jstring candidatesJson,
    jint brainState,
    jlong nowSec,
    jint hourOfDay,
    jint targetCount
) {
    if (!seedJson || !candidatesJson) return nullptr;
    typedef char* (*RustFn)(const char*, const char*, uint8_t, uint64_t, uint32_t, size_t);
    auto fn = get_rust_symbol<RustFn>("rust_generate_neuro_queue");
    if (!fn) return nullptr;

    const char* seed = env->GetStringUTFChars(seedJson, nullptr);
    const char* candidates = env->GetStringUTFChars(candidatesJson, nullptr);

    char* res = fn(
        seed,
        candidates,
        static_cast<uint8_t>(brainState),
        static_cast<uint64_t>(nowSec),
        static_cast<uint32_t>(hourOfDay),
        static_cast<size_t>(targetCount)
    );

    env->ReleaseStringUTFChars(seedJson, seed);
    env->ReleaseStringUTFChars(candidatesJson, candidates);

    if (!res) return nullptr;
    jstring outStr = env->NewStringUTF(res);
    auto free_fn = get_rust_symbol<RustFreeStringFn>("rust_free_string");
    if (free_fn) free_fn(res);
    return outStr;
}

// ═══════════════════════════════════════════════════════════════
// PHASE 3: C++20 DSP & 128-D VECTOR STORE JNI EXPORTS
// ═══════════════════════════════════════════════════════════════

static LufsNormalizer g_lufs_normalizer;
static SoftKneeLimiter g_soft_knee_limiter;
// NOTE: vector access MUST go through VectorStore::getInstance() — a previous
// standalone g_vector_store global created a split-brain: embeddings inserted
// via nativeInsertVector were invisible to searchSimilarTracks (and vice versa).

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_nativePinToLittleCores(JNIEnv* /*env*/, jobject /*thiz*/) {
    return TaskOrchestrator::pinCurrentThreadToLittleCores() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_streamify_app_data_NativeBridge_nativeProcessPcmTap(
    JNIEnv* env, jobject /*thiz*/, jobject direct_byte_buffer, jint sample_count) {
    if (!direct_byte_buffer || sample_count <= 0) return 1.0f;
    // sample_count is treated as an interleaved float count downstream; clamp
    // it to the buffer's real capacity before any in-place write.
    const jlong cap = env->GetDirectBufferCapacity(direct_byte_buffer);
    if (cap < static_cast<jlong>(sample_count) * static_cast<jlong>(sizeof(float))) return 1.0f;

    auto* pcm_data = static_cast<float*>(env->GetDirectBufferAddress(direct_byte_buffer));
    if (!pcm_data || sample_count <= 0) return 1.0f;

    // 1. In-Place Soft Knee Limiting
    g_soft_knee_limiter.processInterleavedSIMD(pcm_data, sample_count);

    // 2. K-Weighting Analysis (at the CONFIGURED rate — audit #22 item 9;
    //    nativeConfigureAudioDSP rebuilds these filters per geometry).
    g_lufs_normalizer.processChannelSIMD(pcm_data, sample_count);
    const float* channels[] = { pcm_data };
    float lufs = g_lufs_normalizer.computeIntegratedLufs(
        channels, 1, sample_count, g_lufs_normalizer.sampleRate());
    return g_lufs_normalizer.calculateNormalizationGain(lufs, -14.0f);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_streamify_app_data_NativeBridge_nativeConfigureAudioDSP(
        JNIEnv* /* env */, jobject /* this */, jint sampleRate, jint channels) {
    // Audit #22 item 9: the legacy float-DSP singletons were pinned at
    // 48 kHz / hard-coded channel math; 44.1 kHz content (the most common
    // Android output rate) measured and limited through the wrong
    // K-weighting and time constants. Configure ALL four legacy DSP
    // singletons from one knob. Idempotent: every sub-engine
    // change-detects and early-returns on a repeat geometry.
    if (sampleRate < 8000 || sampleRate > 192000) return JNI_FALSE;
    if (channels < 1 || channels > LufsNormalizer::kMaxChannels) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(g_floatDspMutex);
        if (!g_floatLimiter) g_floatLimiter = new SoftKneeLimiter(-1.0f, 2.0f, 20.0f);
        if (!g_floatLufs) g_floatLufs = new LufsNormalizer();
        g_floatLufs->configure(sampleRate, channels);
        g_floatLimiter->setSampleRate(sampleRate);
        g_floatLimiter->reset();
    }
    // The PCM-tap pair (nativeProcessPcmTap) shares the same discipline.
    g_lufs_normalizer.configure(sampleRate, 1);
    g_soft_knee_limiter.setSampleRate(sampleRate);
    g_soft_knee_limiter.reset();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeInsertVector(
    JNIEnv* env, jobject /*thiz*/, jlong track_id, jfloatArray embedding_array) {
    if (!embedding_array) return;
    // VectorStore::insert reads exactly VECTOR_DIM floats; reject short arrays.
    if (env->GetArrayLength(embedding_array) < static_cast<jint>(VECTOR_DIM)) return;
    jfloat* elements = env->GetFloatArrayElements(embedding_array, nullptr);
    if (elements) {
        VectorStore::getInstance().insert(static_cast<uint64_t>(track_id), elements);
        env->ReleaseFloatArrayElements(embedding_array, elements, JNI_ABORT);
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_streamify_app_data_NativeBridge_nativeQueryTopK(
    JNIEnv* env, jobject /*thiz*/, jfloatArray target_embedding_array, jint k) {
    if (!target_embedding_array || k <= 0) return nullptr;
    // Cosine kernel reads VECTOR_DIM floats from the query vector.
    if (env->GetArrayLength(target_embedding_array) < static_cast<jint>(VECTOR_DIM)) return nullptr;

    jfloat* elements = env->GetFloatArrayElements(target_embedding_array, nullptr);
    if (!elements) return nullptr;

    auto results = VectorStore::getInstance().queryTopK(elements, k);
    env->ReleaseFloatArrayElements(target_embedding_array, elements, JNI_ABORT);

    jlongArray output = env->NewLongArray(static_cast<jsize>(results.size()));
    if (output && !results.empty()) {
        std::vector<jlong> track_ids(results.size());
        for (size_t i = 0; i < results.size(); ++i) {
            track_ids[i] = static_cast<jlong>(results[i].track_id);
        }
        env->SetLongArrayRegion(output, 0, static_cast<jsize>(results.size()), track_ids.data());
    }
    return output;
}

static LyricAligner g_lyric_aligner(30);

JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_nativeCalculateDriftOffset(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray vocal_energy_array, jint vocal_len,
    jfloatArray lyric_onsets_array, jint lyric_len) {

    if (!vocal_energy_array || !lyric_onsets_array) return 0;

    // Clamp declared lengths against real array bounds.
    const jint vocal_actual = env->GetArrayLength(vocal_energy_array);
    const jint lyric_actual = env->GetArrayLength(lyric_onsets_array);
    const jint safeVocalLen = std::min<jint>(vocal_len, vocal_actual);
    const jint safeLyricLen = std::min<jint>(lyric_len, lyric_actual);
    if (safeVocalLen <= 0 || safeLyricLen <= 0) return 0;

    jfloat* vocal_ptr = env->GetFloatArrayElements(vocal_energy_array, nullptr);
    jfloat* lyric_ptr = env->GetFloatArrayElements(lyric_onsets_array, nullptr);

    if (!vocal_ptr || !lyric_ptr) {
        if (vocal_ptr) env->ReleaseFloatArrayElements(vocal_energy_array, vocal_ptr, JNI_ABORT);
        if (lyric_ptr) env->ReleaseFloatArrayElements(lyric_onsets_array, lyric_ptr, JNI_ABORT);
        return 0;
    }

    int drift_ms = g_lyric_aligner.calculateDriftOffset(
        vocal_ptr, static_cast<size_t>(safeVocalLen),
        lyric_ptr, static_cast<size_t>(safeLyricLen)
    );

    env->ReleaseFloatArrayElements(vocal_energy_array, vocal_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(lyric_onsets_array, lyric_ptr, JNI_ABORT);

    return static_cast<jint>(drift_ms);
}

JNIEXPORT jstring JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGenerateCadId(
    JNIEnv* env,
    jclass /* clazz */,
    jstring title,
    jstring artist,
    jint durationSec) noexcept {
    try {
        const char* titleChars = title ? env->GetStringUTFChars(title, nullptr) : nullptr;
        const char* artistChars = artist ? env->GetStringUTFChars(artist, nullptr) : nullptr;

        std::string cleanTitle = "";
        if (titleChars) {
            for (size_t i = 0; titleChars[i] != '\0'; ++i) {
                char c = titleChars[i];
                if (std::isalnum(static_cast<unsigned char>(c)) || c == '(') {
                    cleanTitle += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                }
            }
            env->ReleaseStringUTFChars(title, titleChars);
        }

        std::string cleanArtist = "";
        if (artistChars) {
            for (size_t i = 0; artistChars[i] != '\0'; ++i) {
                char c = artistChars[i];
                if (std::isalnum(static_cast<unsigned char>(c))) {
                    cleanArtist += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                }
            }
            env->ReleaseStringUTFChars(artist, artistChars);
        }

        uint32_t durationBucket = durationSec > 0 ? static_cast<uint32_t>(durationSec / 3) : 0;

        // FNV-1a 64-bit hash
        uint64_t hash = 14695981039346656037ULL;
        for (char c : cleanTitle) {
            hash ^= static_cast<uint8_t>(c);
            hash *= 1099511628211ULL;
        }
        for (char c : cleanArtist) {
            hash ^= static_cast<uint8_t>(c);
            hash *= 1099511628211ULL;
        }
        for (int i = 0; i < 4; ++i) {
            hash ^= static_cast<uint8_t>((durationBucket >> (i * 8)) & 0xFF);
            hash *= 1099511628211ULL;
        }

        char hexBuf[32];
        snprintf(hexBuf, sizeof(hexBuf), "%016llx", static_cast<unsigned long long>(hash));
        return env->NewStringUTF(hexBuf);
    } catch (...) {
        return env->NewStringUTF("0000000000000000");
    }
}

} // extern "C"



