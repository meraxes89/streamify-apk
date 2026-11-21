# `data/native/` — Native Metadata Tagging

## Files

| File | What it is |
|---|---|
| `NativeMetadataTagger.kt` | Kotlin surface for the Rust Lofty tagger: writes ID3/Vorbis metadata + embeds artwork into downloaded files (`TaggedAudioResult`). |

The actual tagging happens in `rust/` (`tagger.rs`); this file only
marshals arguments over JNI. The JNI entry class itself is pinned at
`data/NativeBridge.kt` — see `data/README.md`.
