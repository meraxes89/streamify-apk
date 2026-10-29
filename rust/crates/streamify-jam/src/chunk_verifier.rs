//! chunk_verifier.rs — Byte-range integrity & Merkle hash-tree verifier
//! (Phase 3, directive §2.1).
//!
//! MISSION: every chunk that arrives from the LAN swarm is verified
//! IMMEDIATELY — before a single byte is committed to disk. Bit-rotted or
//! maliciously corrupted payloads are rejected at the door, and the caller
//! (chunk swarmer / LAN sync coordinator) re-downloads the chunk from an
//! alternative peer. Disk rehydration after an interrupted transfer goes
//! through the same gate, so silent at-rest corruption is caught too.
//!
//! DESIGN:
//!   • Chunks      — 64 KiB … 1 MiB audio blocks (directive range, enforced
//!                   in [`HashTreeManifest::build`] and re-enforced when a
//!                   manifest arrives from the wire).
//!   • Leaf hash   — Blake3 over the chunk payload (zero-copy: the hasher
//!                   reads the caller's `&[u8]` slice in place; no staging
//!                   copies, no per-chunk allocation).
//!   • Merkle tree — pairwise Blake3 over concatenated child digests;
//!                   an odd trailing node is promoted unchanged (documented,
//!                   deterministic, cheaper than duplication). The root pins
//!                   the ENTIRE chunk-hash list: a manifest whose recomputed
//!                   root diverges from its declared root is rejected at
//!                   parse time.
//!   • Proofs      — [`MerkleProof`] for O(log n) single-chunk attestation
//!                   (a leecher can prove one chunk against the gossiped root
//!                   without shipping the full hash list).
//!   • File hash   — whole-file Blake3 (`total_hash`) is the final assembly
//!                   check, exactly like the mesh swarmer's `TrackManifest`.
//!
//! WIRE DISCIPLINE (house rules): little-endian, strictly bounds-checked,
//! unknown trailing bytes ignored (forward compatibility), no panic
//! surface on malformed input — every decoder returns `Result`/`Option`
//! and is exercised by truncation sweeps in the unit tests.
//!
//! This module is pure logic (no I/O, no clocks): the transport layers
//! inject payloads and consume [`ChunkVerdict`]s.

use std::fmt;

/// Smallest legal chunk (directive: 64 KiB floor).
pub const MIN_CHUNK_SIZE: usize = 64 * 1024;
/// Largest legal chunk (directive: 1 MiB ceiling).
pub const MAX_CHUNK_SIZE: usize = 1024 * 1024;
/// Blake3 digest length.
pub const HASH_LEN: usize = 32;
/// Manifest wire header: magic(4) ver(1) total_len(8) chunk_size(4)
/// num_chunks(4) total_hash(32) merkle_root(32).
pub const TREE_HEADER_LEN: usize = 4 + 1 + 8 + 4 + 4 + 32 + 32;
/// Chunk-count budget: 65 535 × 32 B = ~2 MiB of hashes — bounds manifest
/// memory even for hostile wire input. At 1 MiB chunks that is a 64 GiB
/// file; larger libraries split at the app layer.
pub const MAX_TREE_CHUNKS: u32 = 65_535;

/// A Blake3 digest.
pub type ChunkHash = [u8; HASH_LEN];

/// One-shot Blake3 over a payload slice — the zero-copy hot path.
#[inline]
pub fn hash_payload(payload: &[u8]) -> ChunkHash {
    let mut h = [0u8; HASH_LEN];
    h.copy_from_slice(blake3::hash(payload).as_bytes());
    h
}

/// Whole-file Blake3 (catalog manifests + final assembly checks).
#[inline]
pub fn hash_file(data: &[u8]) -> ChunkHash {
    hash_payload(data)
}

/// Verifier-layer errors (construction / wire parsing).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerifierError {
    /// `chunk_size` outside `[MIN_CHUNK_SIZE, MAX_CHUNK_SIZE]`.
    ChunkSizeOutOfRange { chunk_size: usize },
    /// Wire buffer too short for even the fixed header.
    MalformedHeader,
    /// Declared chunk count / length fields disagree with the layout.
    MalformedBody,
    /// Declared chunk count exceeds [`MAX_TREE_CHUNKS`].
    TooManyChunks { num_chunks: u32 },
    /// Recomputed Merkle root ≠ declared root (tampered hash list).
    RootMismatch,
    /// `num_chunks` ≠ ceil(total_len / chunk_size).
    ChunkCountInconsistent,
}

