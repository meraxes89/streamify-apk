#include "EventTracker.h"
#include "StreamifyDB.h"
#include "../util/stlog.h"

EventTracker& EventTracker::getInstance() {
    static EventTracker instance;
    return instance;
}

void EventTracker::logPlay(int fromTrackId, int toTrackId, int userId) {
    if (toTrackId <= 0) return;
    StreamifyDB::getInstance().insertTransition(userId, fromTrackId, toTrackId, "play");
    __android_log_print(ANDROID_LOG_INFO, "StreamifyNative", "[EventTracker] Logged PLAY transition from %d to %d", fromTrackId, toTrackId);
}

void EventTracker::logSkip(int fromTrackId, int toTrackId, int userId) {
    if (toTrackId <= 0) return;
    StreamifyDB::getInstance().insertTransition(userId, fromTrackId, toTrackId, "skip");
    __android_log_print(ANDROID_LOG_INFO, "StreamifyNative", "[EventTracker] Logged SKIP transition from %d to %d", fromTrackId, toTrackId);
}
