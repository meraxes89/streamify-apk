// ============================================================================
//  CandidateHasher.cc — NEON bulk candidate-ID hashing (Phase 2)
// ============================================================================
//
//  See CandidateHasher.h for the design contract. The stripe kernel below is
//  the bit-exact NEON twin of mixStripe(): same operations, same order —
//  integer modular arithmetic makes the equivalence structural (u32 x u32
//  products cannot overflow, u64 adds commute), not incidental.
// ============================================================================

#include "CandidateHasher.h"

#include <cstring>

#include "../util/NeonCompat.h"

#if STREAMIFY_HAVE_NEON
#include <arm_neon.h>
#endif

namespace streamify {
namespace math {
namespace {

// XXH3 / XXH64 prime constants (xxHash, BSD-2 licensed reference values).
constexpr uint64_t kPrime1 = 0x9E3779B185EEDA5Bull;
constexpr uint64_t kPrime2 = 0xC2B2AE3D27D4EB4Full;
constexpr uint64_t kPrime3 = 0x165667B19E3779F9ull;
constexpr uint64_t kPrime4 = 0x85EBCA77C2B2AE63ull;
constexpr uint64_t kPrime5 = 0x27D4EB2F165667C5ull;

inline uint64_t rotl64(uint64_t x, int r) {
    return (x << r) | (x >> (64 - r));
}

// XXH64 finalizer — the avalanche of the whole construction.
inline uint64_t avalanche64(uint64_t x) {
    x ^= x >> 33;
    x *= kPrime2;
    x ^= x >> 29;
    x *= kPrime3;
    x ^= x >> 32;
    return x;
}

inline uint64_t read64(const char* p) {
    uint64_t v;
    std::memcpy(&v, p, sizeof(v));   // unaligned-safe, UB-free
    return v;
}

inline uint32_t read32(const char* p) {
    uint32_t v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}

// Murmur3-64-style tail mixing, shared by the short path and the long path's
// trailing (< 16) bytes. Advances p / rem; mutates h in place.
inline void mixTail(const char*& p, uint32_t& rem, uint64_t& h) {
    while (rem >= 8) {
        uint64_t k = read64(p);
        k *= kPrime2;
        k = rotl64(k, 31);
        k *= kPrime1;
        h ^= k;
        h = rotl64(h, 27) * kPrime1 + kPrime4;
        p += 8;
        rem -= 8;
    }
    if (rem >= 4) {
        h ^= static_cast<uint64_t>(read32(p)) * kPrime1;
        h = rotl64(h, 23) * kPrime2 + kPrime3;
        p += 4;
        rem -= 4;
    }
    while (rem > 0) {
        h ^= static_cast<uint64_t>(static_cast<uint8_t>(*p++)) * kPrime5;
        h = rotl64(h, 11) * kPrime1;
        --rem;
    }
}

// XXH3-style 32x32 lane-product stripe mix — exact scalar mirror of the NEON
// kernel in hashLongNeon() (identical operation order => bit-identical).
inline void mixStripe(uint64_t& acc0, uint64_t& acc1, uint64_t d0, uint64_t d1,
                      uint64_t k0, uint64_t k1) {
    const uint64_t dk0 = d0 ^ k0;
    const uint64_t dk1 = d1 ^ k1;
    acc0 += static_cast<uint64_t>(static_cast<uint32_t>(dk0)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d0));
    acc1 += static_cast<uint64_t>(static_cast<uint32_t>(dk0 >> 32)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d0 >> 32));
    acc0 += static_cast<uint64_t>(static_cast<uint32_t>(dk1)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d1));
    acc1 += static_cast<uint64_t>(static_cast<uint32_t>(dk1 >> 32)) *
            static_cast<uint64_t>(static_cast<uint32_t>(d1 >> 32));
}

// Position-dependent stripe keys: two different key streams so the two words
// of a stripe can never mix with the same multiplier.
inline void stripeKeys(uint64_t stripeIndex, uint64_t byteOffset, uint64_t& k0,
                       uint64_t& k1) {
    k0 = rotl64(kPrime1 * (stripeIndex + 1), 29) ^ (kPrime2 + byteOffset);
    k1 = rotl64(kPrime2 * (stripeIndex + 2), 31) ^ (kPrime3 + byteOffset);
}

uint64_t finalizeLong(uint64_t acc0, uint64_t acc1, uint32_t length,
                      const char* bytes) {
    uint64_t h = acc0 ^ rotl64(acc1, 29) ^ (static_cast<uint64_t>(length) * kPrime5);
    const uint64_t stripes = length / 16;
    const char* p = bytes + stripes * 16;
    uint32_t rem = length - static_cast<uint32_t>(stripes * 16);
    mixTail(p, rem, h);
    return avalanche64(h);
}