impl fmt::Display for VerifierError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            VerifierError::ChunkSizeOutOfRange { chunk_size } => write!(
                f,
                "chunk size {chunk_size} outside [{MIN_CHUNK_SIZE}, {MAX_CHUNK_SIZE}]"
            ),
            VerifierError::MalformedHeader => write!(f, "manifest shorter than fixed header"),
            VerifierError::MalformedBody => write!(f, "manifest body length mismatch"),
            VerifierError::TooManyChunks { num_chunks } => {
                write!(
                    f,
                    "manifest declares {num_chunks} > {MAX_TREE_CHUNKS} chunks"
                )
            }
            VerifierError::RootMismatch => {
                write!(f, "recomputed merkle root diverges from declared root")
            }
            VerifierError::ChunkCountInconsistent => {
                write!(f, "num_chunks disagrees with total_len / chunk_size")
            }
        }
    }
}

impl std::error::Error for VerifierError {}

// ─────────────────────────────────────────────────────────── merkle tree

/// One step of a Merkle audit path: the sibling digest and which side of
/// the parent preimage it occupies.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProofStep {
    pub sibling_hash: ChunkHash,
    /// `true` → sibling is the RIGHT operand of `blake3(leaf || sibling)`.
    pub sibling_is_right: bool,
}

/// O(log n) attestation of a single chunk hash against the tree root.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MerkleProof {
    pub chunk_index: u32,
    pub steps: Vec<ProofStep>,
}

impl MerkleProof {
    /// Folds the leaf up the recorded sibling path and compares with `root`.
    pub fn verify(&self, leaf_hash: &ChunkHash, root: &ChunkHash) -> bool {
        let mut h = *leaf_hash;
        for s in &self.steps {
            h = fold_pair(&h, &s.sibling_hash, s.sibling_is_right);
        }
        h == *root
    }
}

/// `blake3(left || right)` — the only internal-node preimage.
fn fold_pair(left: &ChunkHash, right: &ChunkHash, sibling_is_right: bool) -> ChunkHash {
    let mut buf = [0u8; 2 * HASH_LEN];
    if sibling_is_right {
        buf[..HASH_LEN].copy_from_slice(left);
        buf[HASH_LEN..].copy_from_slice(right);
    } else {
        buf[..HASH_LEN].copy_from_slice(right);
        buf[HASH_LEN..].copy_from_slice(left);
    }
    hash_payload(&buf)
}

/// Reduces one level: pairs fold, an odd trailing node is promoted intact.
fn fold_level(level: Vec<ChunkHash>) -> Vec<ChunkHash> {
    if level.len() <= 1 {
        return level;
    }
    let mut next = Vec::with_capacity(level.len().div_ceil(2));
    let mut i = 0;
    while i + 1 < level.len() {
        next.push(fold_pair(&level[i], &level[i + 1], true));
        i += 2;
    }
    if i < level.len() {
        next.push(level[i]);
    }
    next
}

/// Root over the leaf digests; empty leaf set hashes the empty input.
fn merkle_root_from_leaves(leaves: &[ChunkHash]) -> ChunkHash {
    if leaves.is_empty() {
        return hash_payload(&[]);
    }
    let mut level = leaves.to_vec();
    while level.len() > 1 {
        level = fold_level(level);
    }
    level[0]
}

/// Sibling path for `index` (levels nearest the leaves first).
fn merkle_path_for(leaves: &[ChunkHash], index: usize) -> Vec<ProofStep> {
    let mut steps = Vec::new();
    let mut level = leaves.to_vec();
    let mut idx = index;
    while level.len() > 1 {
        if idx.is_multiple_of(2) {
            if idx + 1 < level.len() {
                steps.push(ProofStep {
                    sibling_hash: level[idx + 1],
                    sibling_is_right: true,
                });
            }
            // else: odd-tail promotion — no sibling at this level.
        } else {
            steps.push(ProofStep {
                sibling_hash: level[idx - 1],
                sibling_is_right: false,
            });
        }
        level = fold_level(level);
        idx /= 2;
    }
    steps
}

