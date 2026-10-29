use streamify_resolver::resolver::StreamResolver;

fn main() {
    println!("=== [DEMO 1] Streamify Rust: CDN Stream Resolver & YouTube Music Search ===");

    // 1. Search YouTube Music for a track
    let query = "Blinding Lights The Weeknd";
    println!("\n🔍 1. Searching YouTube Music for: '{}'...", query);
    match StreamResolver::search_music(query, 3) {
        Ok(results) => {
            println!("  ✅ Found {} candidates:", results.len());
            for (i, track) in results.iter().enumerate() {
                println!(
                    "    [{}] Title: {} | Artist: {} | VideoId: {} | Duration: {}s",
                    i + 1,
                    track.title,
                    track.artist,
                    track.id,
                    track.duration_sec
                );
            }

            if let Some(top) = results.first() {
                // 2. Resolve direct playable CDN streams
                println!(
                    "\n⚡ 2. Resolving direct playable CDN stream URLs for VideoId: '{}'...",
                    top.id
                );
                match StreamResolver::resolve_stream(&top.id) {
                    Ok(streams) => {
                        println!(
                            "  ✅ Successfully resolved {} stream format(s):",
                            streams.len()
                        );
                        for (j, s) in streams.iter().take(3).enumerate() {
                            println!(
                                "    [{}] Mime: {} | Bitrate: {} bps | Duration: {}s | IsAudioOnly: {}",
                                j + 1,
                                s.mime_type,
                                s.bitrate,
                                s.duration_sec,
                                s.is_audio_only
                            );
                            println!(
                                "        URL Preview: {}...",
                                s.url.chars().take(80).collect::<String>()
                            );
                        }
                    }
                    Err(e) => println!("  ❌ Stream resolution error: {}", e),
                }
            }
        }
        Err(e) => println!("  ❌ Search error: {}", e),
    }

    println!("\n=== [DEMO 1] Completed Successfully! ===");
}
