import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-06 (Swift migration, Engineer 1): CanvasScraperService — the async
// Swift actor port of CanvasScraperApi.kt + ArtistClipsApi.kt: fetching the
// ~8-second Canvas video loops that play behind the full-screen player, and
// the artist clips rail, through the InnerTube endpoints with URLSession.
//
// ANTI-FAILURE ARCHITECTURE (the same three pillars as the Kotlin original):
//
//  1. MULTI-CLIENT HEADER FALLBACK — WEB_REMIX → ANDROID_MUSIC. Token-rotted
//     / 400 responses silently advance the fleet; the caller never sees an
//     exception (every public entry point returns Optionals — the actor
//     NEVER throws; "this track has no canvas" is a normal nil, not an
//     error).
//
//  2. DEFENSIVE RECURSIVE JSON TRAVERSAL — Canvas assets live at rotating,
//     undocumented paths. The parser walks the whole tree and collects ANY
//     object that carries a video-ish URL under a canvas-ish key;
//     unexpected shapes yield fewer loops, never a crash. Swift's strict
//     `as? String` casts are STRICTER than org.json's coercing optString —
//     exactly the discipline the Kotlin two-pass shelf check needed a
//     comment to explain.
//
//  3. STALE-WHILE-REVALIDATE CACHE — loops are immutable per videoId (24 h
//     TTL) and clips rails rotate weekly (6 h TTL), in-memory per process
//     (Kotlin parity: SwrCache(dir = null)).
//
// DELIBERATELY NOT PORTED: the hand-rolled gzip handling. URLSession
// negotiates and transparently decodes gzip itself (setting
// Accept-Encoding manually would DISABLE that), so the Kotlin
// GZIPInputStream branch collapses into nothing.
//
// MULTIPLATFORM (SW-DEC-04): Foundation-only; identical on iOS and macOS.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Models

/// A scraped Canvas loop.
struct CanvasLoop: Equatable, Sendable {
    let videoId: String
    /// Raw video container URL (mp4/webm).
    let loopUrl: String
    let mimeType: String
    /// Loop length when the source exposes it (8 s per the Canvas spec).
    var durationMs: Int = 8_000
    var width: Int = 0
    var height: Int = 0
    /// Suggested ambient glow seed (ARGB) extracted from the payload.
    var paletteColor: UInt64?
    var fromCache: Bool = false

    var isVertical: Bool { width == 0 || height == 0 || height >= width }
}

/// One vertical clip (~30 s) in the discovery rail.
struct ArtistClip: Equatable, Sendable, Identifiable {
    let clipId: String
    let videoId: String
    let title: String
    var thumbnailUrl: String = ""
    var durationSec: Int = 30
    var viewCountText: String = ""
    var audioSyncTrackVideoId: String?

    var id: String { clipId.isEmpty ? videoId : clipId }
    var isVertical30s: Bool { (1...45).contains(durationSec) }
}

/// A scraped clips page for an artist.
struct ClipsPage: Equatable, Sendable {
    var artistName: String = ""
    var clips: [ArtistClip] = []
    var fromCache: Bool = false
}

// MARK: - InnerTube fleet + transports

/// One InnerTube client spec for the scraper fleet (used strictly inside
/// the CanvasScraperService actor — no Sendable conformance because the
/// context extras are an open [String: Any] payload).
struct InnerTubeClientSpec {
    let label: String
    let clientName: String
    let clientVersion: String
    let clientHeaderId: String
    let origin: String
    let userAgent: String
    /// Extra keys merged into the `context.client` object.
    let contextExtras: [String: Any]
}

/// The frozen fleet: WEB_REMIX first, ANDROID_MUSIC as the fallback.
enum InnerTubeFleet {
    /// YouTube Music's PUBLIC InnerTube web-client key — the same
    /// identifier music.youtube.com ships to every browser (and the one
    /// the Kotlin scrapers already carry on main). It is NOT a Google
    /// Cloud credential: InnerTube is not a billable GCP API, the key
    /// grants no privileges, and it is documented across the public
    /// InnerTube-ecosystem clients. Allowlisted in .gitleaks.toml with
    /// this justification (the scanner's gcp-api-key pattern cannot know
    /// it is a public client id, not a secret).
    static let innertubeWebClientKey = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"

    private static func innertube(_ endpoint: String) -> URL {
        URL(string: "https://music.youtube.com/youtubei/v1/\(endpoint)?key=\(innertubeWebClientKey)")!
    }

