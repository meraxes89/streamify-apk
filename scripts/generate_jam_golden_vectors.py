#!/usr/bin/env python3
"""
generate_jam_golden_vectors.py — audit #44 P1-2 canonical Jam wire fixtures.

The repo carries TWO frozen 48-byte CRDT-op wire formats (the audit's
"two parallel implementations of the same distributed protocol"):

  • KOTLIN  (jam/JamOpWire.kt) — rides inside JamWire Msg.OP frames over
    the mesh between Kotlin peers; also the Merkle-leaf hash input:
       [0..8)   op_id     u64 LE
       [8..16)  sender    u64 LE (packed device nonce, both halves)
       [16..20) type      u32 LE (1=ADD 2=REMOVE 3=REORDER)
       [20..24) policy    u32 LE (0=HOST_ONLY 1=EVERYONE)
       [24..32) cad_id    u64 LE
       [32..40) frac      f64 LE raw bits
       [40..48) target    u64 LE

  • RUST    (rust/src/jam_crdt.rs JamOp::to_bytes) — the Phase-2 Rust
    collaborative-playlist surface (MSG_CRDT_OP payloads):
       [0..8)   op_id     u64 LE
       [8..12)  sender_nonce [u8;4]  — low u32 of sender, BIG-endian
                                         (mirrors nativeJamCrdtApplyOp)
       [12]     op_type   u8
       [13]     policy    u8
       [14..16) pad       0x00 0x00
       [16..24) cad_id    u64 LE
       [24..32) frac      f64 LE raw bits
       [32..40) target    u64 LE
       [40..44) checksum  u32 LE — FNV-1a 32 over bytes[0..40]
       [44..48) pad       0x00 x4

They are DIFFERENT formats for the same conceptual op and are consumed by
disjoint code paths today — which is precisely why nothing enforces
bit-parity: a refactor on either side drifts silently. This script is the
neutral third party that freezes both byte images (plus their BLAKE3
hashes) into ONE canonical fixture, generated from the documented layouts
— not from either implementation — and consumed by both the Kotlin
(JamOpWireTest) and Rust (test_jam_golden_vectors) suites.

Regenerate (after INTENTIONALLY changing a frozen layout — bump
format_version and update the layout docs first):
    python3 scripts/generate_jam_golden_vectors.py
"""
import json
import struct
from pathlib import Path

import blake3

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "fixtures" / "jam_golden_vectors.json"

FNV1A_32_OFFSET = 0x811C9DC5
FNV1A_32_PRIME = 0x01000193


def fnv1a32(data: bytes) -> int:
    h = FNV1A_32_OFFSET
    for b in data:
        h ^= b
        h = (h * FNV1A_32_PRIME) & 0xFFFFFFFF
    return h


def kotlin_pack(op_id: int, sender: int, op_type: int, policy: int,
                cad_id: int, frac_bits: int, target: int) -> bytes:
    return struct.pack(
        "<QQIIQQQ", op_id, sender, op_type, policy, cad_id, frac_bits, target
    )


def rust_pack(op_id: int, sender: int, op_type: int, policy: int,
              cad_id: int, frac_bits: int, target: int) -> bytes:
    b = bytearray(48)
    struct.pack_into("<Q", b, 0, op_id)
    b[8:12] = (sender & 0xFFFFFFFF).to_bytes(4, "big")  # JNI: to_be_bytes()
    b[12] = op_type
    b[13] = policy
    # b[14..16] pad = 0
    struct.pack_into("<Q", b, 16, cad_id)
    struct.pack_into("<Q", b, 24, frac_bits)
    struct.pack_into("<Q", b, 32, target)
    struct.pack_into("<I", b, 40, fnv1a32(bytes(b[:40])))
    # b[44..48] pad = 0
    return bytes(b)


# ── representative ops ────────────────────────────────────────────────
# Field values chosen to cross every boundary: distinct byte lanes,
# high bits set (bit-plasticity), the canonical 0.3 fractional index
# (non-terminating binary → exact-bits matters), a denormal-ish tiny
# fraction, and op types / policies across their enums.
VECTORS = [
    # name, op_id, sender, type, policy, cad_id, frac_bits, target
    ("add-basic",        0x0000000100000001, 0x00000000_00A11CE0, 1, 1,
                        0x0000BEEF0000CAFE, 0x3FD3333333333333, 0),
    ("add-high-bits",    0xDEADBEEFCAFEBABE, 0xFFEEDDCC_00112233, 1, 0,
                        0xA1B2C3D4E5F60718, 0x3FF0000000000000, 0),
    ("remove-basic",     0x0000000100000002, 0x00000000_00BEEF00, 2, 1,
                        0x0000BEEF0000CAFE, 0x3FD3333333333333,
                        0x0000000100000001),
    ("reorder-tiny-frac", 0x0000000200000007, 0x12345678_9ABCDEF0, 3, 0,
                        0x0F0E0D0C0B0A0908, 0x3E112E0BE826D695,
                        0x0000000100000002),
    ("add-frac-half",    0x0000000300000001, 0x00000000_00000042, 1, 1,
                        1, 0x3FE0000000000000, 0),
]

# Hash-only images (not round-trippable ops): the Merkle pad sentinel is
# the sealed image of 48 zero bytes — hashed by BOTH stacks as the empty
# slot filler.
HASH_ONLY = [
    ("merkle-pad-leaf-48-zero-bytes", bytes(48)),
]


def main() -> None:
    vectors = []
    for (name, op_id, sender, op_type, policy, cad_id, frac_bits, target) in VECTORS:
        k = kotlin_pack(op_id, sender, op_type, policy, cad_id, frac_bits, target)
        r = rust_pack(op_id, sender, op_type, policy, cad_id, frac_bits, target)
        vectors.append({
            "name": name,
            "fields": {
                "op_id": f"0x{op_id:016x}",
                "sender": f"0x{sender:016x}",
                "op_type": op_type,
                "policy": policy,
                "cad_id": f"0x{cad_id:016x}",
                "frac_bits": f"0x{frac_bits:016x}",
                "target": f"0x{target:016x}",
            },
            "kotlin_48": k.hex(),
            "rust_48": r.hex(),
            "kotlin_48_blake3": blake3.blake3(k).hexdigest(),
            "rust_48_blake3": blake3.blake3(r).hexdigest(),
        })

    hashes = [
        {"name": n, "input_hex": b.hex(), "blake3": blake3.blake3(b).hexdigest()}
        for (n, b) in HASH_ONLY
    ]

    doc = {
        "format_version": 1,
        "description": (
            "Canonical golden vectors for the two frozen 48-byte Jam CRDT-op "
            "wire formats. Generated by scripts/generate_jam_golden_vectors.py "
            "from the DOCUMENTED layouts (neutral third party — not from "
            "either implementation). Consumed by Kotlin JamOpWireTest and "
            "rust/tests/test_jam_golden_vectors.rs: any byte-image drift on "
            "either side fails that suite. audit #44 P1-2."
        ),
        "vectors": vectors,
        "hash_only": hashes,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(vectors)} op vectors, {len(hashes)} hash-only)")
    for v in vectors:
        print(f"  {v['name']}: kotlin={v['kotlin_48'][:16]}… rust={v['rust_48'][:16]}…")


if __name__ == "__main__":
    main()
