//! state_hash.rs — the canonical BLAKE3 state hash, moved from
//! `streamify-contract/src/contract.rs` (WS-W engine facade breadth).
//!
//! Moved so the wasm32 facade can mirror the UniFFI `get_jam_state_hash`
//! operation from the SAME code: the hash is the cross-runtime Merkle
//! reconciliation currency — a web replica and an Android replica that
//! merged the same op set must derive the identical 64-hex string, so the
//! hashing code must be one implementation, not a facade-side re-write.
//! `streamify-contract` imports it back through the `streamify-jam`
//! re-export chain (its call sites are unchanged).

use crate::jam_crdt::JamCrdtState;

/// Canonical BLAKE3 state hash of a Jam CRDT replica (UFFI-03/04).
///
/// The hash covers exactly the replicated state — queue, tombstones,
/// vote ledger — serialized in a canonical, order-free form:
///
///   * domain tag `streamify.jam.state.v1` (hash-input separation);
///   * queue triples `(frac_bits, add_op_id, cad_id)` in BTreeMap
///     composite-key order (the fold is already sorted);
///   * tombstones sorted ascending (the fold's HashMap iteration order
///     is NOT canonical — this is the one re-ordering the hash performs);
///   * vote ledger by target ascending, each target's voters
///     byte-ascending, each entry `(voter, up, op_id)` — the LWW
///     register's standing value, so the hash reflects the FINAL ledger
///     regardless of the order vote events arrived in.
///
/// Deliberately EXCLUDED from the image: `needs_rebalance` (a transient
/// control signal, not replicated state), the derived `committed` set
/// (a pure function of queue + ledger + local promotion policy — the
/// policy is per-device configuration, not room state), and session
/// identity fields (the hash must be comparable ACROSS replicas, so it
/// can only cover content they share).
///
/// Convergence contract: for op sets the CRDT absorbs commutatively
/// (Add / Remove / Vote — everything except Reorder-of-an-element-not-
/// yet-added, which requires causal delivery by design), applying the
/// same set in any order on any number of replicas yields the identical
/// 64-hex-char string. That property is what the golden-vector
/// determinism tests pin, and what makes this hash usable as a Merkle
/// reconciliation root on the Kotlin side.
pub fn canonical_jam_state_hash(crdt: &JamCrdtState) -> String {
    let fold = crdt.fold_full();
    let mut hasher = blake3::Hasher::new();
    hasher.update(b"streamify.jam.state.v1");

    // Queue: fold order is BTreeMap composite-key order — canonical.
    hasher.update(&(fold.queue.len() as u64).to_le_bytes());
    for (frac, add_op_id, cad_id) in &fold.queue {
        hasher.update(&frac.to_bits().to_le_bytes());
        hasher.update(&add_op_id.to_le_bytes());
        hasher.update(&cad_id.to_le_bytes());
    }

    // Tombstones: the fold collects them from a HashMap — re-sort before
    // hashing, or two identical tombstone sets could hash differently.
    let mut tombstones = fold.tombstones.clone();
    tombstones.sort_unstable();
    hasher.update(&(tombstones.len() as u64).to_le_bytes());
    for tomb in &tombstones {
        hasher.update(&tomb.to_le_bytes());
    }

    // Vote ledger: fold_full already orders targets ascending and each
    // target's voter list byte-ascending.
    hasher.update(&(fold.votes.len() as u64).to_le_bytes());
    for (target, voters) in &fold.votes {
        hasher.update(&target.to_le_bytes());
        hasher.update(&(voters.len() as u64).to_le_bytes());
        for (voter, up, op_id) in voters {
            hasher.update(voter.as_bytes());
            hasher.update(&[u8::from(*up)]);
            hasher.update(&op_id.to_le_bytes());
        }
    }

    hasher.finalize().to_hex().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::jam_crdt::{JamOp, OpType};

    /// Convergence pin: the same op set applied in different orders on two
    /// replicas derives the identical hash — the Merkle root contract.
    #[test]
    fn hash_is_order_independent() {
        let mk = |i: u64| {
            JamOp::new(
                i,
                [0xAA, 0xBB, 0xCC, 0xDD],
                OpType::Add,
                0,
                1000 + i,
                0.5 + i as f64,
                0,
            )
        };
        let mut a = JamCrdtState::new();
        let mut b = JamCrdtState::new();
        for op in [&mk(1), &mk(2), &mk(3)] {
            a.apply_op(op);
        }
        for op in [&mk(3), &mk(1), &mk(2)] {
            b.apply_op(op);
        }
        assert_eq!(canonical_jam_state_hash(&a), canonical_jam_state_hash(&b));
        // And a different op set genuinely differs.
        let mut c = JamCrdtState::new();
        for op in [&mk(1), &mk(2)] {
            c.apply_op(op);
        }
        assert_ne!(canonical_jam_state_hash(&a), canonical_jam_state_hash(&c));
    }
}