    static let playerURL = innertube("player")
    static let nextURL = innertube("next")
    static let searchURL = innertube("search")
    static let browseURL = innertube("browse")

    static let desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    static let androidMusicUserAgent = "com.google.android.apps.youtube.music/8.6.53.52 (Linux; U; Android 14) US"

    /// The SONGS-filtered search params token (CanvasScraperApi parity).
    static let songsFilterParams = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    /// The artist channel's clips/shorts tab route (ArtistClipsApi parity).
    static let clipsTabParams = "EgVzaG9ydHPyBgUKA5oBAA%3D%3D"

    static func fleet() -> [InnerTubeClientSpec] {
        [
            InnerTubeClientSpec(
                label: "WEB_REMIX",
                clientName: "WEB_REMIX",
                clientVersion: "1.20240101.01.00",
                clientHeaderId: "67",
                origin: "https://music.youtube.com",
                userAgent: desktopUserAgent,
                contextExtras: [:]
            ),
            InnerTubeClientSpec(
                label: "ANDROID_MUSIC",
                clientName: "ANDROID_MUSIC",
                clientVersion: "8.6.53.52",
                clientHeaderId: "21",
                origin: "https://music.youtube.com",
                userAgent: androidMusicUserAgent,
                contextExtras: [
                    "androidSdkVersion": 34,
                    "osName": "Android",
                    "osVersion": "14",
                ]
            ),
        ]
    }
}

// MARK: - Pure parsers (no network — directly unit-testable)

/// Defensive Canvas-tree parser (port of CanvasResponseParser.kt).
enum CanvasResponseParser {

    /// Parent/owner key names that identify a canvas-ish subtree.
    static let canvasKeyHints: Set<String> = [
        "canvasMediaRenderer", "canvasOverlayRenderer", "canvasRenderer",
        "auxiliaryAssetRenderer", "canvas", "canvasUrl", "loopUrl", "canvasData",
    ]

    /// Auxiliary asset type values that mean "this is the canvas loop".
    static let canvasTypeValues: Set<String> = ["CANVAS", "CANNED", "CANVAS_LOOP", "SHORT_LOOP"]

    /// URL substrings that mark a URL as a canvas loop candidate.
    static let urlHints = ["googlevideo.com", "canvas", "loop"]

    /// Parses a `/player` response root: fast path through the documented
    /// auxiliaryAssets array, then the defensive whole-tree walk.
    static func parsePlayer(root: Any, videoId: String) -> CanvasLoop? {
        if let object = root as? [String: Any],
           let assets = object["auxiliaryAssets"] as? [Any],
           let loop = parseAuxiliaryAssets(assets, videoId: videoId) {
            return loop
        }
        return walkForCanvas(root, videoId: videoId)
    }

    /// Parses a `/next` response root (whole-tree walk).
    static func parseNext(root: Any, videoId: String) -> CanvasLoop? {
        walkForCanvas(root, videoId: videoId)
    }

    /// Extracts the top SONGS result videoId from a search response.
    static func firstSongVideoId(root: Any) -> String? {
        var found: String?
        walk(root) { node, parentKey in
            guard found == nil else { return }
            guard let object = node as? [String: Any] else { return }
            guard let videoId = object["videoId"] as? String, videoId.count == 11 else { return }
            // Songish rows live under a renderer/viewModel parent key.
            if let key = parentKey, key.hasSuffix("Renderer") || key.hasSuffix("ViewModel") {
                found = videoId
            }
        }
        return found
    }

    /// The documented auxiliaryAssets fast path.
    static func parseAuxiliaryAssets(_ assets: [Any], videoId: String) -> CanvasLoop? {
        for asset in assets {
            guard let outer = asset as? [String: Any] else { continue }
            let renderer = (outer["auxiliaryAssetRenderer"] as? [String: Any]) ?? outer
            let type = renderer["type"] as? String ?? ""
            guard let url = renderer["url"] as? String, !url.isEmpty else { continue }
            let typeMatches = type.isEmpty || canvasTypeValues.contains(type.uppercased())
            if typeMatches && looksLikeCanvasUrl(url) {
                return buildLoop(videoId: videoId, node: renderer, url: url)
            }
        }
        return nil
    }

