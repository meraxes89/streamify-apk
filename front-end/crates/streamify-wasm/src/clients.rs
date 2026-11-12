//! YouTube client recipes for the wasm facade (W0.4).
//!
//! Drift between this table and the engine surfaces it mirrors is a BUG.
//! Sources, in precedence order:
//!  - Tier-5 production anonymous player path: `rust/crates/streamify-
//!    resolver/src/resolver.rs` (`fetch_stream_anonymous`) — ANDROID_VR
//!    1.65.10. The watch-page warm-up is deliberately skipped in P0: the
//!    probe methodology (`tools/probe-clients.sh`, the W0.1 evidence base
//!    and the resolver-canary CI) does no warm-up and the fleet survives
//!    on standard content; `signatureTimestamp` is pinned from the
//!    canary-maintained `fleet-config.json`.
//!  - Music search: `tools/probe-search-resolver.sh` (the end-to-end-proven
//!    W0.1 recipe) + `resolver.rs::legacy_innertube_search_candidates`.
//!  - Fleet ladder + client versions/UAs: `fleet-config.json` (audioClients)
//!    and `tools/probe-clients.sh` rows.

/// SEC-07: this key ships in every YouTube Music web client and is
/// `.gitleaks.toml`-allowlisted (`public-innertube-web-client-key`) as
/// non-secret. Do NOT copy any other InnerTube key here — the android
/// client key was purged by SEC-07 and a unit test pins its absence.
pub const INNER_TUBE_WEB_CLIENT_KEY: &str = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk";

/// `fleet-config.json` `signatureTimestamp` (canary-maintained; 19850 as of
/// version 20261025). The engine's Tier-5 warms this per-session from the
/// watch page; the probe path pins the fleet constant.
pub const SIGNATURE_TIMESTAMP: u32 = 19850;

// ───────────────────────── music search (WEB_REMIX) ─────────────────────────

/// Engine legacy search posts with `?key=<web client key>` appended.
pub const SEARCH_URL: &str = "https://music.youtube.com/youtubei/v1/search";
pub const SEARCH_CLIENT_NUMBER: &str = "67"; // WEB_REMIX
pub const SEARCH_CLIENT_NAME: &str = "WEB_REMIX";
/// fleet-config.json `searchClients.musicVersion` (probe-proven W0.1).
pub const SEARCH_CLIENT_VERSION: &str = "1.20240101.01.00";
/// Songs filter param — `tools/probe-search-resolver.sh` +
/// `resolver.rs::legacy_search_candidates`.
pub const SEARCH_PARAMS: &str = "egWKAQIIAWoMEAMQBBAJEAoQBRAV";

/// JSON body for the WEB_REMIX music search (engine legacy shape).
pub fn search_body(query: &str) -> String {
    serde_json::json!({
        "context": {
            "client": {
                "clientName": SEARCH_CLIENT_NAME,
                "clientVersion": SEARCH_CLIENT_VERSION,
                "hl": "en",
                "gl": "US"
            }
        },
        "query": query,
        "params": SEARCH_PARAMS
    })
    .to_string()
}

// ───────────────────────────── player ladder ────────────────────────────────

pub const PLAYER_URL: &str = "https://www.youtube.com/youtubei/v1/player";

/// Tier-5 VR client (resolver.rs `fetch_stream_anonymous`, verbatim).
pub const VR_UA: &str = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
/// fleet-config.json ANDROID row (matches tools/probe-clients.sh ANDROID).
pub const ANDROID_UA: &str = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip";
/// fleet-config.json IOS row (matches tools/probe-clients.sh IOS).
pub const IOS_UA: &str =
    "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";

/// Player clients in engine Tier order: ANDROID_VR 1.65.10 (Tier-5 production
/// anonymous) first, then the fleet ladder (ANDROID, IOS).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayerClient {
    Vr,
    Android,
    Ios,
}

impl PlayerClient {
    pub const LADDER: [PlayerClient; 3] = [PlayerClient::Vr, PlayerClient::Android, PlayerClient::Ios];

    pub fn label(self) -> &'static str {
        match self {
            PlayerClient::Vr => "ANDROID_VR 1.65.10",
            PlayerClient::Android => "ANDROID 21.26.364",
            PlayerClient::Ios => "IOS 21.26.4",
        }
    }

    /// `X-YouTube-Client-Name` header value (probe-clients.sh clientNumber).
    pub fn client_number(self) -> &'static str {
        match self {
            PlayerClient::Vr => "28",
            PlayerClient::Android => "3",
            PlayerClient::Ios => "5",
        }
    }

    pub fn client_version(self) -> &'static str {
        match self {
            PlayerClient::Vr => "1.65.10",
            PlayerClient::Android => "21.26.364",
            PlayerClient::Ios => "21.26.4",
        }
    }

    /// Outbound User-Agent (forwarded via the relay's `X-Streamify-UA` swap —
    /// browsers forbid setting `User-Agent` on fetch; the relay rewrites it).
    pub fn user_agent(self) -> &'static str {
        match self {
            PlayerClient::Vr => VR_UA,
            PlayerClient::Android => ANDROID_UA,
            PlayerClient::Ios => IOS_UA,
        }
    }

    /// Player request body. VR mirrors `resolver.rs` Tier-5 verbatim
    /// (including `playbackContext`); ANDROID mirrors the probe-search
    /// resolution shape with fleet device fields; IOS mirrors the
    /// probe-clients.sh IOS row.
    pub fn body(self, video_id: &str) -> String {
        match self {
            PlayerClient::Vr => serde_json::json!({
                "context": { "client": {
                    "clientName": "ANDROID_VR",
                    "clientVersion": "1.65.10",
                    "deviceMake": "Oculus",
                    "deviceModel": "Quest 3",
                    "androidSdkVersion": 32,
                    "userAgent": VR_UA,
                    "osName": "Android",
                    "osVersion": "12L",
                    "hl": "en",
                    "timeZone": "UTC",
                    "utcOffsetMinutes": 0
                }},
                "videoId": video_id,
                "playbackContext": { "contentPlaybackContext": {
                    "html5Preference": "HTML5_PREF_WANTS",
                    "signatureTimestamp": SIGNATURE_TIMESTAMP
                }},
                "contentCheckOk": true,
                "racyCheckOk": true
            }),
            PlayerClient::Android => serde_json::json!({
                "context": { "client": {
                    "clientName": "ANDROID",
                    "clientVersion": "21.26.364",
                    "androidSdkVersion": 30,
                    "osName": "Android",
                    "osVersion": "11",
                    "hl": "en",
                    "gl": "US"
                }},
                "videoId": video_id,
                "contentCheckOk": true,
                "racyCheckOk": true
            }),
            PlayerClient::Ios => serde_json::json!({
                "context": { "client": {
                    "clientName": "IOS",
                    "clientVersion": "21.26.4",
                    "deviceMake": "Apple",
                    "deviceModel": "iPhone16,2",
                    "osName": "iPhone",
                    "osVersion": "18.3.2.22D82",
                    "hl": "en",
                    "gl": "US"
                }},
                "videoId": video_id,
                "contentCheckOk": true,
                "racyCheckOk": true
            }),
        }
        .to_string()
    }
}