// NEON-accelerated membership test: is `h` present in hashes[0..n)?
//
// Equality via XOR + lane extract (v7-safe: no 64-bit VCEQ):
//   x = veorq(vector, broadcast(h)); lane k of x is zero iff the matching
//   element equals h — HIT iff EITHER lane is zero.
//
// Host/scalar fallback counts matches BRANCHLESSLY: an early `return true`
// would defeat auto-vectorization, and the low hit-rate candidate regime
// scans nearly the whole array anyway — the vectorizer's 4-8 u64/iteration
// beats a scalar early-exit by 3-6x on the x86 CI runners.
inline bool containsHash(const uint64_t* hashes, int32_t n, uint64_t h) {
    if (hashes == nullptr || n <= 0) {
        return false;
    }
#if STREAMIFY_HAVE_NEON
    const uint64x2_t hv = vdupq_n_u64(h);
    int32_t i = 0;
    for (; i + 2 <= n; i += 2) {
        const uint64x2_t x = veorq_u64(vld1q_u64(hashes + i), hv);
        const uint64_t x0 = vgetq_lane_u64(x, 0);
        const uint64_t x1 = vgetq_lane_u64(x, 1);
        if (x0 == 0 || x1 == 0) {
            return true;
        }
    }
    for (; i < n; ++i) {
        if (hashes[i] == h) {
            return true;
        }
    }
    return false;
#else
    uint32_t hits = 0;
    for (int32_t i = 0; i < n; ++i) {
        hits += (hashes[i] == h) ? 1u : 0u;
    }
    return hits != 0;
#endif
}

inline int32_t sanitizeCount(int32_t n, const void* p) {
    if (p == nullptr || n < 0) {
        return 0;
    }
    return n;
}

// ---------------------------------------------------------------------------
// Stack open-addressing key table — the O(1)-membership accelerator for the
// dedup kernels.
//
//  * Zero heap: 12 KB of stack (8 KB keys + 4 KB first-index), released on
//    return; safe on any JVM/JNI calling thread.
//  * EXACTNESS CONTRACT: the table never lies — it either holds every
//    distinct key of the stream it was built from (fast path), or it
//    reports overflow and the caller recomputes with the brute-force scans.
//    Correctness never depends on the table; only speed does.
//  * Envelope: 1024 distinct keys per stream (the Blend-candidate mandate
//    is "hundreds"); beyond that, or below a 64-element brute-force
//    crossover, the kernels take the exact fallback path.
//  * firstIdx[] doubles as the occupancy map (kEmpty sentinel), so key==0
//    is a perfectly legal hash value.
//  * Pure integer ops — deterministic and bit-identical on every ABI.
// ---------------------------------------------------------------------------
struct KeyTable {
    static constexpr int32_t kSlots = 1024;   // power of two
    static constexpr int32_t kMask = kSlots - 1;
    static constexpr uint32_t kEmpty = 0xFFFFFFFFu;

    uint64_t keys[kSlots];
    uint32_t firstIdx[kSlots];
    bool overflow = false;

    void clear() {
        std::memset(firstIdx, 0xFF, sizeof(firstIdx));
        overflow = false;
    }

    static int32_t home(uint64_t key) {
        uint64_t z = key + 0x9E3779B97F4A7C15ull;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return static_cast<int32_t>((z ^ (z >> 31)) & kMask);
    }

    // Insert `key` seen at stream index `idx`. Returns the FIRST index at
    // which `key` occurs, or -1 on overflow (table full, key new) — callers
    // must then abandon the fast path (results already derived stay valid).
    int32_t upsert(uint64_t key, uint32_t idx) {
        if (overflow) {
            return -1;
        }
        int32_t s = home(key);
        for (int32_t probes = 0; probes < kSlots; ++probes) {
            if (firstIdx[s] == kEmpty) {
                keys[s] = key;
                firstIdx[s] = idx;
                ++used;
                return static_cast<int32_t>(idx);
            }
            if (keys[s] == key) {
                return static_cast<int32_t>(firstIdx[s]);
            }
            s = (s + 1) & kMask;
        }
        overflow = true;   // probed every slot: no room, no match
        return -1;
    }