    /// Depth-first scan: a node qualifies when its PARENT KEY is
    /// canvas-ish AND it carries a usable video URL (directly or one
    /// level deep).
    static func walkForCanvas(_ root: Any, videoId: String) -> CanvasLoop? {
        var found: CanvasLoop?
        walk(root) { node, parentKey in
            guard found == nil else { return }
            guard let object = node as? [String: Any] else { return }
            guard let key = parentKey, canvasKeyHints.contains(key) else { return }
            guard let url = extractVideoUrl(from: object) else { return }
            found = buildLoop(videoId: videoId, node: object, url: url)
        }
        return found
    }

    /// Pulls the first video-looking URL out of a node (one level deep).
    static func extractVideoUrl(from node: [String: Any]) -> String? {
        for key in ["url", "loopUrl", "mediaUrl"] {
            if let value = node[key] as? String, !value.isEmpty, looksLikeCanvasUrl(value) {
                return value
            }
        }
        // Nested one level (e.g. canvasMediaRenderer.player…).
        for key in ["player", "video", "media", "attachment"] {
            guard let child = node[key] as? [String: Any] else { continue }
            if let value = child["url"] as? String, !value.isEmpty, looksLikeCanvasUrl(value) {
                return value
            }
        }
        return nil
    }

    static func buildLoop(videoId: String, node: [String: Any], url: String) -> CanvasLoop {
        let rawMime = (node["mimeType"] as? String) ?? (node["contentType"] as? String) ?? ""
        let mime = videoMimeRegex.firstMatch(in: rawMime, range: NSRange(rawMime.startIndex..., in: rawMime)) != nil
            ? rawMime
            : "video/mp4"
        let durationRaw = (node["durationMs"] as? Int) ?? (node["duration"] as? Int) ?? 0
        let durationMs = durationRaw > 0 ? durationRaw : 8_000
        let width = (node["width"] as? Int) ?? 0
        let height = (node["height"] as? Int) ?? 0
        // Some canvas payloads ship a theme/brand color for ambient glow.
        let themeHex = (node["themeColor"] as? String) ?? (node["backgroundColor"] as? String) ?? ""
        let palette = (themeHex.hasPrefix("#") && themeHex.count == 7)
            ? hexToArgb(themeHex)
            : nil
        return CanvasLoop(
            videoId: videoId,
            loopUrl: url,
            mimeType: mime,
            durationMs: durationMs,
            width: width,
            height: height,
            paletteColor: palette
        )
    }

    static func hexToArgb(_ hex: String) -> UInt64? {
        let digits = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard digits.count == 6, let rgb = UInt64(digits, radix: 16) else { return nil }
        return 0xFF00_0000 | rgb
    }

    static func looksLikeCanvasUrl(_ url: String) -> Bool {
        if videoMimeRegex.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)) != nil {
            return true
        }
        // Canvas loops are served from googlevideo with .mp4/.webm fragments.
        if url.contains("googlevideo.com"), url.contains(".mp4") || url.contains(".webm") {
            return true
        }
        return urlHints.contains { url.range(of: $0, options: .caseInsensitive) != nil }
    }

    private static let videoMimeRegex = try! NSRegularExpression(pattern: "video/(mp4|webm)", options: .caseInsensitive)

    /// Visits every node of the JSON tree with its parent key. NOT
    /// throwing, NOT skipping — hostile shapes simply produce fewer
    /// visits.
    static func walk(_ node: Any?, parentKey: String? = nil, visitor: (Any?, String?) -> Void) {
        switch node {
        case let object as [String: Any]:
            for (key, child) in object {
                visitor(child, key)
                walk(child, parentKey: key, visitor: visitor)
            }
        case let array as [Any]:
            for child in array {
                visitor(child, nil)
                walk(child, parentKey: nil, visitor: visitor)
            }
        default:
            visitor(node, parentKey)
        }
    }
}

/// Clips/shorts renderer parser (port of ClipsResponseParser.kt).
enum ClipsResponseParser {

    static let shelfHints = ["clip", "short", "reel"]

    /// Parses an artist `/browse` or watch-next `/next` response for clip
    /// renderers.
    static func parseBrowse(_ root: Any, maxClips: Int) -> [ArtistClip] {
        collectClips(root, maxClips: maxClips)
    }

    static func parseNext(_ root: Any, maxClips: Int) -> [ArtistClip] {
        collectClips(root, maxClips: maxClips)
    }

