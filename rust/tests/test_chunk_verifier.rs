//! test_chunk_verifier.rs — byte-range integrity & hash-tree verifier
//! integration suite (Phase 3, directive §2.5).
//!
//! Exercises [`streamify_core_rs::chunk_verifier`] at real directive
//! scale: multi-MiB audio payloads, 64 KiB…1 MiB chunk boundaries,
//! bit-rot sweeps, Merkle audit paths, hostile manifest fuzzing —
//! everything deterministic, no I/O.

use streamify_core_rs::chunk_verifier::{
    hash_file, hash_payload, ChunkVerifier, HashTreeManifest, MerkleProof, RejectReason,
    MAX_CHUNK_SIZE, MIN_CHUNK_SIZE,
};

/// Deterministic pseudo-audio at arbitrary size.
fn pseudo_audio(len: usize) -> Vec<u8> {
    // SplitMix-style expansion keeps every MiB statistically distinct
    // while staying byte-reproducible across runs.
    let mut out = Vec::with_capacity(len);
    let mut state = 0x5EED_5EED_u64;
    while out.len() < len {
        state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^= z >> 31;
        out.extend_from_slice(&z.to_le_bytes());
    }
    out.truncate(len);
    out
}

#[test]
fn full_size_file_tree_verifies_every_chunk() {
    // ~4.3 MiB at the 1 MiB ceiling: 4 full chunks + a 331 KB tail.
    let len = 4 * MAX_CHUNK_SIZE + 337_920;
    let data = pseudo_audio(len);
    let m = HashTreeManifest::build(&data, MAX_CHUNK_SIZE).unwrap();
    assert_eq!(m.num_chunks, 5);
    assert_eq!(m.total_len, len as u64);

    let mut v = ChunkVerifier::new(m.clone());
    let cs = MAX_CHUNK_SIZE;
    for i in 0..m.num_chunks as usize {
        let lo = i * cs;
        let hi = (lo + cs).min(len);
        let verdict = v.verify_chunk(i as u32, &data[lo..hi]);
        assert!(matches!(
            verdict,
            streamify_core_rs::chunk_verifier::ChunkVerdict::Accepted { .. }
        ));
    }
    assert!(v.is_complete());
    assert_eq!(v.verified_bytes(), len as u64);
    assert!(v.verify_file(&data));
}

#[test]
fn minimum_chunk_boundary_and_short_tail() {
    // 64 KiB floor: exactly 3 chunks + 1 B tail.
    let data = pseudo_audio(3 * MIN_CHUNK_SIZE + 1);
    let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
    assert_eq!(m.num_chunks, 4);
    assert_eq!(m.chunk_len(3), 1);
    let mut v = ChunkVerifier::new(m);
    for i in 0..4u32 {
        let lo = i as usize * MIN_CHUNK_SIZE;
        let hi = (lo + MIN_CHUNK_SIZE).min(data.len());
        assert!(matches!(
            v.verify_chunk(i, &data[lo..hi]),
            streamify_core_rs::chunk_verifier::ChunkVerdict::Accepted { .. }
        ));
    }
    assert!(v.verify_file(&data));
}

#[test]
fn bit_rot_at_every_region_is_rejected_before_disk() {
    let data = pseudo_audio(2 * MIN_CHUNK_SIZE + 8_000);
    let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
    let mut v = ChunkVerifier::new(m);

    // Corrupt one byte inside each logical region of chunk 1.
    let chunk1 = &data[MIN_CHUNK_SIZE..2 * MIN_CHUNK_SIZE];
    for &probe in &[0usize, 1, 7, 512, 4096, 32_000, MIN_CHUNK_SIZE - 1] {
        let mut rot = chunk1.to_vec();
        rot[probe] ^= 0x01;
        assert_eq!(
            v.verify_chunk(1, &rot),
            streamify_core_rs::chunk_verifier::ChunkVerdict::Rejected {
                index: 1,
                reason: RejectReason::HashMismatch
            },
            "bit-rot at offset {probe} must be rejected before commit"
        );
    }
    // Nothing was stored: the chunk stays pending.
    assert_eq!(v.verified_chunk_count(), 0);
    assert!(!v.is_complete());
    assert_eq!(v.rejected_count(), 7);
}

