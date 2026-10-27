//! LIVE DIAGNOSTIC: runs the exact production resolution path
//! (`resolver::fetch_innertube_cdn`) against real YouTube, then probes the
//! returned googlevideo URL for streamability under two User-Agents:
//!   A) the browser UA PlaybackService's HttpDataSource actually sends
//!   B) the ANDROID_MUSIC app UA matching the Innertube client used
//! This proves or kills the "native tier returns 403-dead URLs" theory.

use streamify_core_rs::resolver::{fetch_innertube_cdn, get_client};

const VIDEO_ID: &str = "jNQXAC9IVRw"; // "Me at the zoo" — most stable video on YT

/// Probe helper (multiplatform Phase 3 / MP-C14: reqwest over the shared
/// client — the ad-hoc blocking ureq call is retired).
async fn probe(client: &reqwest::Client, url: &str, ua: &str, label: &str) {
    match client
        .get(url)
        .header("User-Agent", ua)
        .header("Range", "bytes=0-1023")
        .send()
        .await
    {
        Ok(resp) => {
            let ct = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("?");
            let len = resp
                .headers()
                .get(reqwest::header::CONTENT_LENGTH)
                .or_else(|| resp.headers().get(reqwest::header::CONTENT_RANGE))
                .and_then(|v| v.to_str().ok())
                .unwrap_or("?");
            println!(
                "  [{label}] status={} content-type={ct} range/len={len}",
                resp.status()
            );
            let _ = resp.text().await.unwrap_or_default();
        }
        Err(e) => println!("  [{label}] transport error: {e}"),
    }
}

fn main() {
    let rt = streamify_core_rs::resolver::get_runtime();
    let client = get_client();

    rt.block_on(async {
        println!("═══ 1. fetch_innertube_cdn(\"{VIDEO_ID}\") — production Tier-1 path ═══");
        match fetch_innertube_cdn(client, VIDEO_ID, "", "").await {
            Ok(url) => {
                println!("RESOLVED OK ({}) bytes", url.len());
                // Show host + key params without dumping tokens fully
                let host = url.split('/').nth(2).unwrap_or("?");
                println!("host: {host}");
                for probe_param in ["itag", "mime", "c=", "pot=", "ratebypass"] {
                    if let Some(idx) = url.find(probe_param) {
                        let snippet = &url[idx..(idx + 40).min(url.len())];
                        println!("  param: {}…", snippet.split('&').next().unwrap_or(""));
                    }
                }

                println!("\n═══ 2. Streamability probe of the resolved URL ═══");
                let browser_ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
                let android_ua = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14) gzip";
                probe(client, &url, browser_ua, "A: browser UA (= ExoPlayer factory)").await;
                probe(client, &url, android_ua, "B: android-music UA").await;
            }
            Err(e) => println!("FETCH FAILED: {e}"),
        }

        println!("\n═══ 3. innertube_search_candidates (Tier-3 path) ═══");
        let cands =
            streamify_core_rs::resolver::innertube_search_candidates("never gonna give you up", "", "").await;
        println!("candidates: {}", cands.len());
        for c in cands.iter().take(3) {
            println!(
                "  {} | {} | {} | {}s",
                c.video_id, c.title, c.artist, c.duration_sec
            );
        }
    });
}