    /// First artist channel id (UC…) from a search response.
    static func firstArtistChannelId(_ root: Any) -> String? {
        var found: String?
        CanvasResponseParser.walk(root) { node, parentKey in
            guard found == nil else { return }
            guard let object = node as? [String: Any] else { return }
            guard let key = parentKey, key.hasSuffix("Renderer") else { return }
            guard let nav = object["navigationEndpoint"] as? [String: Any],
                  let browseEndpoint = nav["browseEndpoint"] as? [String: Any],
                  let id = browseEndpoint["browseId"] as? String else { return }
            if id.hasPrefix("UC") && id.count == 24 {
                found = id
            }
        }
        return found
    }

    /// First song videoId from a search response.
    static func firstSongVideoId(_ root: Any) -> String? {
        var found: String?
        CanvasResponseParser.walk(root) { node, parentKey in
            guard found == nil else { return }
            guard let object = node as? [String: Any] else { return }
            guard let key = parentKey, key.hasSuffix("Renderer") else { return }
            if let videoId = object["videoId"] as? String, videoId.count == 11 {
                found = videoId
            }
        }
        return found
    }

    /// Two-pass collection: pass 1 harvests Clips/Short-flavored shelf
    /// headers anywhere in the tree (STRICT string titles — a track title
    /// OBJECT must never string-match "short"), pass 2 collects renderers
    /// with the boost already settled (order-independent).
    static func collectClips(_ root: Any, maxClips: Int) -> [ArtistClip] {
        var shelfBoost = false
        CanvasResponseParser.walk(root) { node, _ in
            guard !shelfBoost else { return }
            guard let object = node as? [String: Any] else { return }
            if let title = object["title"] as? String,
               shelfHints.contains(where: { title.range(of: $0, options: .caseInsensitive) != nil }) {
                shelfBoost = true
            }
        }

        var out: [String: ArtistClip] = [:]
        CanvasResponseParser.walk(root) { node, parentKey in
            guard out.count < maxClips else { return }
            guard let object = node as? [String: Any] else { return }

            let clip: ArtistClip?
            switch parentKey {
            case "shortsLockupViewModel":
                clip = parseShortsLockup(object)
            case "reelItemRenderer":
                clip = parseReelItem(object)
            case "clipRenderer":
                clip = parseClipRenderer(object)
            case "shortVideoRenderer" where Self.isShort(object):
                clip = parseShortVideo(object)
            default:
                clip = nil
            }
            guard let clip else { return }

            // Shelf-adjacent renderers always pass; stray short videos only
            // count when a Clips shelf header was seen somewhere.
            if parentKey == "shortVideoRenderer" && !shelfBoost { return }
            if clip.videoId.isEmpty && clip.clipId.isEmpty { return }
            // Kotlin putIfAbsent parity: the FIRST parse of a key wins.
            let dedupKey = clip.clipId.isEmpty ? clip.videoId : clip.clipId
            if out[dedupKey] == nil {
                out[dedupKey] = clip
            }
        }
        return Array(out.values.prefix(maxClips))
    }

    /// Modern Shorts shelf entry.
    static func parseShortsLockup(_ node: [String: Any]) -> ArtistClip? {
        let entityId = node["entityId"] as? String ?? ""
        let content = (node["content"] as? [String: Any]) ?? node
        // videoId is nested inside the onTap innertube command, or
        // recoverable from the thumbnail URL.
        let videoId = extractVideoIdFromNav(content) ?? extractVideoIdFromThumb(content)
        if entityId.isEmpty && videoId == nil { return nil }
        let overlay = content["overlayMetadata"] as? [String: Any]
        let title = (overlay?["primaryText"] as? [String: Any])?["content"] as? String
            ?? (content["metadata"] as? [String: Any])?["title"] as? String
            ?? ""
        let thumb = largestThumbnailUrl(content["image"] as? [String: Any])
        let secondary = (overlay?["secondaryText"] as? [String: Any])?["content"] as? String ?? ""
        let durationSec = parseDurationBadge(secondary) ?? 30
        // Kotlin parity: clipId = entityId, else videoId (both blank was
        // rejected above); videoId falls back to the entityId suffix.
        let clipId: String
        if !entityId.isEmpty {
            clipId = entityId
        } else if let videoId {
            clipId = videoId
        } else {
            return nil
        }
        let resolvedVideoId = videoId ?? String(entityId.split(separator: "/").last ?? "")
        return ArtistClip(
            clipId: clipId,
            videoId: resolvedVideoId,
            title: title,
            thumbnailUrl: thumb,
            durationSec: durationSec,
            viewCountText: stripDurationFromBadge(secondary)
        )
    }