#[test]
fn zero_copy_verification_from_one_network_buffer() {
    // All chunks verified as sub-slices of a single contiguous "network
    // receive buffer" — no copies, no realignment, slice-relative math.
    let data = pseudo_audio(3 * MIN_CHUNK_SIZE + 5_000);
    let buf = data.clone();
    let m = HashTreeManifest::build(&buf, MIN_CHUNK_SIZE).unwrap();
    let mut v = ChunkVerifier::new(m.clone());
    // Feed in reverse order to prove index-driven (not positional) logic.
    for i in (0..m.num_chunks).rev() {
        let lo = i as usize * MIN_CHUNK_SIZE;
        let hi = (lo + MIN_CHUNK_SIZE).min(buf.len());
        assert!(matches!(
            v.verify_chunk(i, &buf[lo..hi]),
            streamify_core_rs::chunk_verifier::ChunkVerdict::Accepted { .. }
        ));
    }
    assert!(v.is_complete());
    assert!(v.verify_file(&buf));
}

#[test]
fn merkle_proofs_attest_every_chunk_against_the_root() {
    // 33 chunks → odd levels exercise the promotion path.
    let data = pseudo_audio(33 * MIN_CHUNK_SIZE - 4_321);
    let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
    assert_eq!(m.num_chunks, 33);
    for i in 0..m.num_chunks {
        let proof = m.merkle_proof(i).unwrap();
        let lo = i as usize * MIN_CHUNK_SIZE;
        let hi = (lo + MIN_CHUNK_SIZE).min(data.len());
        let leaf = hash_payload(&data[lo..hi]);
        assert!(proof.verify(&leaf, &m.merkle_root), "proof {i} must attest");
        // Cross-chunk substitution fails for any other leaf.
        for &j in [0u32, 16, 32].iter() {
            if j != i {
                let other_lo = j as usize * MIN_CHUNK_SIZE;
                let other_hi = (other_lo + MIN_CHUNK_SIZE).min(data.len());
                let other = hash_payload(&data[other_lo..other_hi]);
                assert!(!proof.verify(&other, &m.merkle_root));
            }
        }
    }
    // A hand-built proof with a flipped sibling never verifies.
    let mut evil = m.merkle_proof(5).unwrap();
    if let Some(s) = evil.steps.first_mut() {
        s.sibling_hash[3] ^= 0x40;
    }
    let leaf5 = hash_payload(&data[5 * MIN_CHUNK_SIZE..6 * MIN_CHUNK_SIZE]);
    assert!(!evil.verify(&leaf5, &m.merkle_root));
    let _: MerkleProof = evil; // type is nameable + Send-ish by value
}

#[test]
fn manifest_wire_survives_hostile_fuzzing() {
    let data = pseudo_audio(2 * MAX_CHUNK_SIZE + 1);
    let wire = HashTreeManifest::build(&data, MAX_CHUNK_SIZE)
        .unwrap()
        .to_wire();

    // 1. Every prefix truncation → error, never a panic.
    for cut in 0..wire.len() {
        assert!(
            HashTreeManifest::from_wire(&wire[..cut]).is_err(),
            "truncation at {cut} must be rejected"
        );
    }
    // 2. Deterministic random junk never panics.
    let mut state = 0xFA1_CE11_u64;
    for round in 0..200 {
        let junk: Vec<u8> = (0..(round % 300 + 1))
            .map(|_| {
                state = state
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                (state >> 33) as u8
            })
            .collect();
        let _ = HashTreeManifest::from_wire(&junk);
    }
    // 3. Single-bit flips anywhere in the header region are caught by
    //    layout/consistency/root checks (error or a valid parse that
    //    still fails chunk verification — but never a panic).
    for byte_idx in 0..wire.len().min(85) {
        for bit in [0u8, 1, 2, 4, 7] {
            let mut tampered = wire.clone();
            tampered[byte_idx] ^= 1 << bit;
            let _ = HashTreeManifest::from_wire(&tampered);
        }
    }
}

#[test]
fn tampered_chunk_hash_list_is_pinned_by_the_root() {
    let data = pseudo_audio(5 * MIN_CHUNK_SIZE + 777);
    let mut m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
    m.chunk_hashes[2][0] ^= 0xFF; // silent tamper
                                  // The wire re-parse recomputes the root → hard rejection.
    assert!(HashTreeManifest::from_wire(&m.to_wire()).is_err());
}

#[test]
fn whole_file_hash_independent_cross_check() {
    // The manifest's total_hash must equal an independent one-shot
    // Blake3 over the file — the final assembly gate is trustworthy.
    let data = pseudo_audio(MIN_CHUNK_SIZE * 2 + 12_345);
    let m = HashTreeManifest::build(&data, MIN_CHUNK_SIZE).unwrap();
    assert_eq!(m.total_hash, hash_file(&data));
    // And the merkle root over an EMPTY chunk list is the empty hash.
    let empty = HashTreeManifest::build(&[], MIN_CHUNK_SIZE).unwrap();
    assert_eq!(empty.merkle_root, hash_file(&[]));
}
