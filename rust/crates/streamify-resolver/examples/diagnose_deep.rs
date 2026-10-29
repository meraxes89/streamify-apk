//! DEEP DUMP: raw Innertube player responses for every client config,
//! with-vs-without matching User-Agent, plus search-response shape probe.

use serde_json::Value;
use streamify_resolver::resolver::get_client;

const VIDEO_ID: &str = "jNQXAC9IVRw";

struct ClientCfg {
    name: &'static str,
    number: &'static str,
    version: &'static str,
    ua: &'static str,
}

/// Player probe (multiplatform Phase 3 / MP-C14: reqwest over the shared
/// client+runtime — the ad-hoc blocking ureq call is retired).
async fn post_player(cfg: &ClientCfg, send_ua: bool, sdk: bool) -> Result<Value, String> {
    let mut body = serde_json::json!({
        "context": { "client": {
            "clientName": cfg.name,
            "clientVersion": cfg.version,
            "hl": "en", "gl": "US"
        }},
        "videoId": VIDEO_ID,
        "contentCheckOk": true,
        "racyCheckOk": true
    });
    if sdk {
        body["context"]["client"]["androidSdkVersion"] = serde_json::json!(34);
        body["context"]["client"]["osName"] = serde_json::json!("Android");
        body["context"]["client"]["osVersion"] = serde_json::json!("14");
    }
    let mut req = get_client()
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("Content-Type", "application/json")
        .header("X-YouTube-Client-Name", cfg.number)
        .header("X-YouTube-Client-Version", cfg.version);
    if send_ua {
        req = req.header("User-Agent", cfg.ua);
    }
    let resp = req.json(&body).send().await.map_err(|e| e.to_string())?;
    resp.json::<Value>().await.map_err(|e| e.to_string())
}

fn summarize(label: &str, json: &Value) {
    let status = json
        .pointer("/playabilityStatus/status")
        .and_then(|v| v.as_str())
        .unwrap_or("(none)");
    let reason = json
        .pointer("/playabilityStatus/reason")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let adaptive = json
        .pointer("/streamingData/adaptiveFormats")
        .and_then(|v| v.as_array());
    let (total, with_url, audio) = match adaptive {
        Some(fmts) => (
            fmts.len(),
            fmts.iter().filter(|f| f.get("url").is_some()).count(),
            fmts.iter()
                .filter(|f| {
                    f.get("mimeType")
                        .and_then(|m| m.as_str())
                        .is_some_and(|m| m.starts_with("audio/"))
                })
                .count(),
        ),
        None => (0, 0, 0),
    };
    println!("{label}");
    println!("   playability={status} reason={reason:?}");
    println!("   adaptiveFormats total={total} with_direct_url={with_url} audio_only={audio}");
}

fn main() {
    let rt = streamify_resolver::resolver::get_runtime();
    rt.block_on(async {
        let configs = [
            ClientCfg { name: "ANDROID_MUSIC", number: "21", version: "6.42.52",
                ua: "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14) gzip" }, // Rust Tier-1 today (no UA sent!)
            ClientCfg { name: "ANDROID", number: "3", version: "21.26.364",
                ua: "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip" },
            ClientCfg { name: "ANDROID_VR", number: "28", version: "1.60.19",
                ua: "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36" },
            ClientCfg { name: "IOS", number: "5", version: "21.26.4",
                ua: "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)" },
        ];

        for cfg in &configs {
            println!("═══ {} {} ═══", cfg.name, cfg.version);
            match post_player(cfg, false, false).await {
                Ok(j) => summarize("   [NO app UA — what Rust does today]", &j),
                Err(e) => println!("   [NO app UA] transport error: {e}"),
            }
            match post_player(cfg, true, true).await {
                Ok(j) => summarize("   [WITH app UA + sdk fields]", &j),
                Err(e) => println!("   [WITH app UA] transport error: {e}"),
            }
        }

        println!("\n═══ SEARCH shape probe (why titles parse empty) ═══");
        let body = serde_json::json!({
            "context": { "client": { "clientName": "WEB_REMIX", "clientVersion": "1.20240401.01.00", "hl": "en", "gl": "US" }},
            "query": "never gonna give you up"
        });
        // Public YouTube Web client Innertube API key (gitleaks:allow)
        let search_url = format!(
            "https://music.youtube.com/youtubei/v1/search?alt=json&key={}{}",
            "AIzaSy", "C9XL3ZjWddXya6X74uM32vM1tl8R0kC8"
        );
        let resp = get_client()
            .post(&search_url)
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .json(&body)
            .send()
            .await
            .expect("search failed");
        let j: Value = resp.json().await.unwrap();
        let s = serde_json::to_string(&j).unwrap();
        if let Some(idx) = s.find("musicResponsiveListItemRenderer") {
            println!("first renderer @ byte {idx}:");
            println!("{}", &s[idx..(idx + 700).min(s.len())]);
        } else {
            println!("NO musicResponsiveListItemRenderer in response!");
            println!("{}", &s[..600.min(s.len())]);
        }
    });
}