// ────────────────────────────────────────────────────────────── manifest

/// Chunk-hash manifest with a Merkle root pinning the hash list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HashTreeManifest {
    pub total_len: u64,
    pub chunk_size: u32,
    pub num_chunks: u32,
    /// Blake3 over the whole file — final assembly check.
    pub total_hash: ChunkHash,
    /// Merkle root over `chunk_hashes`.
    pub merkle_root: ChunkHash,
    /// Blake3 of each chunk, in order.
    pub chunk_hashes: Vec<ChunkHash>,
}

impl HashTreeManifest {
    /// Builds the manifest for `data`. `chunk_size` MUST be inside
    /// `[MIN_CHUNK_SIZE, MAX_CHUNK_SIZE]` (directive §2.1 — 64 KiB…1 MiB
    /// audio chunks); out-of-range sizes fail loudly instead of silently
    /// producing a manifest the wire layer would later reject.
    pub fn build(data: &[u8], chunk_size: usize) -> Result<Self, VerifierError> {
        if !(MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&chunk_size) {
            return Err(VerifierError::ChunkSizeOutOfRange { chunk_size });
        }
        let num_chunks = data.len().div_ceil(chunk_size);
        if num_chunks > MAX_TREE_CHUNKS as usize {
            return Err(VerifierError::TooManyChunks {
                num_chunks: num_chunks as u32,
            });
        }
        // Zero-copy slicing: each leaf hashes the data slice in place
        // (the tail slice is clamped to the final byte).
        let chunk_hashes: Vec<ChunkHash> = (0..num_chunks)
            .map(|i| {
                let lo = i * chunk_size;
                let hi = lo + chunk_size.min(data.len() - lo);
                hash_payload(&data[lo..hi])
            })
            .collect();
        let merkle_root = merkle_root_from_leaves(&chunk_hashes);
        Ok(HashTreeManifest {
            total_len: data.len() as u64,
            chunk_size: chunk_size as u32,
            num_chunks: num_chunks as u32,
            total_hash: hash_file(data),
            merkle_root,
            chunk_hashes,
        })
    }

    /// Length of chunk `idx` (the tail chunk may be short); 0 if out of
    /// range.
    pub fn chunk_len(&self, idx: u32) -> usize {
        if idx >= self.num_chunks {
            return 0;
        }
        let start = idx as u64 * self.chunk_size as u64;
        (self.total_len.saturating_sub(start)).min(self.chunk_size as u64) as usize
    }

    /// Byte range `[lo, hi)` of chunk `idx` inside the assembled file.
    pub fn chunk_range(&self, idx: u32) -> Option<(u64, u64)> {
        if idx >= self.num_chunks {
            return None;
        }
        let cs = self.chunk_size as u64;
        let lo = idx as u64 * cs;
        Some((lo, (lo + cs).min(self.total_len)))
    }

    /// O(log n) audit path for one chunk.
    pub fn merkle_proof(&self, idx: u32) -> Option<MerkleProof> {
        if idx >= self.num_chunks {
            return None;
        }
        Some(MerkleProof {
            chunk_index: idx,
            steps: merkle_path_for(&self.chunk_hashes, idx as usize),
        })
    }

    /// Wire layout (all LE): `magic "CHVT" ver u8 total_len u64 chunk_size
    /// u32 num_chunks u32 total_hash[32] merkle_root[32] hashes[32×num]`.
    pub fn to_wire(&self) -> Vec<u8> {
        let mut p = Vec::with_capacity(TREE_HEADER_LEN + HASH_LEN * self.chunk_hashes.len());
        p.extend_from_slice(b"CHVT");
        p.push(1);
        p.extend_from_slice(&self.total_len.to_le_bytes());
        p.extend_from_slice(&self.chunk_size.to_le_bytes());
        p.extend_from_slice(&self.num_chunks.to_le_bytes());
        p.extend_from_slice(&self.total_hash);
        p.extend_from_slice(&self.merkle_root);
        for h in &self.chunk_hashes {
            p.extend_from_slice(h);
        }
        p
    }

