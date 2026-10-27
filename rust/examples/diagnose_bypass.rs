//! Bypass-client probe: which Innertube client still serves adaptiveFormats
//! without login on today's enforcement?

use serde_json::Value;
use streamify_core_rs::resolver::get_client;

const VIDEO_ID: &str = "jNQXAC9IVRw";

/// Client probe (multiplatform Phase 3 / MP-C14: reqwest over the shared
/// client+runtime — the ad-hoc blocking ureq call is retired).
async fn try_client(label: &str, url: &str, body: Value, headers: &[(&str, &str)]) {
    let client = get_client();
    let mut req = client.post(url).header("Content-Type", "application/json");
    for (k, v) in headers {
        req = req.header(*k, *v);
    }
    match req.json(&body).send().await {
        Ok(resp) => {
            let j: Value = resp.json().await.unwrap_or(Value::Null);
            let status = j
                .pointer("/playabilityStatus/status")
                .and_then(|v| v.as_str())
                .unwrap_or("(none)");
            let reason = j
                .pointer("/playabilityStatus/reason")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let fmts = j
                .pointer("/streamingData/adaptiveFormats")
                .and_then(|v| v.as_array());
            let (total, with_url, audio_best): (usize, usize, u64) = match fmts {
                Some(f) => (
                    f.len(),
                    f.iter().filter(|x| x.get("url").is_some()).count(),
                    f.iter()
                        .filter(|x| x.get("url").is_some())
                        .filter(|x| {
                            x.get("mimeType")
                                .and_then(|m| m.as_str())
                                .is_some_and(|m| m.starts_with("audio/"))
                        })
                        .map(|x| x.get("bitrate").and_then(|b| b.as_u64()).unwrap_or(0))
                        .max()
                        .unwrap_or(0),
                ),
                None => (0, 0, 0),
            };
            println!("{label}");
            println!("   playability={status} reason={reason:?} formats(total={total}, direct_url={with_url}) best_audio_bitrate={audio_best}");
        }
        Err(e) => println!("{label}\n   transport error: {e}"),
    }
}

fn main() {
    let rt = streamify_core_rs::resolver::get_runtime();
    rt.block_on(async {
        let base = serde_json::json!({
            "videoId": VIDEO_ID,
            "contentCheckOk": true,
            "racyCheckOk": true
        });

        // 1. TVHTML5_SIMPLY_EMBEDDED_PLAYER — classic no-login bypass
        let mut b = base.clone();
        b["context"]["client"]["clientName"] = serde_json::json!("TVHTML5_SIMPLY_EMBEDDED_PLAYER");
        b["context"]["client"]["clientVersion"] = serde_json::json!("2.0");
        b["context"]["thirdParty"] = serde_json::json!({"embedUrl": "https://www.youtube.com/"});
        try_client(
            "── TVHTML5_SIMPLY_EMBEDDED_PLAYER 2.0",
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            b,
            &[(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
            )],
        )
        .await;

        // 2. WEB with full browser headers
        let mut b = base.clone();
        b["context"]["client"]["clientName"] = serde_json::json!("WEB");
        b["context"]["client"]["clientVersion"] = serde_json::json!("2.20250312.04.00");
        try_client(
            "── WEB 2.20250312 (browser headers)",
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            b,
            &[
                ("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"),
                ("Origin", "https://www.youtube.com"),
                ("Referer", "https://www.youtube.com/watch?v=jNQXAC9IVRw"),
            ],
        )
        .await;

        // 3. MWEB
        let mut b = base.clone();
        b["context"]["client"]["clientName"] = serde_json::json!("MWEB");
        b["context"]["client"]["clientVersion"] = serde_json::json!("2.20250311.03.00");
        try_client(
            "── MWEB 2.20250311",
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            b,
            &[("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1")],
        )
        .await;

        // 4. WEB_REMIX (music) with browser UA + Origin — what search used successfully
        let mut b = base.clone();
        b["context"]["client"]["clientName"] = serde_json::json!("WEB_REMIX");
        b["context"]["client"]["clientVersion"] = serde_json::json!("1.20240401.01.00");
        try_client(
            "── WEB_REMIX 1.20240401",
            "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            b,
            &[
                ("Origin", "https://music.youtube.com"),
                ("Referer", "https://music.youtube.com/"),
            ],
        )
        .await;
    });
}