    /// Legacy Shorts shelf entry.
    static func parseReelItem(_ node: [String: Any]) -> ArtistClip? {
        guard let videoId = node["videoId"] as? String, !videoId.isEmpty else { return nil }
        let title = (node["headline"] as? [String: Any])?["simpleText"] as? String ?? ""
        let thumb = largestThumbnailUrl(node["thumbnail"] as? [String: Any])
        return ArtistClip(
            clipId: videoId,
            videoId: videoId,
            title: title,
            thumbnailUrl: thumb,
            durationSec: 30,
            viewCountText: (node["viewCountText"] as? [String: Any])?["simpleText"] as? String ?? ""
        )
    }

    /// Curated watch-next Clip.
    static func parseClipRenderer(_ node: [String: Any]) -> ArtistClip? {
        guard let videoId = node["videoId"] as? String, !videoId.isEmpty else { return nil }
        let titleObject = node["title"] as? [String: Any]
        let accessibilityLabel = ((titleObject?["accessibility"] as? [String: Any])?["accessibilityData"] as? [String: Any])?["label"] as? String
        let title = accessibilityLabel ?? (titleObject?["simpleText"] as? String) ?? ""
        let durationFromText = parseDurationBadge(node["durationText"] as? String ?? "")
            ?? ((node["durationText"] as? [String: Any])).flatMap { $0["simpleText"] as? String }.flatMap(parseDurationBadge)
        let thumb = largestThumbnailUrl(node["thumbnail"] as? [String: Any])
        return ArtistClip(
            clipId: videoId,
            videoId: videoId,
            title: title,
            thumbnailUrl: thumb,
            durationSec: durationFromText ?? 30
        )
    }

    /// shortVideoRenderer only counts when it carries a ≤45 s badge.
    static func parseShortVideo(_ node: [String: Any]) -> ArtistClip? {
        guard let videoId = node["videoId"] as? String, !videoId.isEmpty else { return nil }
        let titleObject = node["title"] as? [String: Any]
        let title = ((titleObject?["runs"] as? [Any])?.first as? [String: Any])?["text"] as? String
            ?? (titleObject?["simpleText"] as? String)
            ?? ""
        let thumb = largestThumbnailUrl(node["thumbnail"] as? [String: Any])
        let lengthText = (node["lengthText"] as? [String: Any])?["simpleText"] as? String ?? ""
        return ArtistClip(
            clipId: videoId,
            videoId: videoId,
            title: title,
            thumbnailUrl: thumb,
            durationSec: parseDurationBadge(lengthText) ?? 30,
            viewCountText: (node["viewCountText"] as? [String: Any])?["simpleText"] as? String ?? ""
        )
    }

    static func isShort(_ node: [String: Any]) -> Bool {
        let badge = (node["lengthText"] as? [String: Any])?["simpleText"] as? String
            ?? (node["lengthText"] as? String)
            ?? ""
        guard let secs = parseDurationBadge(badge) else { return false }
        return (1...45).contains(secs)
    }

    // ── helper decoding ───────────────────────────────────────────────────

    static func extractVideoIdFromNav(_ content: [String: Any]) -> String? {
        let onTap = (content["onTap"] as? [String: Any])
            ?? ((content["rendererContext"] as? [String: Any])?["commandContext"] as? [String: Any])?["onTap"] as? [String: Any]
        guard let onTap else { return nil }
        let watchEndpoint = (onTap["watchEndpoint"] as? [String: Any])
            ?? ((onTap["innertubeCommand"] as? [String: Any])?["watchEndpoint"] as? [String: Any])
        guard let watchEndpoint else { return nil }
        guard let id = watchEndpoint["videoId"] as? String, !id.isEmpty else { return nil }
        return id
    }

