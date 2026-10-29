//! END-TO-END test of the PRODUCTION anonymous path (resolve_stream_master).
use streamify_resolver::resolver::{get_client, resolve_stream_master};

fn main() {
    let client = get_client();
    let rt = streamify_resolver::resolver::get_runtime();
    for vid in ["jNQXAC9IVRw", "dQw4w9WgXcQ"] {
        print!("▶ {vid}: ");
        rt.block_on(async {
            match resolve_stream_master(client, vid, "", "").await {
                Ok(url) => {
                    println!("RESOLVED ({}b)", url.len());
                    // live stream probe like ExoPlayer (multiplatform Phase 3
                    // / MP-C14: reqwest over the shared client+runtime — the
                    // ad-hoc blocking ureq call is retired).
                    match client
                        .get(&url)
                        .header("Range", "bytes=0-262143")
                        .send()
                        .await
                    {
                        Ok(r) => {
                            let st = r.status();
                            let ct = r
                                .headers()
                                .get(reqwest::header::CONTENT_TYPE)
                                .and_then(|v| v.to_str().ok())
                                .unwrap_or("?")
                                .to_string();
                            // Cap the probe at 256 KiB.
                            let mut n = 0usize;
                            let mut resp = r;
                            while n < 262_144 {
                                match resp.chunk().await {
                                    Ok(Some(chunk)) => n += chunk.len(),
                                    Ok(None) | Err(_) => break,
                                }
                            }
                            println!(
                                "   ↳ STREAM {st} ct={ct} got={n}B {}",
                                if n > 100_000 { "✅" } else { "⚠️" }
                            );
                        }
                        Err(e) => println!("   ↳ STREAM ERR {e}"),
                    }
                }
                Err(e) => println!("FAILED: {e}"),
            }
        });
    }
}
