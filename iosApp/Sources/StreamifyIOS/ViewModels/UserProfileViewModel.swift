import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): UserProfileViewModel — public follower counts and
// listening taste profile; the Swift mirror of `UserProfileScreen`'s state
// (Supabase `UserProfile` + the chronotype card derived from the local hour).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class UserProfileViewModel {

    private(set) var profile: UserProfileSummary = .default
    private(set) var isLoading = true
    private(set) var lastError: String?

    /// The listening chronotype card (derived from the local hour).
    private(set) var chronotype: Chronotype

    /// True while viewing another listener's public profile.
    private(set) var isViewingOtherUser = false

    /// The stored load task handle. `nonisolated(unsafe)`: the handle is
    /// only mutated on the main actor (init / load()); `deinit` — which is
    /// always nonisolated — reads it solely to cancel, and `Task.cancel()`
    /// is itself thread-safe.
    nonisolated(unsafe) private var loadTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
        self.chronotype = Chronotype.chronotype(forHour: Calendar.current.component(.hour, from: Date()))
    }

    deinit {
        loadTask?.cancel()
    }

    // MARK: - Load

    /// Loads the profile for `userId` (nil = the local user's own profile).
    func load(userId: String?) {
        isViewingOtherUser = userId != nil
        isLoading = true
        lastError = nil
        loadTask?.cancel()
        let library = library
        loadTask = Task { [weak self] in
            let summary = await library.userProfile(userId: userId)
            guard !Task.isCancelled, let self else { return }
            self.profile = summary
            self.chronotype = Chronotype.chronotype(
                forHour: Calendar.current.component(.hour, from: Date())
            )
            self.isLoading = false
        }
    }

    // MARK: - Projections

    var displayName: String { profile.displayName }
    var bio: String { profile.bio }
    var avatarUrl: String? { profile.avatarUrl }

    /// Follower counts compacted exactly like the Kotlin `formatFollowerCount`
    /// (1.2K / 12K / 1.2M / raw).
    var followerCountLabel: String {
        UserProfileSummary.formatFollowerCount(profile.followerCount)
    }

    var followingCountLabel: String {
        UserProfileSummary.formatFollowerCount(profile.followingCount)
    }

    var favoriteGenre: String { profile.favoriteGenre }
    var topGenre: String { profile.topGenre }
    var isPrivate: Bool { profile.isPrivate }

    /// Listening hours compacted to a human label.
    var listeningSummary: String {
        let minutes = profile.listeningMinutes
        if minutes >= 60 {
            return String(format: "%.1f hrs this month", Double(minutes) / 60)
        }
        return "\(minutes) min this month"
    }
}