    // First index of `key`, or -1 if absent. Only meaningful while the
    // table is NOT in overflow (see the exactness contract above).
    int32_t find(uint64_t key) const {
        int32_t s = home(key);
        for (int32_t probes = 0; probes < kSlots; ++probes) {
            if (firstIdx[s] == kEmpty) {
                return -1;
            }
            if (keys[s] == key) {
                return static_cast<int32_t>(firstIdx[s]);
            }
            s = (s + 1) & kMask;
        }
        return -1;
    }

private:
    int32_t used = 0;
};

// Builds a table over hashes[0..n). Returns false on overflow (table then
// unusable — caller must use the brute-force path).
inline bool buildKeyTable(KeyTable& tab, const uint64_t* hashes, int32_t n) {
    for (int32_t i = 0; i < n; ++i) {
        if (tab.upsert(hashes[i], static_cast<uint32_t>(i)) < 0) {
            return false;
        }
    }
    return true;
}

// Brute-force crossover: below this total stream size the table build +
// 12 KB clear costs more than it saves.
constexpr int32_t kTableCrossover = 64;

// Audit #22 item 9: KeyTable is 12 KB (8 KB keys + 4 KB firstIdx); the
// union fast path used to stack TWO of them (24 KB) inside the JNI
// ranking call chain, whose threads run small stacks. Thread-local
// scratch removes the stack-overflow risk with zero per-call
// allocation (first touch per thread only; every caller clear()s
// before use, so no state crosses calls).
thread_local KeyTable tlsKeyTableA;
thread_local KeyTable tlsKeyTableB;

}  // namespace

uint64_t hashCandidateIdReference(const char* bytes, uint32_t length,
                                  uint64_t seed) {
    if (bytes == nullptr || length == 0) {
        return avalanche64(seed ^ kPrime5);
    }
    if (length < 16) {
        uint64_t h = seed ^ (static_cast<uint64_t>(length) * kPrime5);
        const char* p = bytes;
        uint32_t rem = length;
        mixTail(p, rem, h);
        return avalanche64(h);
    }
    const uint64_t stripes = length / 16;
    uint64_t acc0 = seed ^ kPrime1 ^ static_cast<uint64_t>(length);
    uint64_t acc1 = seed ^ kPrime2;
    for (uint64_t i = 0; i < stripes; ++i) {
        const uint64_t off = i * 16;
        uint64_t k0;
        uint64_t k1;
        stripeKeys(i, off, k0, k1);
        mixStripe(acc0, acc1, read64(bytes + off), read64(bytes + off + 8), k0,
                  k1);
    }
    return finalizeLong(acc0, acc1, length, bytes);
}

#if STREAMIFY_HAVE_NEON
// NEON stripe kernel — the vector twin of the scalar loop above. v7-safe
// instruction set only (see header). acc[0] == scalar acc0, acc[1] == acc1.
static uint64_t hashLongNeon(const char* bytes, uint32_t length, uint64_t seed) {
    const uint64_t stripes = length / 16;
    const uint64x1_t a0 = vcreate_u64(seed ^ kPrime1 ^ static_cast<uint64_t>(length));
    const uint64x1_t a1 = vcreate_u64(seed ^ kPrime2);
    uint64x2_t acc = vcombine_u64(a0, a1);
    for (uint64_t i = 0; i < stripes; ++i) {
        const uint64_t off = i * 16;
        uint64_t k0;
        uint64_t k1;
        stripeKeys(i, off, k0, k1);
        // vld1q_u64 accepts arbitrary alignment (ACLE); no typed deref.
        const uint64x2_t data =
            vld1q_u64(reinterpret_cast<const uint64_t*>(bytes + off));
        const uint64x2_t key = vcombine_u64(vcreate_u64(k0), vcreate_u64(k1));
        const uint64x2_t dk = veorq_u64(data, key);
        const uint32x4_t d32 = vreinterpretq_u32_u64(data);
        const uint32x4_t k32 = vreinterpretq_u32_u64(dk);
        const uint64x2_t p0 =
            vmull_u32(vget_low_u32(d32), vget_low_u32(k32));
        const uint64x2_t p1 =
            vmull_u32(vget_high_u32(d32), vget_high_u32(k32));
        acc = vaddq_u64(vaddq_u64(acc, p0), p1);
    }
    return finalizeLong(vgetq_lane_u64(acc, 0), vgetq_lane_u64(acc, 1),
                        length, bytes);
}
#endif  // STREAMIFY_HAVE_NEON

uint64_t hashCandidateId(const char* bytes, uint32_t length, uint64_t seed) {
#if STREAMIFY_HAVE_NEON
    if (bytes != nullptr && length >= 16) {
        return hashLongNeon(bytes, length, seed);
    }
#endif
    return hashCandidateIdReference(bytes, length, seed);
}