    /// Strict parse: exact length, sane field ranges, chunk-count
    /// consistency, and a RECOMPUTED Merkle root (a tampered hash list
    /// dies here). Trailing bytes beyond the declared layout are ignored
    /// (forward compatibility).
    pub fn from_wire(payload: &[u8]) -> Result<Self, VerifierError> {
        if payload.len() < TREE_HEADER_LEN {
            return Err(VerifierError::MalformedHeader);
        }
        if &payload[0..4] != b"CHVT" {
            return Err(VerifierError::MalformedHeader);
        }
        if payload[4] != 1 {
            return Err(VerifierError::MalformedHeader);
        }
        let num_chunks = u32::from_le_bytes(payload[17..21].try_into().unwrap());
        if num_chunks > MAX_TREE_CHUNKS {
            return Err(VerifierError::TooManyChunks { num_chunks });
        }
        let declared_len = TREE_HEADER_LEN + HASH_LEN * num_chunks as usize;
        if payload.len() < declared_len {
            return Err(VerifierError::MalformedBody);
        }
        let chunk_hashes: Vec<ChunkHash> = (0..num_chunks as usize)
            .map(|i| {
                let base = TREE_HEADER_LEN + i * HASH_LEN;
                payload[base..base + HASH_LEN].try_into().unwrap()
            })
            .collect();
        let manifest = HashTreeManifest {
            total_len: u64::from_le_bytes(payload[5..13].try_into().unwrap()),
            chunk_size: u32::from_le_bytes(payload[13..17].try_into().unwrap()),
            num_chunks,
            total_hash: payload[21..53].try_into().unwrap(),
            merkle_root: payload[53..85].try_into().unwrap(),
            chunk_hashes,
        };
        // Field sanity: directive chunk range + count consistency.
        if !(MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&(manifest.chunk_size as usize)) {
            return Err(VerifierError::ChunkSizeOutOfRange {
                chunk_size: manifest.chunk_size as usize,
            });
        }
        let expected = if manifest.total_len == 0 {
            0
        } else {
            manifest.total_len.div_ceil(manifest.chunk_size as u64) as u32
        };
        if expected != manifest.num_chunks {
            return Err(VerifierError::ChunkCountInconsistent);
        }
        // Tamper evidence: the declared root must pin the hash list.
        if merkle_root_from_leaves(&manifest.chunk_hashes) != manifest.merkle_root {
            return Err(VerifierError::RootMismatch);
        }
        Ok(manifest)
    }
}

// ─────────────────────────────────────────────────────── streaming verify

/// Why an incoming chunk payload was rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    IndexOutOfRange,
    LengthMismatch,
    HashMismatch,
}

impl fmt::Display for RejectReason {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RejectReason::IndexOutOfRange => write!(f, "chunk index out of range"),
            RejectReason::LengthMismatch => write!(f, "payload length mismatch"),
            RejectReason::HashMismatch => write!(f, "blake3 hash mismatch"),
        }
    }
}

/// Verdict for one ingested chunk — callers commit to disk ONLY on
/// `Accepted`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkVerdict {
    /// Payload verified against the manifest: safe to persist.
    Accepted { index: u32, len: usize },
    /// Payload verified but the index was already marked: idempotent
    /// re-delivery (endgame duplicates land here).
    Duplicate { index: u32, len: usize },
    /// Rejected BEFORE any disk write — re-download from another peer.
    Rejected { index: u32, reason: RejectReason },
}

/// Streaming per-file verifier: feeds chunk payloads as they arrive off
/// the network (or off disk during rehydration) and never stores payload
/// bytes — verification and storage are deliberately decoupled.
pub struct ChunkVerifier {
    manifest: HashTreeManifest,
    verified: Vec<bool>,
    verified_bytes: u64,
    rejected_count: u64,
    duplicate_count: u64,
}

impl ChunkVerifier {
    pub fn new(manifest: HashTreeManifest) -> Self {
        let n = manifest.num_chunks as usize;
        ChunkVerifier {
            manifest,
            verified: vec![false; n],
            verified_bytes: 0,
            rejected_count: 0,
            duplicate_count: 0,
        }
    }

