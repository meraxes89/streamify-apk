use std::fs::File;
use std::io::Write;
use streamify_core_rs::tagger::AudioMetadataEngine;

fn main() {
    println!("=== [DEMO 3] Streamify Rust: Audio Metadata Tagger & ID3 Injection ===");

    // Create a minimal valid PCM WAV file for testing tagger
    let sample_rate = 44100u32;
    let num_samples = 44100u32; // 1 second
    let byte_rate = sample_rate * 2;
    let data_len = num_samples * 2;
    let file_len = 36 + data_len;

    let mut wav_bytes = Vec::new();
    wav_bytes.extend_from_slice(b"RIFF");
    wav_bytes.extend_from_slice(&file_len.to_le_bytes());
    wav_bytes.extend_from_slice(b"WAVEfmt ");
    wav_bytes.extend_from_slice(&16u32.to_le_bytes()); // Subchunk1Size
    wav_bytes.extend_from_slice(&1u16.to_le_bytes()); // AudioFormat (PCM)
    wav_bytes.extend_from_slice(&1u16.to_le_bytes()); // NumChannels (1)
    wav_bytes.extend_from_slice(&sample_rate.to_le_bytes());
    wav_bytes.extend_from_slice(&byte_rate.to_le_bytes());
    wav_bytes.extend_from_slice(&2u16.to_le_bytes()); // BlockAlign
    wav_bytes.extend_from_slice(&16u16.to_le_bytes()); // BitsPerSample
    wav_bytes.extend_from_slice(b"data");
    wav_bytes.extend_from_slice(&data_len.to_le_bytes());
    wav_bytes.resize(44 + data_len as usize, 0);

    let temp_audio_path = "/tmp/streamify_test_track.wav";
    {
        let mut file = File::create(temp_audio_path).expect("Failed to create temp audio file");
        file.write_all(&wav_bytes)
            .expect("Failed to write audio bytes");
    }

    println!(
        "\n📝 1. Writing metadata via Rust Lofty engine into '{}'...",
        temp_audio_path
    );
    let title = "Starboy (Streamify Remaster)";
    let artist = "The Weeknd ft. Daft Punk";
    let album = "Starboy (Deluxe)";
    let synced_lyrics = "[00:01.00] I'm tryna put you in the worst mood, ah\n[00:04.50] P1 cleaner than your church shoes, ah";

    let write_result = AudioMetadataEngine::write_metadata(
        temp_audio_path,
        title,
        artist,
        album,
        None,
        Some(synced_lyrics),
    );

    match write_result {
        Ok(meta) => {
            println!("  ✅ Metadata successfully injected!");
            println!("    - Title: {}", meta.title);
            println!("    - Artist: {}", meta.artist);
            println!("    - Album: {}", meta.album);
            println!("    - Duration: {}s", meta.duration_sec);
            println!("    - Lyrics: {:?}", meta.lyrics);
        }
        Err(e) => println!("  ❌ Write error: {}", e),
    }

    println!("\n🔍 2. Reading back metadata from disk using Rust Lofty probe...");
    match AudioMetadataEngine::read_metadata(temp_audio_path) {
        Ok(read_meta) => {
            println!("  ✅ Verification Successful! Read back from file:");
            println!("    - Title: {}", read_meta.title);
            println!("    - Artist: {}", read_meta.artist);
            println!("    - Album: {}", read_meta.album);
            println!("    - Duration: {}s", read_meta.duration_sec);
            assert_eq!(read_meta.title, title);
            assert_eq!(read_meta.artist, artist);
        }
        Err(e) => println!("  ❌ Read error: {}", e),
    }

    println!("\n=== [DEMO 3] Completed Successfully! ===");
}
