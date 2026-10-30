import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-07 (Engineer 4): BlendViewModel — the 2-user taste
// overlap generator + shared playlist bootstrap; the Swift mirror of
// `BlendViewModel.kt` (loading state, memoization key, playable queue).
//
// The overlap scoring (Jaccard over distinct artist sets, 35 + jaccard × 64
// clamped to 35...99) is computed by the engine behind the feature seam —
// never re-implemented here (SW-DEC-05).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class BlendViewModel {

    // ── State (mirror of BlendUiState) ─────────────────────────────────────

    private(set) var isLoading = true
    private(set) var myName = "You"
    private(set) var friendName = "Friend"
    private(set) var friendAvatarUrl = ""
    private(set) var result: BlendResult?

    /// Memoization key (Kotlin parity: `"$friendName|${friendSeeds...}"`) —
    /// an identical request never regenerates an existing result.
    private var generatedKey: String?

    private var generateTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
    }

    deinit {
        generateTask?.cancel()
    }

    // MARK: - Generation

    /// Generates (or memoizes) the taste blend for a friend identified by
    /// their seed artists/tracks. Keeps the previous result visible while a
    /// regeneration is in flight (Kotlin parity).
    func generateBlend(friendName: String, friendSeeds: [String], friendAvatarUrl: String = "") {
        let key = "\(friendName)|\(friendSeeds.joined(separator: ","))"
        if key == generatedKey, result != nil { return }

        generatedKey = key
        self.friendName = friendName.isEmpty ? "Friend" : friendName
        self.friendAvatarUrl = friendAvatarUrl
        isLoading = result == nil

        generateTask?.cancel()
        let library = library
        generateTask = Task { [weak self] in
            let blend = await library.fetchBlend(friendName: friendName, friendSeeds: friendSeeds)
            guard !Task.isCancelled, let self else { return }
            self.isLoading = false
            self.result = blend
        }
    }

    // MARK: - Projections

    var matchPercent: Int { result?.matchPercent ?? 0 }

    var mutualArtists: [String] { result?.mutualArtists ?? [] }

    var tracks: [BlendTrack] { result?.tracks ?? [] }

    /// Shared-playlist bootstrap: the queue behind "Play Blend".
    var playableQueue: [PresentationTrack] { result?.playableQueue ?? [] }

    var isEmpty: Bool {
        !isLoading && (result?.tracks.isEmpty ?? true)
    }
}
