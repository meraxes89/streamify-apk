# Streamify Crypto Specification (SEC-06)

**Status:** implemented (Horizon 0, CORE) · **Supersedes:** the repeating-XOR constructions flagged in the lead audit (R1.2) · **Scope:** offline vault (`streamify-storage/src/crypto.rs`) and audio-cache chunk cipher (`streamify-ffi/src/cache.rs`).

---

## 1. Threat model

The device-local caches protected here are **confidentiality-sensitive, integrity-critical**:

- An attacker with the vault file (offline backup, cloud sync, adb pull) must not be able to recover audio payloads. The v1 repeating-XOR keystream was recoverable from a single known audio header (deterministic, no nonce) — a known-plaintext break.
- An attacker able to modify cache files (shared storage, backup tampering) must not be able to inject modified payloads: the v1 XOR path decoded garbage silently; the v1 HMAC covered only the vault, and the cache chunk cipher had **no** integrity at all.

Not in scope: key storage (per-device keys come from the OS keychain, resp5 §2.5 — unchanged), and at-rest metadata (filenames/sizes remain visible; that is a deliberate trade for streaming performance).

## 2. Constructions

Both formats use **ChaCha20-Poly1305 AEAD** (RFC 8439) — the `chacha20poly1305` crate that was declared in the workspace since 2026-08 but consumed by zero crates until this change. Encrypt-then-authenticate is inherent to the AEAD: no unauthenticated plaintext is ever produced.

### 2.1 Offline vault — format v2

| Field | Size | Notes |
|---|---|---|
| magic | 18 B | `STREAMIFY_VAULT_V2` (format sniffer for the migration reader) |
| record × N | var | one per ≤ 64 KiB source chunk, until EOF |

record := `ct_len: u32 LE ‖ nonce: 12 B ‖ ct_and_tag: ct_len B`

- **Key derivation:** `blake3::derive_key("streamify-vault-v2-aead", master_key)` → 32 B. BLAKE3 keyed KDF, context-separated from every other key use in the workspace (jam state hashing, cache keys).
- **Nonce:** fresh CSPRNG 96-bit nonce **per chunk** — never derived from position or key (the v1 defect). Identical plaintexts seal to distinct ciphertexts (pinned by test).
- **AAD:** the chunk index as LE `u64`. Truncation, reordering and cross-position reassembly all fail authentication.
- **Framing:** length-prefixed records so the streaming reader never scans for boundaries.

### 2.2 Audio-cache chunk cipher — format v2

Envelope per chunk: `nonce: 12 B ‖ ct ‖ tag: 16 B` (sealed size = plaintext + 28 B).

- **Key derivation:** `blake3::derive_key("streamify-cache-v2-aead", cache_key)` → 32 B.
- **Nonce:** fresh CSPRNG 96-bit per sealed chunk.
- **AAD:** caller-supplied `chunk_id` (cache-slot position) — replaying a sealed chunk under a different id is an authentication failure.
- **ABI:** `rust_seal_cache_chunk` / `rust_open_cache_chunk` (C ABI, JNI-bridged as `NativeBridge.rustSealCacheChunk` / `rustOpenCacheChunk`). Negative returns are sentinels (`-1` args, `-2` capacity/malformed envelope, `-3` authentication failure, `-99` panic — the house FFI safety model, `jni_panic_boundary_check.py`).

## 3. Migration (read-old / write-new)

Both readers sniff the v2 magic:

- **v2 magic present** → AEAD path.
- **absent** → legacy path, byte-for-byte the pre-SEC-06 algorithm (SHA-256-derived XOR keystream; the vault reader additionally verifies the v1 whole-file HMAC-SHA256 tail before emitting a single byte).

Writers **always** emit v2. Consequences:

- Every vault/cache written by builds ≤ 2026-11 keeps decrypting.
- Every rewrite upgrades the file to v2 automatically.
- Legacy writers are gone: no code path can produce a v1 file after this change.

The legacy audio-cache chunk reader survives as `crypt_audio_chunk` (rebranded LEGACY in docs; semantics frozen) because no format header exists in the raw chunk stream to sniff — cache readers know which format wrote a slot via the cache metadata version.

## 4. Test gates (all in-tree, CI-enforced)

| Gate | Test |
|---|---|
| known-plaintext resistance | `v2_known_plaintext_distinct_ciphertexts` (vault), `cache_v2_known_plaintext_distinct_ciphertexts` (cache): two encryptions of identical input differ; identical chunks within one file differ |
| round-trip | `v2_round_trip_multichunk` (multi-chunk incl. partial tail), `cache_v2_round_trip`, `v2_empty_source_round_trip` |
| legacy migration | `legacy_v1_migration_read_old_write_new`: a byte-exact v1 vault (writer reproduced in the test) decrypts through the new engine and re-encrypts to v2; `legacy_xor_reader_still_round_trips` for the cache cipher |
| tamper detection | `v2_tamper_detection`, `cache_v2_tamper_fails` (nonce / body / tag bytes), `cache_v2_chunk_reordering_fails` (AAD), `v2_wrong_key_fails_closed`, `cache_v2_wrong_key_fails` |

Clippy runs `-D warnings` on the workspace (CI `clippy` spoke) — these modules are clean by construction.

## 5. Fuzzing follow-up (Horizon 2, DSP-02)

A LibFuzzer/cargo-fuzz harness for the v2 readers (header sniffing, length framing, truncated files) is scheduled with DSP-02's fuzzer promotion; the malformed-envelope sentinels (`-2`) are the targets.

## 6. Out of scope / unchanged

- `STREAMIFY_ZHIPU_KEYS`, InnerTube fleet keys (SEC-07/SEC-08, merged).
- The Jam mesh's Ed25519 governance signatures and BLAKE3 state hashing (already real cryptography, unchanged).