void hashCandidateIdsBulk(const CandidateIdSpan* ids, std::size_t count,
                          uint64_t* outHashes, uint64_t seed) {
    if (ids == nullptr || outHashes == nullptr) {
        return;
    }
    for (std::size_t i = 0; i < count; ++i) {
        if (i + 1 < count && ids[i + 1].bytes != nullptr) {
            __builtin_prefetch(ids[i + 1].bytes, 0, 3);
        }
        outHashes[i] = hashCandidateId(ids[i].bytes, ids[i].length, seed);
    }
}

int32_t computeIntersectionMasks(const uint64_t* hashesA, int32_t countA,
                                 const uint64_t* hashesB, int32_t countB,
                                 uint8_t* outMaskA, uint8_t* outMaskB) {
    const int32_t nA = sanitizeCount(countA, hashesA);
    const int32_t nB = sanitizeCount(countB, hashesB);
    if (outMaskA != nullptr) {
        std::memset(outMaskA, 0, static_cast<std::size_t>(nA));
    }
    if (outMaskB != nullptr) {
        std::memset(outMaskB, 0, static_cast<std::size_t>(nB));
    }
    if (nA == 0 || nB == 0) {
        return 0;   // empty stream: nothing can intersect
    }

    // Fast path: one stack table per direction, O(nA + nB) expected time.
    // Falls through to the NEON/branchless brute scans when the distinct-key
    // count overflows the table or the streams are tiny.
    if (nA + nB >= kTableCrossover) {
        KeyTable& tab = tlsKeyTableA;
        tab.clear();
        if (buildKeyTable(tab, hashesB, nB)) {
            int32_t commonA = 0;
            for (int32_t i = 0; i < nA; ++i) {
                if (tab.find(hashesA[i]) >= 0) {
                    if (outMaskA != nullptr) {
                        outMaskA[i] = 1;
                    }
                    ++commonA;
                }
            }
            tab.clear();
            if (buildKeyTable(tab, hashesA, nA)) {
                if (outMaskB != nullptr) {
                    for (int32_t j = 0; j < nB; ++j) {
                        if (tab.find(hashesB[j]) >= 0) {
                            outMaskB[j] = 1;
                        }
                    }
                }
                return commonA;
            }
            // Table A overflowed after maskA resolved — finish maskB with the
            // exact brute path (maskA + commonA above remain valid).
            for (int32_t j = 0; j < nB; ++j) {
                if (outMaskB != nullptr && containsHash(hashesA, nA, hashesB[j])) {
                    outMaskB[j] = 1;
                }
            }
            return commonA;
        }
    }

    // Brute path (exact, NEON/branchless-vectorized scans).
    int32_t commonA = 0;
    for (int32_t i = 0; i < nA; ++i) {
        if (containsHash(hashesB, nB, hashesA[i])) {
            if (outMaskA != nullptr) {
                outMaskA[i] = 1;
            }
            ++commonA;
        }
    }
    for (int32_t j = 0; j < nB; ++j) {
        if (outMaskB != nullptr && containsHash(hashesA, nA, hashesB[j])) {
            outMaskB[j] = 1;
        }
    }
    return commonA;
}

