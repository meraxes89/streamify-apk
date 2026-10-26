use streamify_core_rs::resolver::StreamResolver;

fn main() {
    println!("=== [DEMO 2] Streamify Rust: Spotify Playlist Scraper & Importer ===");

    // Public Spotify playlist test URL (Today's Top Hits / Global playlist)
    let spotify_url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M";
    println!("\n📥 Scraping Spotify playlist: {}", spotify_url);

    match StreamResolver::import_spotify_tracks(spotify_url) {
        Ok(tracks) => {
            println!(
                "  ✅ Successfully extracted {} tracks without Spotify API credentials!",
                tracks.len()
            );
            for (i, t) in tracks.iter().take(5).enumerate() {
                println!(
                    "    [{}] '{}' by {} ({}s)",
                    i + 1,
                    t.title,
                    t.artist,
                    t.duration_sec
                );
            }
        }
        Err(e) => {
            println!("  ⚠️ Note: Spotify embed scrape status: {}", e);
        }
    }

    println!("\n=== [DEMO 2] Completed! ===");
}
