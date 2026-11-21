#include <iostream>
#include <vector>
#include <cassert>
#include <iterator>
#include <cstdarg>
#include "../engine/StreamifyDB.h"
#include "../engine/TelemetryEngine.h"

#ifndef __ANDROID__
extern "C" int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    return 0;
}
#endif

int main() {
    std::cout << "[TEST] Starting Storage & Telemetry Test Suite..." << std::endl;

    // 1. Test Vyukov Lock-Free MPMC Ring Buffer
    VyukovMPMCQueue<TelemetryEvent, 64> queue;
    TelemetryEvent ev1{TelemetryEventType::SCRUB_SEEK, 101, 45000.0f, 1700000000};
    TelemetryEvent ev2{TelemetryEventType::VOLUME_CHANGE, 101, 0.85f, 1700000001};

    assert(queue.push(ev1));
    assert(queue.push(ev2));

    auto outEv1 = queue.pop();
    assert(outEv1.has_value());
    assert(outEv1->type == TelemetryEventType::SCRUB_SEEK);
    assert(outEv1->trackId == 101);

    auto outEv2 = queue.pop();
    assert(outEv2.has_value());
    assert(outEv2->type == TelemetryEventType::VOLUME_CHANGE);
    assert(outEv2->value == 0.85f);
    std::cout << "  - Lock-Free MPMC Ring Buffer: PASSED" << std::endl;

    // 2. Test StreamifyDB SQLite WAL In-Memory
    StreamifyDB& db = StreamifyDB::getInstance();
    assert(db.init(":memory:"));

    int trackId = db.insertTrack("/storage/emulated/0/Music/track1.flac", "Starboy", "The Weeknd", "Starboy", 230, 186.0);
    assert(trackId > 0);

    auto trackOpt = db.getTrackById(trackId);
    assert(trackOpt.has_value());
    assert(trackOpt->title == "Starboy");
    assert(trackOpt->artist == "The Weeknd");
    std::cout << "  - SQLite WAL Database CRUD: PASSED" << std::endl;

    std::cout << "[TEST] All Storage & Telemetry Tests Passed Successfully!" << std::endl;
    return 0;
}
