//! END-TO-END test of the PRODUCTION anonymous path (resolve_stream_master).
use streamify_core_rs::resolver::{get_client, resolve_stream_master};

fn main() {
    let client = get_client();
    let rt = streamify_core_rs::resolver::get_runtime();
    for vid in ["jNQXAC9IVRw", "dQw4w9WgXcQ"] {
        print!("▶ {vid}: ");
        rt.block_on(async {
            match resolve_stream_master(client, vid, "", "").await {
                Ok(url) => {
                    println!("RESOLVED ({}b)", url.len());
                    // live stream probe like ExoPlayer
                    let mut req = ureq::get(&url).set("Range", "bytes=0-262143");
                    match req.call() {
                        Ok(r) => {
                            let st = r.status();
                            let ct = r.content_type().to_string();
                            let mut reader = r.into_reader();
                            let mut tmp = vec![0u8; 16384];
                            let mut n = 0usize;
                            while n < 262_144 {
                                match std::io::Read::read(&mut reader, &mut tmp) {
                                    Ok(0) | Err(_) => break,
                                    Ok(k) => n += k,
                                }
                            }
                            println!(
                                "   ↳ STREAM {st} ct={ct} got={n}B {}",
                                if n > 100_000 { "✅" } else { "⚠️" }
                            );
                        }
                        Err(ureq::Error::Status(c, _)) => println!("   ↳ STREAM HTTP {c} ❌"),
                        Err(e) => println!("   ↳ STREAM ERR {e}"),
                    }
                }
                Err(e) => println!("FAILED: {e}"),
            }
        });
    }
}