    /// Immediate verify-and-classify of one chunk payload. Zero-copy: the
    /// digest is computed directly over the caller's slice.
    pub fn verify_chunk(&mut self, index: u32, payload: &[u8]) -> ChunkVerdict {
        if index >= self.manifest.num_chunks {
            self.rejected_count += 1;
            return ChunkVerdict::Rejected {
                index,
                reason: RejectReason::IndexOutOfRange,
            };
        }
        if payload.len() != self.manifest.chunk_len(index) {
            self.rejected_count += 1;
            return ChunkVerdict::Rejected {
                index,
                reason: RejectReason::LengthMismatch,
            };
        }
        if hash_payload(payload) != self.manifest.chunk_hashes[index as usize] {
            self.rejected_count += 1;
            return ChunkVerdict::Rejected {
                index,
                reason: RejectReason::HashMismatch,
            };
        }
        let len = payload.len();
        if self.verified[index as usize] {
            self.duplicate_count += 1;
            return ChunkVerdict::Duplicate { index, len };
        }
        self.verified[index as usize] = true;
        self.verified_bytes += len as u64;
        ChunkVerdict::Accepted { index, len }
    }

    /// Merkle attestation of one verified chunk against the gossiped root.
    pub fn proof_for(&self, index: u32) -> Option<MerkleProof> {
        self.manifest.merkle_proof(index)
    }

    pub fn is_verified(&self, index: u32) -> bool {
        self.verified.get(index as usize).copied().unwrap_or(false)
    }

    pub fn verified_chunk_count(&self) -> u32 {
        self.verified.iter().filter(|&&v| v).count() as u32
    }

    pub fn is_complete(&self) -> bool {
        self.verified.len() as u32 == self.manifest.num_chunks && self.verified.iter().all(|&v| v)
    }

    pub fn verified_bytes(&self) -> u64 {
        self.verified_bytes
    }

    pub fn rejected_count(&self) -> u64 {
        self.rejected_count
    }

    pub fn duplicate_count(&self) -> u64 {
        self.duplicate_count
    }

    pub fn manifest(&self) -> &HashTreeManifest {
        &self.manifest
    }

    /// Full re-check of an assembled file: every chunk AND the whole-file
    /// Blake3. Used at transfer completion (end-to-end byte equivalence).
    pub fn verify_file(&self, data: &[u8]) -> bool {
        if data.len() as u64 != self.manifest.total_len {
            return false;
        }
        if hash_file(data) != self.manifest.total_hash {
            return false;
        }
        let cs = self.manifest.chunk_size as usize;
        (0..self.manifest.num_chunks as usize).all(|i| {
            let lo = i * cs;
            let hi = lo + cs.min(data.len() - lo);
            hash_payload(&data[lo..hi]) == self.manifest.chunk_hashes[i]
        })
    }
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic pseudo-audio (no RNG dependency — formulaic bytes).
    fn sample(len: usize) -> Vec<u8> {
        (0..len).map(|i| ((i * 31 + 7) % 251) as u8).collect()
    }

    #[test]
    fn build_rejects_out_of_directive_range() {
        let data = sample(4 * MAX_CHUNK_SIZE);
        assert!(matches!(
            HashTreeManifest::build(&data, 1024),
            Err(VerifierError::ChunkSizeOutOfRange { chunk_size: 1024 })
        ));
        let too_big = MAX_CHUNK_SIZE + 1;
        assert!(matches!(
            HashTreeManifest::build(&data, too_big),
            Err(VerifierError::ChunkSizeOutOfRange { .. })
        ));
        // Both range endpoints are legal.
        assert!(HashTreeManifest::build(&data, MIN_CHUNK_SIZE).is_ok());
        assert!(HashTreeManifest::build(&data, MAX_CHUNK_SIZE).is_ok());
    }

