//! LIVE DIAGNOSTIC: runs the exact production resolution path
//! (`resolver::fetch_innertube_cdn`) against real YouTube, then probes the
//! returned googlevideo URL for streamability under two User-Agents:
//!   A) the browser UA PlaybackService's HttpDataSource actually sends
//!   B) the ANDROID_MUSIC app UA matching the Innertube client used
//! This proves or kills the "native tier returns 403-dead URLs" theory.

use streamify_core_rs::resolver::{fetch_innertube_cdn, get_client};

const VIDEO_ID: &str = "jNQXAC9IVRw"; // "Me at the zoo" — most stable video on YT

fn probe(url: &str, ua: &str, label: &str) {
    match ureq::get(url)
        .set("User-Agent", ua)
        .set("Range", "bytes=0-1023")
        .timeout(std::time::Duration::from_secs(20))
        .call()
    {
        Ok(resp) => {
            let ct = resp.content_type();
            let len = resp
                .header("Content-Length")
                .or_else(|| resp.header("content-range"))
                .unwrap_or("?")
                .to_string();
            println!(
                "  [{label}] status={} content-type={ct} range/len={len}",
                resp.status()
            );
            let _ = resp.into_string().unwrap_or_default();
        }
        Err(ureq::Error::Status(code, resp)) => {
            println!(
                "  [{label}] HTTP {code} content-type={}",
                resp.content_type()
            );
            let _ = resp.into_string().unwrap_or_default();
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
                probe(&url, browser_ua, "A: browser UA (= ExoPlayer factory)");
                probe(&url, android_ua, "B: android-music UA");
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
