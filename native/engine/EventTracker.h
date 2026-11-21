#ifndef EVENT_TRACKER_H
#define EVENT_TRACKER_H

class EventTracker {
public:
    static EventTracker& getInstance();

    void logPlay(int fromTrackId, int toTrackId, int userId = 1);
    void logSkip(int fromTrackId, int toTrackId, int userId = 1);

private:
    EventTracker() = default;
    ~EventTracker() = default;
    EventTracker(const EventTracker&) = delete;
    EventTracker& operator=(const EventTracker&) = delete;
};

#endif // EVENT_TRACKER_H