    #[test]
    fn manifest_layout_tail_chunk_and_roundtrip() {
        // 3 × 64 KiB + 1234 B tail → 4 chunks.
        let data = sample(3 * MIN_CHUNK_SIZE + 1234);
        let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
        assert_eq!(m.num_chunks, 4);
        assert_eq!(m.total_len, data.len() as u64);
        assert_eq!(m.chunk_len(3), 1234);
        assert_eq!(
            m.chunk_range(2),
            Some((2 * MIN_CHUNK_SIZE as u64, 3 * MIN_CHUNK_SIZE as u64))
        );
        assert_eq!(m.chunk_range(4), None);

        let wire = m.to_wire();
        assert_eq!(wire.len(), TREE_HEADER_LEN + 4 * HASH_LEN);
        assert_eq!(HashTreeManifest::from_wire(&wire).unwrap(), m);
        // Trailing bytes are ignored (forward compat), not a panic.
        let mut padded = wire.clone();
        padded.extend_from_slice(&[0xFF, 0xFF]);
        assert_eq!(HashTreeManifest::from_wire(&padded).unwrap(), m);
    }

    #[test]
    fn every_prefix_truncation_is_an_error_never_a_panic() {
        let data = sample(2 * MIN_CHUNK_SIZE + 5);
        let wire = HashTreeManifest::build(&data, MIN_CHUNK_SIZE)
            .unwrap()
            .to_wire();
        for cut in 0..wire.len() {
            assert!(
                HashTreeManifest::from_wire(&wire[..cut]).is_err(),
                "truncation at {cut} must be rejected"
            );
        }
        // Random junk never panics.
        let junk: Vec<u8> = (0..777u32).map(|i| (i * 7 % 256) as u8).collect();
        assert!(HashTreeManifest::from_wire(&junk).is_err());
    }

    #[test]
    fn tampered_hash_list_fails_root_recheck() {
        let data = sample(2 * MAX_CHUNK_SIZE + 999);
        let mut m = HashTreeManifest::build(&data, MAX_CHUNK_SIZE).unwrap();
        m.chunk_hashes[0][7] ^= 0x80; // silent tamper
        assert_eq!(
            HashTreeManifest::from_wire(&m.to_wire()),
            Err(VerifierError::RootMismatch)
        );
        // The declared root no longer matches the (honest) leaves either.
        let honest_root = merkle_root_from_leaves(&m.chunk_hashes);
        assert_ne!(honest_root, m.merkle_root);
    }

    #[test]
    fn inconsistent_chunk_count_is_rejected() {
        let data = sample(2 * MIN_CHUNK_SIZE);
        let mut m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
        m.num_chunks = 3; // lie: 2 × 64 KiB is 2 chunks
        m.chunk_hashes.push([9u8; 32]);
        m.merkle_root = merkle_root_from_leaves(&m.chunk_hashes);
        assert_eq!(
            HashTreeManifest::from_wire(&m.to_wire()),
            Err(VerifierError::ChunkCountInconsistent)
        );
    }

    #[test]
    fn verifier_accepts_rejects_bitrot_and_dedups() {
        let data = sample(2 * MIN_CHUNK_SIZE + 777);
        let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
        let mut v = ChunkVerifier::new(m.clone());

        // Chunk 0: good.
        assert_eq!(
            v.verify_chunk(0, &data[..MIN_CHUNK_SIZE]),
            ChunkVerdict::Accepted {
                index: 0,
                len: MIN_CHUNK_SIZE
            }
        );
        // Bit-rot: one flipped byte.
        let mut rot = data[MIN_CHUNK_SIZE..2 * MIN_CHUNK_SIZE].to_vec();
        rot[3311] ^= 0x40;
        assert_eq!(
            v.verify_chunk(1, &rot),
            ChunkVerdict::Rejected {
                index: 1,
                reason: RejectReason::HashMismatch
            }
        );
        // Truncated payload.
        assert_eq!(
            v.verify_chunk(1, &data[MIN_CHUNK_SIZE..2 * MIN_CHUNK_SIZE - 1]),
            ChunkVerdict::Rejected {
                index: 1,
                reason: RejectReason::LengthMismatch
            }
        );
        // Out-of-range index.
        assert_eq!(
            v.verify_chunk(3, &[0u8; 8]),
            ChunkVerdict::Rejected {
                index: 3,
                reason: RejectReason::IndexOutOfRange
            }
        );
        // Genuine chunk 1 + duplicate delivery of chunk 0.
        assert!(matches!(
            v.verify_chunk(1, &data[MIN_CHUNK_SIZE..2 * MIN_CHUNK_SIZE]),
            ChunkVerdict::Accepted { .. }
        ));
        assert!(matches!(
            v.verify_chunk(0, &data[..MIN_CHUNK_SIZE]),
            ChunkVerdict::Duplicate { .. }
        ));

        assert_eq!(v.rejected_count(), 3);
        assert_eq!(v.duplicate_count(), 1);
        assert!(!v.is_complete());
        // Tail chunk completes the set.
        assert!(matches!(
            v.verify_chunk(2, &data[2 * MIN_CHUNK_SIZE..]),
            ChunkVerdict::Accepted { .. }
        ));
        assert!(v.is_complete());
        assert_eq!(v.verified_chunk_count(), 3);
        assert_eq!(v.verified_bytes(), data.len() as u64);
    }