int32_t computeUniqueUnion(const uint64_t* hashesA, int32_t countA,
                           const uint64_t* hashesB, int32_t countB,
                           uint64_t* outUnion, uint8_t* outIsOverlap,
                           int32_t capacity) {
    const int32_t nA = sanitizeCount(countA, hashesA);
    const int32_t nB = sanitizeCount(countB, hashesB);
    // NULL outUnion can never absorb writes, whatever capacity claims.
    const int32_t cap =
        (outUnion == nullptr || capacity < 0) ? 0 : capacity;
    int32_t emitted = 0;

    // Dedup scans the INPUT streams only, never the output buffer — a
    // truncated or NULL output (pure size probe) computes the exact same
    // REQUIRED size as a full write, with no out-of-bounds reads.

    // Fast path: stack tables give O(1) expected membership AND first-index
    // (dup detection) for both streams. tableA over A, tableB over B.
    if (nA + nB >= kTableCrossover) {
        KeyTable& tabA = tlsKeyTableA;
        KeyTable& tabB = tlsKeyTableB;
        tabA.clear();
        tabB.clear();
        if (buildKeyTable(tabA, hashesA, nA) &&
            buildKeyTable(tabB, hashesB, nB)) {
            // Pass 1 — unique values of A in first-seen order, overlap-flagged.
            for (int32_t i = 0; i < nA; ++i) {
                if (tabA.find(hashesA[i]) < i) {
                    continue;   // within-A duplicate (first occurrence earlier)
                }
                if (emitted < cap) {
                    outUnion[emitted] = hashesA[i];
                    if (outIsOverlap != nullptr) {
                        outIsOverlap[emitted] = tabB.find(hashesA[i]) >= 0 ? 1 : 0;
                    }
                }
                ++emitted;
            }
            // Pass 2 — B-only uniques (in A => already emitted, overlap).
            for (int32_t j = 0; j < nB; ++j) {
                if (tabA.find(hashesB[j]) >= 0) {
                    continue;
                }
                if (tabB.find(hashesB[j]) < j) {
                    continue;   // within-B duplicate
                }
                if (emitted < cap) {
                    outUnion[emitted] = hashesB[j];
                    if (outIsOverlap != nullptr) {
                        outIsOverlap[emitted] = 0;   // B-only by construction
                    }
                }
                ++emitted;
            }
            return emitted;
        }
        // Overflow (or tiny crossover): fall through to the exact brute path.
    }

    // Pass 1 — unique values of A in first-seen order, overlap-flagged.
    for (int32_t i = 0; i < nA; ++i) {
        if (containsHash(hashesA, i, hashesA[i])) {
            continue;   // within-A duplicate (appeared earlier in A)
        }
        if (emitted < cap) {
            outUnion[emitted] = hashesA[i];
            if (outIsOverlap != nullptr) {
                outIsOverlap[emitted] =
                    containsHash(hashesB, nB, hashesA[i]) ? 1 : 0;
            }
        }
        ++emitted;
    }

    // Pass 2 — unique values of B absent from A ("B-only" adds), first-seen
    // order, overlap 0 by construction.
    for (int32_t j = 0; j < nB; ++j) {
        if (containsHash(hashesA, nA, hashesB[j])) {
            continue;   // already emitted from the A side (overlap-flagged)
        }
        if (containsHash(hashesB, j, hashesB[j])) {
            continue;   // within-B duplicate (appeared earlier in B)
        }
        if (emitted < cap) {
            outUnion[emitted] = hashesB[j];
            if (outIsOverlap != nullptr) {
                outIsOverlap[emitted] = 0;
            }
        }
        ++emitted;
    }
    return emitted;
}

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI (Rust FFI) — see CandidateHasher.h.
// ---------------------------------------------------------------------------
extern "C" {

STREAMIFY_MATH_API uint64_t streamify_hash_candidate_id(const char* id,
                                                        int32_t length) {
    if (id == nullptr) {
        return streamify::math::hashCandidateId(nullptr, 0);
    }
    uint32_t len;
    if (length < 0) {
        len = static_cast<uint32_t>(std::strlen(id));
    } else {
        len = static_cast<uint32_t>(length);
    }
    return streamify::math::hashCandidateId(id, len);
}

STREAMIFY_MATH_API void streamify_hash_candidate_ids_bulk(
    const char* const* idBytes, const int32_t* idLengths, int32_t count,
    uint64_t* outHashes) {
    if (idBytes == nullptr || idLengths == nullptr || outHashes == nullptr ||
        count <= 0) {
        return;
    }
    for (int32_t i = 0; i < count; ++i) {
        const char* bytes = idBytes[i];
        if (bytes == nullptr) {
            outHashes[i] =
                streamify::math::hashCandidateId(nullptr, 0);
            continue;
        }
        const int32_t len = idLengths[i];
        outHashes[i] = streamify::math::hashCandidateId(
            bytes, len > 0 ? static_cast<uint32_t>(len) : 0u);
    }
}

STREAMIFY_MATH_API int32_t streamify_candidate_intersection(
    const uint64_t* hashesA, int32_t countA, const uint64_t* hashesB,
    int32_t countB, uint8_t* outMaskA, uint8_t* outMaskB) {
    return streamify::math::computeIntersectionMasks(hashesA, countA, hashesB,
                                                     countB, outMaskA,
                                                     outMaskB);
}

STREAMIFY_MATH_API int32_t streamify_candidate_union(
    const uint64_t* hashesA, int32_t countA, const uint64_t* hashesB,
    int32_t countB, uint64_t* outUnion, uint8_t* outIsOverlap,
    int32_t capacity) {
    return streamify::math::computeUniqueUnion(hashesA, countA, hashesB,
                                               countB, outUnion, outIsOverlap,
                                               capacity);
}

}  // extern "C"