    static func extractVideoIdFromThumb(_ content: [String: Any]) -> String? {
        let sources = (content["image"] as? [String: Any])?["sources"] as? [Any]
            ?? (content["thumbnail"] as? [String: Any])?["thumbnails"] as? [Any]
        guard let sources else { return nil }
        for source in sources {
            guard let url = (source as? [String: Any])?["url"] as? String else { continue }
            // /vi/<videoId>/… thumbnails encode the videoId directly.
            if let match = videoIdFromThumbRegex.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)),
               let range = Range(match.range(at: 1), in: url) {
                return String(url[range])
            }
        }
        return nil
    }

    /// The largest (highest-resolution) URL of a thumbnails/sources array.
    static func largestThumbnailUrl(_ image: [String: Any]?) -> String {
        guard let image,
              let sources = (image["sources"] as? [Any]) ?? (image["thumbnails"] as? [Any]) else {
            return ""
        }
        let urls = sources.compactMap { ($0 as? [String: Any])?["url"] as? String }.filter { !$0.isEmpty }
        return urls.max(by: { $0.count < $1.count }) ?? ""
    }

    /// "3:12" / "0:45" / "45s" / "30 sec" → seconds; nil when unparseable.
    /// Unanchored mm:ss so badges embedded in richer overlay text
    /// ("0:32 • 1.2M views") still decode; the seconds form REQUIRES the
    /// 's' suffix so bare numbers inside view counts never match.
    static func parseDurationBadge(_ text: String) -> Int? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let match = mmssRegex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)) {
            let minutes = Int((trimmed as NSString).substring(with: match.range(at: 1))) ?? 0
            let seconds = Int((trimmed as NSString).substring(with: match.range(at: 2))) ?? 0
            return minutes * 60 + seconds
        }
        if let match = secsRegex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)) {
            return Int((trimmed as NSString).substring(with: match.range(at: 1)))
        }
        return nil
    }

    static func stripDurationFromBadge(_ badge: String) -> String {
        var stripped = badge
        stripped = stripRegex.stringByReplacingMatches(
            in: stripped,
            range: NSRange(stripped.startIndex..., in: stripped),
            withTemplate: ""
        )
        // Trim the leading separators the strip leaves behind (Kotlin:
        // ^[^A-Za-z0-9]+ removal).
        while let first = stripped.first, !first.isLetter && !first.isNumber {
            stripped.removeFirst()
        }
        return stripped.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let mmssRegex = try! NSRegularExpression(pattern: #"\b(\d{1,2}):(\d{2})\b"#)
    private static let secsRegex = try! NSRegularExpression(
        pattern: #"\b(\d{1,3})\s*(?:s|sec|secs|second|seconds)\b"#,
        options: .caseInsensitive
    )
    private static let stripRegex = try! NSRegularExpression(
        pattern: #"\d{1,2}:\d{2}|\d+\s*(?:s|sec|secs|second|seconds)\b"#,
        options: .caseInsensitive
    )
    private static let videoIdFromThumbRegex = try! NSRegularExpression(pattern: "/vi/([A-Za-z0-9_-]{11})/")
}

// MARK: - The scraping actor

/// Canvas video loops + artist clips through the InnerTube fleet —
/// SW-ENG1-06. NEVER throws: every entry point returns Optionals.
actor CanvasScraperService {

    /// Canvas loops are immutable per videoId → long SWR TTL.
    static let canvasCacheTtlMs: Int64 = 24 * 60 * 60 * 1000
    /// Clips rails rotate weekly — a 6 h TTL serves the shelf instantly.
    static let clipsCacheTtlMs: Int64 = 6 * 60 * 60 * 1000

    private struct CacheEntry {
        let payload: CachePayload
        let storedAtMs: Int64
    }

    /// The cached value shapes (Kotlin canonical-JSON parity, as native
    /// Swift values — the cache is process-memory only).
    private enum CachePayload {
        case canvas(CanvasLoop)
        case clips(ClipsPage)
    }

    private let session: URLSession
    private let clock: () -> Int64
    private var cache: [String: CacheEntry] = [:]

    init(
        session: URLSession = URLSession(configuration: .ephemeral),
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.session = session
        self.clock = clock
    }

    // MARK: - Canvas loops

    /// Canvas loop for a concrete videoId (SWR-cached 24 h). Tries the
    /// `/player` fleet first, then the `/next` fleet. Nil when the track
    /// simply has no canvas — a normal outcome, not an error.
    func canvas(forVideoId videoId: String) async -> CanvasLoop? {
        let id = videoId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard id.count == 11 else { return nil }
        if let cached = cachedCanvas("canvas_\(id)") {
            return cached
        }
        guard let loop = await resolveCanvasFleet(videoId: id) else { return nil }
        store(.canvas(loop), key: "canvas_\(id)")
        return loop
    }

    /// Canvas loop for an arbitrary query — resolves the query to a
    /// videoId through a SONGS-filtered search, then fetches the canvas.
    func canvas(forQuery query: String) async -> CanvasLoop? {
        let clean = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        guard let videoId = await searchSongVideoId(clean) else { return nil }
        return await canvas(forVideoId: videoId)
    }

    private func resolveCanvasFleet(videoId: String) async -> CanvasLoop? {
        // Transport A: /player auxiliary canvas assets.
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "videoId": videoId,
                "contentCheckOk": true,
            ]
            if let root = await executePost(spec, url: InnerTubeFleet.playerURL, body: json),
               let loop = CanvasResponseParser.parsePlayer(root: root, videoId: videoId) {
                return loop
            }
        }
        // Transport B: /next watch-next player overlays.
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "videoId": videoId,
            ]
            if let root = await executePost(spec, url: InnerTubeFleet.nextURL, body: json),
               let loop = CanvasResponseParser.parseNext(root: root, videoId: videoId) {
                return loop
            }
        }
        return nil
    }

    /// Resolves one SONGS-filtered search videoId for a query (best-effort).
    private func searchSongVideoId(_ query: String) async -> String? {
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "query": query,
                "params": InnerTubeFleet.songsFilterParams,
            ]
            if let root = await executePost(spec, url: InnerTubeFleet.searchURL, body: json),
               let id = CanvasResponseParser.firstSongVideoId(root: root) {
                return id
            }
        }
        return nil
    }

    // MARK: - Artist clips

    /// Clips rail for an artist by name (SWR-cached 6 h). Resolves the
    /// artist browseId through search when not already known.
    func clips(
        forArtist artistName: String,
        browseId: String? = nil,
        maxClips: Int = 12
    ) async -> ClipsPage {
        let clean = artistName.trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty && (browseId ?? "").isEmpty { return ClipsPage() }
        let cacheKey = "clips_\(((browseId ?? clean)).lowercased())"
        if let cached = cachedClips(cacheKey) {
            return cached
        }
        let page = await resolveClipsFleet(artistName: clean, browseId: browseId, maxClips: maxClips)
        store(.clips(page), key: cacheKey)
        return page
    }

    /// Clips discovered from the watch-next tree of a seed track (no
    /// browseId needed — the player-adjacent discovery rail).
    func clips(forSeedTrackVideoId videoId: String, maxClips: Int = 12) async -> ClipsPage {
        let id = videoId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard id.count == 11 else { return ClipsPage() }
        let cacheKey = "clips_seed_\(id)"
        if let cached = cachedClips(cacheKey) {
            return cached
        }
        let page = await resolveSeedClipsFleet(videoId: id, maxClips: maxClips)
        store(.clips(page), key: cacheKey)
        return page
    }

    private func resolveClipsFleet(
        artistName: String,
        browseId: String?,
        maxClips: Int
    ) async -> ClipsPage {
        var channelId = browseId.flatMap { $0.isEmpty ? nil : $0 }
        if channelId == nil {
            channelId = await resolveArtistChannelId(artistName)
        }
        if let channelId {
            // The clips/shorts tab first…
            for spec in InnerTubeFleet.fleet() {
                guard let root = await executeBrowse(spec, browseId: channelId, tab: .clips) else {
                    continue
                }
                let clips = ClipsResponseParser.parseBrowse(root, maxClips: maxClips)
                if !clips.isEmpty {
                    return ClipsPage(artistName: artistName, clips: clips)
                }
            }
            // …then the main (non-shorts) tab — some artists only surface
            // clips there.
            for spec in InnerTubeFleet.fleet() {
                guard let root = await executeBrowse(spec, browseId: channelId, tab: nil) else {
                    continue
                }
                let clips = ClipsResponseParser.parseBrowse(root, maxClips: maxClips)
                if !clips.isEmpty {
                    return ClipsPage(artistName: artistName, clips: clips)
                }
            }
        }
        // Final fallback: watch-next tree of the artist's top track.
        if !artistName.isEmpty {
            if let seedId = await resolveSeedVideoForArtist(artistName) {
                return await resolveSeedClipsFleet(videoId: seedId, maxClips: maxClips)
            }
        }
        return ClipsPage(artistName: artistName)
    }

    private func resolveSeedClipsFleet(videoId: String, maxClips: Int) async -> ClipsPage {
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "videoId": videoId,
            ]
            guard let root = await executePost(spec, url: InnerTubeFleet.nextURL, body: json) else {
                continue
            }
            let clips = ClipsResponseParser.parseNext(root, maxClips: maxClips)
            if !clips.isEmpty {
                return ClipsPage(clips: clips)
            }
        }
        return ClipsPage()
    }

    /// Resolves an artist channel id (UC…) via an artist-filtered search.
    private func resolveArtistChannelId(_ artistName: String) async -> String? {
        guard !artistName.isEmpty else { return nil }
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "query": artistName,
                "params": InnerTubeFleet.songsFilterParams,
            ]
            if let root = await executePost(spec, url: InnerTubeFleet.searchURL, body: json),
               let id = ClipsResponseParser.firstArtistChannelId(root) {
                return id
            }
        }
        return nil
    }

    /// Resolves one seed track videoId for an artist (SONGS top result).
    private func resolveSeedVideoForArtist(_ artistName: String) async -> String? {
        guard !artistName.isEmpty else { return nil }
        for spec in InnerTubeFleet.fleet() {
            let json: [String: Any] = [
                "context": contextObject(spec),
                "query": artistName,
                "params": InnerTubeFleet.songsFilterParams,
            ]
            if let root = await executePost(spec, url: InnerTubeFleet.searchURL, body: json),
               let id = ClipsResponseParser.firstSongVideoId(root) {
                return id
            }
        }
        return nil
    }

    // MARK: - InnerTube transports

    private func contextObject(_ spec: InnerTubeClientSpec) -> [String: Any] {
        var client: [String: Any] = [
            "clientName": spec.clientName,
            "clientVersion": spec.clientVersion,
            "hl": "en",
            "gl": "US",
        ]
        for (key, value) in spec.contextExtras {
            client[key] = value
        }
        return ["context": ["client": client]]
    }

    private enum BrowseTab {
        case clips
    }

    private func executeBrowse(
        _ spec: InnerTubeClientSpec,
        browseId: String,
        tab: BrowseTab?
    ) async -> [String: Any]? {
        var json: [String: Any] = contextObject(spec)
        json["browseId"] = browseId
        if tab != nil {
            json["params"] = InnerTubeFleet.clipsTabParams
        }
        return await executePost(spec, url: InnerTubeFleet.browseURL, body: json)
    }

    /// Executes one POST; returns the parsed JSON root or nil on ANY
    /// failure (non-2xx, transport error, unparseable body) — the fleet
    /// advances silently, exactly like the Kotlin lane.
    private func executePost(
        _ spec: InnerTubeClientSpec,
        url: URL,
        body: [String: Any]
    ) async -> [String: Any]? {
        guard let payload = try? JSONSerialization.data(withJSONObject: body) else {
            return nil
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = payload
        request.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue(spec.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        // Accept-Encoding is deliberately NOT set: URLSession negotiates
        // gzip itself and transparently decodes it (the Kotlin lane needed
        // a GZIPInputStream branch for the same effect).
        request.setValue(spec.clientHeaderId, forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue(spec.clientVersion, forHTTPHeaderField: "X-YouTube-Client-Version")
        request.setValue(spec.origin, forHTTPHeaderField: "Origin")
        request.setValue("\(spec.origin)/", forHTTPHeaderField: "Referer")

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            guard !data.isEmpty else { return nil }
            return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        } catch {
            return nil
        }
    }

    // MARK: - In-memory SWR cache

    private func cachedCanvas(_ key: String) -> CanvasLoop? {
        guard let entry = cache[key],
              clock() - entry.storedAtMs < Self.canvasCacheTtlMs,
              case .canvas(let loop) = entry.payload else {
            return nil
        }
        return CanvasLoop(
            videoId: loop.videoId,
            loopUrl: loop.loopUrl,
            mimeType: loop.mimeType,
            durationMs: loop.durationMs,
            width: loop.width,
            height: loop.height,
            paletteColor: loop.paletteColor,
            fromCache: true
        )
    }

    private func cachedClips(_ key: String) -> ClipsPage? {
        guard let entry = cache[key],
              clock() - entry.storedAtMs < Self.clipsCacheTtlMs,
              case .clips(let page) = entry.payload else {
            return nil
        }
        var cached = page
        cached.fromCache = true
        return cached
    }

    private func store(_ payload: CachePayload, key: String) {
        cache[key] = CacheEntry(payload: payload, storedAtMs: clock())
    }
}