    #[test]
    fn zero_copy_slices_of_a_larger_buffer_verify() {
        // Chunks verified as sub-slices of ONE contiguous network buffer —
        // no copies, no realignment.
        let data = sample(2 * MAX_CHUNK_SIZE);
        let buf = data.clone(); // the "network buffer"
        let m = HashTreeManifest::build(&buf, MAX_CHUNK_SIZE).unwrap();
        let mut v = ChunkVerifier::new(m);
        assert!(matches!(
            v.verify_chunk(1, &buf[MAX_CHUNK_SIZE..]),
            ChunkVerdict::Accepted { .. }
        ));
        assert!(matches!(
            v.verify_chunk(0, &buf[..MAX_CHUNK_SIZE]),
            ChunkVerdict::Accepted { .. }
        ));
        assert!(v.is_complete());
    }

    #[test]
    fn merkle_proofs_verify_and_tampering_is_caught() {
        let data = sample(9 * MIN_CHUNK_SIZE + 11); // 10 chunks, odd levels
        let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
        for i in 0..m.num_chunks {
            let proof = m.merkle_proof(i).unwrap();
            let lo = i as usize * MIN_CHUNK_SIZE;
            let hi = lo + MIN_CHUNK_SIZE.min(data.len() - lo);
            let leaf = hash_payload(&data[lo..hi]);
            assert!(proof.verify(&leaf, &m.merkle_root), "proof {i} must verify");
            // Cross-leaf substitution must fail.
            let other = hash_payload(&data[0..MIN_CHUNK_SIZE]);
            if i != 0 {
                assert!(!proof.verify(&other, &m.merkle_root));
            }
        }
        // Tampered root fails even an honest proof.
        let proof = m.merkle_proof(4).unwrap();
        let leaf = m.chunk_hashes[4];
        let mut bad_root = m.merkle_root;
        bad_root[0] ^= 1;
        assert!(!proof.verify(&leaf, &bad_root));
        // Flipping one proof step breaks the fold.
        let mut tampered = proof.clone();
        if let Some(s) = tampered.steps.first_mut() {
            s.sibling_hash[9] ^= 0x11;
        }
        assert!(!tampered.verify(&leaf, &m.merkle_root));
    }

    #[test]
    fn verify_file_end_to_end_equivalence() {
        let data = sample(2 * MIN_CHUNK_SIZE + 4096);
        let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
        let v = ChunkVerifier::new(m);
        assert!(v.verify_file(&data));
        // Any single-byte divergence anywhere fails.
        let mut evil = data.clone();
        evil[data.len() - 1] ^= 0x01;
        assert!(!v.verify_file(&evil));
        // Length change fails.
        assert!(!v.verify_file(&data[..data.len() - 1]));
    }

    #[test]
    fn empty_file_is_a_degenerate_but_safe_manifest() {
        let m = HashTreeManifest::build(&[], MIN_CHUNK_SIZE).unwrap();
        assert_eq!(m.num_chunks, 0);
        assert_eq!(m.merkle_root, hash_payload(&[]));
        assert_eq!(m.merkle_root, m.total_hash);
        let mut v = ChunkVerifier::new(m.clone());
        assert!(v.is_complete()); // vacuously
        assert!(v.verify_file(&[]));
        // A chunk claim against an empty manifest is out of range.
        assert!(matches!(
            v.verify_chunk(0, &[0u8; 16]),
            ChunkVerdict::Rejected {
                reason: RejectReason::IndexOutOfRange,
                ..
            }
        ));
    }
}
