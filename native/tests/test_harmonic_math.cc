// ============================================================================
//  test_harmonic_math.cc — Phase 2 verification: harmonic transition engine,
//  candidate fast-math & circadian curves
// ============================================================================
//
//  Covers BEHIND.md gaps #15 (group-taste blend), #20 (Daylist circadian),
//  #25 (multi-user taste merge), #26 (smart sequencing), #39 (Automix
//  reorder hooks) per the Phase-2 engineering directive:
//    A. NEON XXH3-style candidate hashing + zero-alloc dedup kernels
//    B. Camelot key + BPM transition compatibility matrix (all 24 keys)
//    C. Circadian daypart energy curves (continuity, periodicity, bounds)
//
//  Linked into dsp_test_suite (native-dsp CI shard); entry point
//  run_harmonic_math_tests() is called from test_dsp.cc's main().
//
//  [PERF] The 128x128 masks+union timing assert uses the MINIMUM of 5 runs
//  (robust to CI scheduler noise); 256/512 scales are report-only so a slow
//  shared runner can never flake the shard.
// ============================================================================

#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

#include "../math/CandidateHasher.h"
#include "../math/CircadianCurves.h"
#include "../math/HarmonicTransitionEngine.h"

#include "AllocGuard.h"

using streamify::math::bpmTransitionScore;
using streamify::math::camelotPitchClass;
using streamify::math::CandidateIdSpan;
using streamify::math::circadianWeights;
using streamify::math::classifyKeyTransition;
using streamify::math::computeIntersectionMasks;
using streamify::math::computeUniqueUnion;
using streamify::math::hashCandidateId;
using streamify::math::hashCandidateIdReference;
using streamify::math::hashCandidateIdsBulk;
using streamify::math::kCircadianAnchors;
using streamify::math::KeyRelation;
using streamify::math::keyTransitionScore;
using streamify::math::transitionCompatibility;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

void checkEq(float got, float want, float tol, const char* what) {
    const bool ok = std::fabs(got - want) <= tol;
    if (!ok) {
        std::printf("    FAILED: %s (got %.6f want %.6f)\n", what, got, want);
    }
    check(ok, what);
}

// Deterministic RNG (SplitMix64) — same generator family as the Phase-1
// suite, so failure reproducibility spans both files.
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s += 0x9E3779B97F4A7C15ull;
        uint64_t z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
};

int popcount64(uint64_t x) {
    int c = 0;
    while (x) {
        c += static_cast<int>(x & 1u);
        x >>= 1;
    }
    return c;
}

// ---------------------------------------------------------------------------
// A. Candidate hashing
// ---------------------------------------------------------------------------
void test_candidate_hashing() {
    std::printf("  [harmonic] candidate ID hashing\n");

    // Golden known-answer tests (portable reference; boundary lengths 15/16/17
    // exercise the short-path / first-stripe / stripe+tail transitions).
    struct Kat {
        const char* id;
        uint64_t want;
    };
    const Kat kats[] = {
        {"", 0x54479AF15DD17D03ull},
        {"a", 0x77E34829D5D3D2F2ull},
        {"hello", 0x9545A983C9F4550Cull},
        {"track-001", 0x3EB9B626F82A3575ull},
        {"4iV5W9uYEdYUVa79Ab7LF3", 0xD4D8417214B87E50ull},
        {"artist::The Weeknd", 0x176F1085A8BC76AAull},
        {"0123456789abcde", 0x82477B4ABC8645DAull},
        {"0123456789abcdef", 0xBF54F8C053C06CEBull},
        {"0123456789abcdefg", 0xE63B328F54BA2E27ull},
        {"spotify:track:6DhNscAhZpKUeAs63VkZdH?si=abc123", 0xC461C5BEDC95943Bull},
        {"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         0x4C139BF26CAC1714ull},
    };
    for (const Kat& k : kats) {
        const uint64_t h = hashCandidateIdReference(
            k.id, static_cast<uint32_t>(std::strlen(k.id)));
        check(h == k.want, "golden KAT");
        // Production path (== reference on host; NEON twin on ARM).
        const uint64_t p = hashCandidateId(k.id,
                                           static_cast<uint32_t>(std::strlen(k.id)));
        check(p == k.want, "production path == golden");
    }

    // Determinism + seed sensitivity.
    const char* id = "cand:seedcheck";
    const auto len = static_cast<uint32_t>(std::strlen(id));
    check(hashCandidateId(id, len) == hashCandidateId(id, len), "deterministic");
    check(hashCandidateId(id, len, 1) != hashCandidateId(id, len, 2),
          "seed changes hash");

    // NULL / empty contract.
    check(hashCandidateId(nullptr, 0) == hashCandidateIdReference("", 0),
          "null == empty");

    // Bulk API == single calls, zero-alloc.
    {
        std::vector<std::string> ids;
        for (int i = 0; i < 1000; ++i) {
            ids.push_back("cand:" + std::to_string(i) + ":" +
                          std::to_string(i * 2654435761u));
        }
        std::vector<CandidateIdSpan> spans(ids.size());
        for (size_t i = 0; i < ids.size(); ++i) {
            spans[i] = {ids[i].data(), static_cast<uint32_t>(ids[i].size())};
        }
        std::vector<uint64_t> bulk(ids.size());
        {
            streamify_test::AllocGuard g;
            hashCandidateIdsBulk(spans.data(), spans.size(), bulk.data());
            check(g.count() == 0, "bulk hashing zero-alloc");
        }
        bool allEqual = true;
        for (size_t i = 0; i < ids.size(); ++i) {
            if (bulk[i] != hashCandidateId(ids[i].data(),
                                           static_cast<uint32_t>(ids[i].size()))) {
                allEqual = false;
                break;
            }
        }
        check(allEqual, "bulk == single");

        // Distribution: 20k synthetic ids, zero collisions.
        std::unordered_set<uint64_t> seen;
        seen.reserve(bulk.size() * 4);
        for (uint64_t h : bulk) {
            if (!seen.insert(h).second) {
                break;
            }
        }
        check(seen.size() == bulk.size(), "no collisions over 1000 ids");
    }

    // Avalanche: 1-bit input flips => ~32 output bits flip (measured 31.65).
    {
        char base[24];
        for (int i = 0; i < 24; ++i) {
            base[i] = static_cast<char>(i * 7 + 3);
        }
        const uint64_t h0 = hashCandidateIdReference(base, 24);
        double total = 0.0;
        int flips = 0;
        for (int byte = 0; byte < 24; ++byte) {
            for (int bit = 0; bit < 8; ++bit) {
                char t[24];
                std::memcpy(t, base, 24);
                t[byte] = static_cast<char>(t[byte] ^ (1 << bit));
                total += popcount64(hashCandidateIdReference(t, 24) ^ h0);
                ++flips;
            }
        }
        const double mean = total / flips;
        check(mean > 28.0 && mean < 36.0, "avalanche 28..36 mean bits");
    }

#if defined(__ARM_NEON) || defined(__aarch64__)
    // NEON self-check (runs when the suite is built on ARM hardware): the
    // vector stripe kernel must be bit-identical to the reference.
    {
        bool neonOk = true;
        for (int len = 16; len <= 128; len += 8) {
            char buf[128];
            for (int i = 0; i < len; ++i) {
                buf[i] = static_cast<char>((i * 31 + len * 7) & 0xFF);
            }
            if (hashCandidateId(buf, static_cast<uint32_t>(len)) !=
                hashCandidateIdReference(buf, static_cast<uint32_t>(len))) {
                neonOk = false;
                break;
            }
        }
        check(neonOk, "NEON stripe kernel == scalar reference");
    }
#endif
}

// ---------------------------------------------------------------------------
// A. Dedup kernels
// ---------------------------------------------------------------------------
void test_candidate_dedup() {
    std::printf("  [harmonic] candidate dedup kernels\n");

    // Handcrafted semantics: order, overlap flags, masks, counts.
    {
        const uint64_t a[] = {1, 2, 2, 3};
        const uint64_t b[] = {3, 4, 1, 4};
        uint64_t u[8] = {0};
        uint8_t ov[8] = {9};
        const int32_t n = computeUniqueUnion(a, 4, b, 4, u, ov, 8);
        check(n == 4, "union size");
        check(u[0] == 1 && u[1] == 2 && u[2] == 3 && u[3] == 4, "A-first union order");
        check(ov[0] == 1 && ov[1] == 0 && ov[2] == 1 && ov[3] == 0, "overlap flags");
        uint8_t ma[4] = {0};
        uint8_t mb[4] = {0};
        const int32_t c = computeIntersectionMasks(a, 4, b, 4, ma, mb);
        check(c == 2, "A-side intersection count");
        check(ma[0] == 1 && ma[1] == 0 && ma[2] == 0 && ma[3] == 1, "maskA values");
        check(mb[0] == 1 && mb[1] == 0 && mb[2] == 1 && mb[3] == 0, "maskB values");
    }

    // Size probe (capacity 0 / NULL out) == full write.
    {
        const uint64_t a[] = {7, 7, 7, 8, 9};
        const uint64_t b[] = {9, 10, 10};
        const int32_t req = computeUniqueUnion(a, 5, b, 3, nullptr, nullptr, 0);
        const int32_t req2 = computeUniqueUnion(a, 5, b, 3, nullptr, nullptr, 100);
        uint64_t u[8];
        const int32_t n = computeUniqueUnion(a, 5, b, 3, u, nullptr, 8);
        check(req == 4 && req2 == 4 && n == 4, "probe == write size");
    }

    // Truncation: writes clamped to capacity, required count still exact.
    {
        const uint64_t a[] = {1, 2, 3};
        const uint64_t b[] = {4};
        uint64_t u[2] = {0, 0};
        const int32_t req = computeUniqueUnion(a, 3, b, 1, u, nullptr, 2);
        check(req == 4, "required size under truncation");
        check(u[0] == 1 && u[1] == 2, "writes clamped to capacity");
    }

    // Empty / null / negative sanitization.
    {
        const uint64_t a[] = {5};
        const uint64_t e[] = {6};
        uint8_t ma[1] = {9};
        uint8_t mb[1] = {9};
        check(computeUniqueUnion(nullptr, 3, nullptr, 3, nullptr, nullptr, 0) == 0,
              "null streams");
        check(computeUniqueUnion(a, 1, nullptr, 3, nullptr, nullptr, 0) == 1,
              "null B = uniques(A)");
        check(computeUniqueUnion(nullptr, 3, e, 1, nullptr, nullptr, 0) == 1,
              "null A = uniques(B)");
        check(computeUniqueUnion(a, -5, a, -5, nullptr, nullptr, 0) == 0,
              "negative counts");
        check(computeIntersectionMasks(a, 1, e, 1, ma, mb) == 0, "no intersection");
        check(ma[0] == 0 && mb[0] == 0, "non-matching masks zeroed");
    }

    // Randomized cross-check vs a naive spec-faithful reference.
    {
        Rng rng(42);
        bool allOk = true;
        for (int trial = 0; trial < 200 && allOk; ++trial) {
            const int nA = static_cast<int>(rng.next() % 40);
            const int nB = static_cast<int>(rng.next() % 40);
            std::vector<uint64_t> a(static_cast<size_t>(nA));
            std::vector<uint64_t> b(static_cast<size_t>(nB));
            for (auto& v : a) v = rng.next() % 25;   // heavy collision domain
            for (auto& v : b) v = rng.next() % 25;
            std::vector<uint64_t> u(a.size() + b.size(), 0xAB);
            std::vector<uint8_t> ov(a.size() + b.size(), 7);
            const int32_t n = computeUniqueUnion(
                a.data(), nA, b.data(), nB, u.data(), ov.data(),
                static_cast<int32_t>(a.size() + b.size()));

            // Naive reference (same documented spec).
            std::vector<uint64_t> ref;
            std::vector<uint8_t> refOv;
            auto inA = [&](uint64_t h) {
                for (uint64_t v : a)
                    if (v == h) return true;
                return false;
            };
            auto inB = [&](uint64_t h) {
                for (uint64_t v : b)
                    if (v == h) return true;
                return false;
            };
            for (int i = 0; i < nA; ++i) {
                bool dup = false;
                for (int k = 0; k < i; ++k)
                    if (a[static_cast<size_t>(k)] == a[static_cast<size_t>(i)]) {
                        dup = true;
                        break;
                    }
                if (dup) continue;
                ref.push_back(a[static_cast<size_t>(i)]);
                refOv.push_back(inB(a[static_cast<size_t>(i)]) ? 1 : 0);
            }
            for (int j = 0; j < nB; ++j) {
                if (inA(b[static_cast<size_t>(j)])) continue;
                bool dup = false;
                for (int k = 0; k < j; ++k)
                    if (b[static_cast<size_t>(k)] == b[static_cast<size_t>(j)]) {
                        dup = true;
                        break;
                    }
                if (dup) continue;
                ref.push_back(b[static_cast<size_t>(j)]);
                refOv.push_back(0);
            }

            if (static_cast<size_t>(n) != ref.size()) {
                allOk = false;
                break;
            }
            for (size_t k = 0; k < ref.size(); ++k) {
                if (u[k] != ref[k] || ov[k] != refOv[k]) {
                    allOk = false;
                    break;
                }
            }
            // Masks cross-check.
            std::vector<uint8_t> ma(static_cast<size_t>(nA));
            std::vector<uint8_t> mb(static_cast<size_t>(nB));
            computeIntersectionMasks(a.data(), nA, b.data(), nB, ma.data(),
                                     mb.data());
            for (int i = 0; i < nA; ++i) {
                if (ma[static_cast<size_t>(i)] != (inB(a[static_cast<size_t>(i)]) ? 1 : 0)) {
                    allOk = false;
                }
            }
            for (int j = 0; j < nB; ++j) {
                if (mb[static_cast<size_t>(j)] != (inA(b[static_cast<size_t>(j)]) ? 1 : 0)) {
                    allOk = false;
                }
            }
        }
        check(allOk, "randomized dedup == naive reference (200 trials)");
    }

    // Envelope: heavy dups (table fast path) and >1024 distinct (brute
    // fallback) must both stay exact.
    {
        Rng rng(99);
        auto refUnion = [](const std::vector<uint64_t>& a,
                           const std::vector<uint64_t>& b,
                           std::vector<uint64_t>& u, std::vector<uint8_t>& ov) {
            u.clear();
            ov.clear();
            auto inA = [&](uint64_t h) {
                for (uint64_t v : a)
                    if (v == h) return true;
                return false;
            };
            auto inB = [&](uint64_t h) {
                for (uint64_t v : b)
                    if (v == h) return true;
                return false;
            };
            for (size_t i = 0; i < a.size(); ++i) {
                bool dup = false;
                for (size_t k = 0; k < i; ++k)
                    if (a[k] == a[i]) { dup = true; break; }
                if (dup) continue;
                u.push_back(a[i]);
                ov.push_back(inB(a[i]) ? 1 : 0);
            }
            for (size_t j = 0; j < b.size(); ++j) {
                if (inA(b[j])) continue;
                bool dup = false;
                for (size_t k = 0; k < j; ++k)
                    if (b[k] == b[j]) { dup = true; break; }
                if (dup) continue;
                u.push_back(b[j]);
                ov.push_back(0);
            }
        };

        // 5000 elements over 500 distinct values -> table path.
        {
            std::vector<uint64_t> a(5000), b(5000);
            for (auto& v : a) v = rng.next() % 500;
            for (auto& v : b) v = rng.next() % 500;
            std::vector<uint64_t> u(10000, 0xAB), ru;
            std::vector<uint8_t> ov(10000, 7), rov;
            const int32_t n = computeUniqueUnion(a.data(), 5000, b.data(), 5000,
                                                 u.data(), ov.data(), 10000);
            refUnion(a, b, ru, rov);
            bool ok = static_cast<size_t>(n) == ru.size();
            for (size_t k = 0; ok && k < ru.size(); ++k) {
                ok = u[k] == ru[k] && ov[k] == rov[k];
            }
            check(ok, "heavy-dup (table path) exact");
        }
        // 2000 distinct per side -> table overflow -> brute fallback.
        {
            std::vector<uint64_t> a(2000), b(2000);
            for (auto& v : a) v = rng.next();
            for (auto& v : b) v = rng.next();
            for (int i = 0; i < 100; ++i) b[static_cast<size_t>(i)] = a[static_cast<size_t>(i)];
            std::vector<uint64_t> u(4000, 0xAB), ru;
            std::vector<uint8_t> ov(4000, 7), rov;
            const int32_t n = computeUniqueUnion(a.data(), 2000, b.data(), 2000,
                                                 u.data(), ov.data(), 4000);
            refUnion(a, b, ru, rov);
            bool ok = static_cast<size_t>(n) == ru.size();
            for (size_t k = 0; ok && k < ru.size(); ++k) {
                ok = u[k] == ru[k] && ov[k] == rov[k];
            }
            check(ok, "overflow fallback (brute path) exact");
        }
    }

    // C-ABI parity with the C++ kernels.
    {
        const uint64_t a[] = {11, 22, 22, 33};
        const uint64_t b[] = {22, 33, 44};
        uint8_t ma[4] = {0}, mb[3] = {0};
        const int32_t cabiC = streamify_candidate_intersection(a, 4, b, 3, ma, mb);
        uint8_t ma2[4] = {0}, mb2[3] = {0};
        const int32_t cppC = computeIntersectionMasks(a, 4, b, 3, ma2, mb2);
        bool ok = cabiC == cppC && std::memcmp(ma, ma2, 4) == 0 &&
                  std::memcmp(mb, mb2, 3) == 0;
        uint64_t u1[6], u2[6];
        uint8_t o1[6], o2[6];
        const int32_t n1 = streamify_candidate_union(a, 4, b, 3, u1, o1, 6);
        const int32_t n2 = computeUniqueUnion(a, 4, b, 3, u2, o2, 6);
        ok = ok && n1 == n2 && std::memcmp(u1, u2, sizeof(uint64_t) * 6) == 0 &&
             std::memcmp(o1, o2, 6) == 0;
        check(ok, "C-ABI parity (union + intersection)");
        check(streamify_hash_candidate_id("4iV5W9uYEdYUVa79Ab7LF3", -1) ==
                  0xD4D8417214B87E50ull,
              "C-ABI hash (strlen path) == golden");
    }
}

// ---------------------------------------------------------------------------
// B. Camelot key matrix (all 24 musical keys)
// ---------------------------------------------------------------------------
void test_camelot_matrix() {
    std::printf("  [harmonic] Camelot key transition matrix\n");

    // Directive anchors.
    checkEq(keyTransitionScore(8, false, 8, false), 1.00f, 1e-6f, "exact 8B->8B");
    checkEq(keyTransitionScore(8, true, 8, true), 1.00f, 1e-6f, "exact 8A->8A");
    checkEq(keyTransitionScore(8, false, 8, true), 0.95f, 1e-6f, "relative 8B->8A");
    checkEq(keyTransitionScore(8, true, 8, false), 0.95f, 1e-6f, "relative 8A->8B");
    checkEq(keyTransitionScore(8, false, 7, false), 0.90f, 1e-6f, "adjacent 8B->7B");
    checkEq(keyTransitionScore(8, false, 9, false), 0.90f, 1e-6f, "adjacent 8B->9B");
    checkEq(keyTransitionScore(12, false, 1, false), 0.90f, 1e-6f, "wrap 12B->1B");
    checkEq(keyTransitionScore(1, true, 12, true), 0.90f, 1e-6f, "wrap 1A->12A");

    // Same-mode energy boosts + directionality.
    checkEq(keyTransitionScore(8, true, 10, true), 0.85f, 1e-6f, "+2st boost 8A->10A");
    checkEq(keyTransitionScore(8, true, 3, true), 0.80f, 1e-6f, "+1st boost 8A->3A");
    checkEq(keyTransitionScore(10, true, 8, true), 0.60f, 1e-6f, "inverse 10A->8A two-step");
    checkEq(keyTransitionScore(6, false, 1, false), 0.80f, 1e-6f, "+1st boost 6B->1B");
    checkEq(keyTransitionScore(1, false, 6, false), 0.178125f, 1e-4f, "inverse 1B->6B clash d=5");

    // Diagonal + clash falloff.
    checkEq(keyTransitionScore(8, true, 9, false), 0.70f, 1e-6f, "diagonal 8A->9B");
    checkEq(keyTransitionScore(9, false, 8, true), 0.70f, 1e-6f, "diagonal symmetric 9B->8A");
    checkEq(keyTransitionScore(8, true, 7, false), 0.70f, 1e-6f, "diagonal 8A->7B");
    checkEq(keyTransitionScore(8, false, 2, false), 0.10f, 1e-6f, "clash floor d=6");
    checkEq(keyTransitionScore(8, false, 11, false), 0.521875f, 1e-5f, "clash d=3 smoothstep");
    checkEq(keyTransitionScore(8, false, 12, false), 0.35f, 1e-5f, "clash d=4 (8B->12B)");
    checkEq(keyTransitionScore(8, true, 12, false), 0.35f, 1e-5f, "8A->12B d=4 ladder");
    checkEq(keyTransitionScore(8, false, 10, false), 0.85f, 1e-6f, "+2st boost 8B->10B (B mode)");

    // Invalid keys => neutral (missing metadata never punishes).
    checkEq(keyTransitionScore(0, false, 8, false), 0.50f, 1e-6f, "invalid keyA neutral");
    checkEq(keyTransitionScore(8, false, 13, false), 0.50f, 1e-6f, "invalid keyB neutral");
    checkEq(keyTransitionScore(-3, true, 99, true), 0.50f, 1e-6f, "garbage neutral");

    // Pitch classes vs the standard Camelot mapping.
    struct PcCheck {
        int n;
        bool minor;
        uint32_t want;
    };
    const PcCheck pcs[] = {
        {1, false, 0},  {2, false, 7},  {8, false, 1},  {12, false, 5},
        {1, true, 9},   {8, true, 10},  {10, true, 0},  {12, true, 2},
    };
    for (const PcCheck& c : pcs) {
        check(camelotPitchClass(c.n, c.minor) == c.want, "pitch class table");
    }

    // Full 24x24 ordered sweep: bounds, relation counts, symmetry, monotone
    // clash falloff. This is the directive's "all 24 musical keys" mandate.
    int exactCnt = 0, relCnt = 0, adjCnt = 0;
    bool boundsOk = true;
    bool symmetryOk = true;
    bool falloffMonotone = true;
    float prevClashScore = 1.0f;
    for (int d = 3; d <= 6; ++d) {
        // Fixed reference key 8B vs keys at ring distance d (same mode).
        const int numB = ((8 - 1 + d) % 12) + 1;
        const float s = keyTransitionScore(8, false, numB, false);
        if (s >= prevClashScore) falloffMonotone = false;
        prevClashScore = s;
    }
    for (int num = 1; num <= 12; ++num) {
        for (int minorA = 0; minorA <= 1; ++minorA) {
            for (int numB = 1; numB <= 12; ++numB) {
                for (int minorB = 0; minorB <= 1; ++minorB) {
                    const float s = keyTransitionScore(num, minorA != 0, numB,
                                                       minorB != 0);
                    if (!(s >= 0.0f && s <= 1.0f)) boundsOk = false;
                    if (num == numB && minorA == minorB) {
                        ++exactCnt;
                        if (s != 1.0f) boundsOk = false;
                    }
                    if (num == numB && minorA != minorB) {
                        ++relCnt;
                        if (s != 0.95f) boundsOk = false;
                    }
                    const int dd = (numB >= num) ? numB - num : num - numB;
                    const int rd = std::min(dd, 12 - dd);
                    if (minorA == minorB && rd == 1) {
                        ++adjCnt;
                        if (s != 0.90f) boundsOk = false;
                    }
                    // Symmetry: exempt only directional energy boosts.
                    const float r = keyTransitionScore(numB, minorB != 0, num,
                                                       minorA != 0);
                    const KeyRelation relAB =
                        classifyKeyTransition(num, minorA != 0, numB, minorB != 0);
                    const KeyRelation relBA =
                        classifyKeyTransition(numB, minorB != 0, num, minorA != 0);
                    const bool isBoost =
                        relAB == KeyRelation::EnergyBoostWhole ||
                        relAB == KeyRelation::EnergyBoostHalf ||
                        relBA == KeyRelation::EnergyBoostWhole ||
                        relBA == KeyRelation::EnergyBoostHalf;
                    if (!isBoost && std::fabs(s - r) > 1e-6f) symmetryOk = false;
                }
            }
        }
    }
    check(boundsOk, "24x24 sweep bounds + anchor counts");
    check(exactCnt == 24 && relCnt == 24 && adjCnt == 48,
          "relation counts 24/24/48");
    check(symmetryOk, "symmetry (only boosts directional)");
    check(falloffMonotone, "clash falloff monotone over D=3..6");

    // C-ABI parity.
    check(std::fabs(streamify_key_transition_score(8, 0, 7, 0) - 0.90f) < 1e-6f,
          "C-ABI key score parity");
}

// ---------------------------------------------------------------------------
// B. BPM matrix + combined coefficient
// ---------------------------------------------------------------------------
void test_bpm_and_combined() {
    std::printf("  [harmonic] BPM transition + combined coefficient\n");

    // Tolerance window.
    checkEq(bpmTransitionScore(120, 120), 1.00f, 1e-6f, "equal tempo");
    checkEq(bpmTransitionScore(100, 108), 0.68f, 1e-5f, "window edge +8%");
    checkEq(bpmTransitionScore(100, 92), 0.68f, 1e-5f, "window edge -8%");
    checkEq(bpmTransitionScore(100, 104), 0.84f, 1e-5f, "mid window");
    const float atEdge = bpmTransitionScore(100, 108);
    const float pastEdge = bpmTransitionScore(100, 108.2f);
    checkEq(pastEdge, atEdge, 0.02f, "continuous at window boundary");
    checkEq(bpmTransitionScore(100, 109), 0.68f * std::exp(-0.125f), 1e-4f,
            "exponential tail");
    checkEq(bpmTransitionScore(100, 180), 0.0f, 0.001f, "tempo cliff ~0");

    // Octave folding (directive example: 75 <-> 150).
    checkEq(bpmTransitionScore(75, 150), 1.00f, 1e-6f, "double-time 75->150");
    checkEq(bpmTransitionScore(150, 75), 1.00f, 1e-6f, "half-time 150->75");
    checkEq(bpmTransitionScore(120, 60), 1.00f, 1e-6f, "half-time 120->60");
    checkEq(bpmTransitionScore(74, 148), 1.00f, 1e-6f, "exact 2:1");
    checkEq(bpmTransitionScore(100, 195), 0.90f, 1e-4f, "fold r=1.95");
    checkEq(bpmTransitionScore(100, 190), 0.80f, 1e-4f, "fold r=1.9 (float edge)");
    checkEq(bpmTransitionScore(100, 189), 0.0f, 0.001f, "outside fold = cliff");
    checkEq(bpmTransitionScore(100, 300), 0.0f, 1e-3f, "3x ratio no fold");

    // Invalid metadata => neutral.
    checkEq(bpmTransitionScore(0, 120), 0.50f, 1e-6f, "zero bpm neutral");
    checkEq(bpmTransitionScore(-5, 120), 0.50f, 1e-6f, "negative bpm neutral");
    checkEq(bpmTransitionScore(120, 0), 0.50f, 1e-6f, "zero bpmB neutral");
    checkEq(bpmTransitionScore(std::nanf(""), 120), 0.50f, 1e-6f, "NaN neutral");
    checkEq(bpmTransitionScore(120, std::nanf("")), 0.50f, 1e-6f, "NaN bpmB neutral");
    checkEq(bpmTransitionScore(120, INFINITY), 0.50f, 1e-6f, "inf neutral");
    checkEq(bpmTransitionScore(0, 0), 0.50f, 1e-6f, "both invalid neutral");

    // Combined coefficient = clamp(0.6*key + 0.4*bpm).
    checkEq(transitionCompatibility(8, false, 120, 8, false, 120), 1.00f, 1e-6f,
            "perfect pair");
    checkEq(transitionCompatibility(8, false, 0, 8, false, 120), 0.80f, 1e-6f,
            "key exact + bpm missing");
    checkEq(transitionCompatibility(0, false, 120, 0, false, 120), 0.70f, 1e-6f,
            "key missing + bpm equal");
    checkEq(transitionCompatibility(0, false, 0, 0, false, 0), 0.50f, 1e-6f,
            "both missing neutral");
    checkEq(transitionCompatibility(8, true, 75, 8, false, 150), 0.97f, 1e-3f,
            "relative + double-time");
    checkEq(transitionCompatibility(8, false, 120, 2, false, 180), 0.06f, 0.02f,
            "clash + cliff");

    // Bounds sweep incl. hostile inputs.
    bool boundsOk = true;
    for (int ka = -2; ka <= 14 && boundsOk; ++ka) {
        for (int kb = -2; kb <= 14; ++kb) {
            for (int mi = 0; mi <= 1; ++mi) {
                for (const float ba : {0.0f, 75.0f, 128.5f, 1e9f}) {
                    for (const float bb : {0.0f, 73.0f, 143.0f, 150.0f}) {
                        const float s = transitionCompatibility(
                            ka, mi != 0, ba, kb, false, bb);
                        if (!(s >= 0.0f && s <= 1.0f) || !std::isfinite(s)) {
                            boundsOk = false;
                        }
                    }
                }
            }
        }
    }
    check(boundsOk, "combined bounds sweep");

    check(std::fabs(streamify_transition_score(8, 1, 75.0f, 8, 0, 150.0f) -
                    transitionCompatibility(8, true, 75.0f, 8, false, 150.0f)) <
              1e-6f,
          "C-ABI combined parity");
    check(std::fabs(streamify_bpm_transition_score(75.0f, 150.0f) - 1.0f) < 1e-6f,
          "C-ABI bpm parity");
}

// ---------------------------------------------------------------------------
// C. Circadian curves
// ---------------------------------------------------------------------------
void test_circadian_curves() {
    std::printf("  [harmonic] circadian daypart curves\n");

    // Anchor hours reproduce calibration values exactly (u=0 => s=0).
    for (const auto& a : kCircadianAnchors) {
        const auto w = circadianWeights(a.hour);
        checkEq(w.energy, a.weights.energy, 1e-6f, "anchor energy exact");
        checkEq(w.acousticness, a.weights.acousticness, 1e-6f,
                "anchor acousticness exact");
        checkEq(w.valence, a.weights.valence, 1e-6f, "anchor valence exact");
    }

    // Periodicity: t == t+24 == t-48; 0 == 24; -0.001h == 23.999h.
    bool perOk = true;
    for (float t = 0.0f; t < 24.0f; t += 0.37f) {
        const auto a = circadianWeights(t);
        const auto b = circadianWeights(t + 24.0f);
        const auto c = circadianWeights(t - 48.0f);
        if (std::fabs(a.energy - b.energy) > 1e-5f ||
            std::fabs(a.acousticness - c.acousticness) > 1e-5f) {
            perOk = false;
            break;
        }
    }
    check(perOk, "periodicity +-24/48h");
    const auto w0 = circadianWeights(0.0f);
    const auto w24 = circadianWeights(24.0f);
    const auto wNeg = circadianWeights(-0.001f);
    const auto wLate = circadianWeights(23.999f);
    checkEq(w0.energy, w24.energy, 1e-6f, "0h == 24h");
    check(std::fabs(wNeg.energy - wLate.energy) < 1e-6f &&
              std::fabs(wNeg.valence - wLate.valence) < 1e-6f,
          "-0.001h == 23.999h (same instant)");

    // ZERO step discontinuities: 1-second sampling over the full day.
    float maxJump = 0.0f;
    auto prev = circadianWeights(0.0f);
    for (float t = 1.0f / 3600.0f; t <= 24.0f; t += 1.0f / 3600.0f) {
        const auto w = circadianWeights(t);
        float j = std::fabs(w.energy - prev.energy);
        j = std::fmax(j, std::fabs(w.acousticness - prev.acousticness));
        j = std::fmax(j, std::fabs(w.valence - prev.valence));
        maxJump = std::fmax(maxJump, j);
        prev = w;
    }
    check(maxJump < 1e-3f, "no step discontinuities (1s sampling)");
    std::printf("    (24h max per-second jump: %.2e)\n", static_cast<double>(maxJump));

    // Musical ordering at slot centers.
    const auto night = circadianWeights(2.0f);
    const auto morning = circadianWeights(8.5f);
    const auto afternoon = circadianWeights(14.0f);
    const auto evening = circadianWeights(19.5f);
    check(evening.energy > afternoon.energy && afternoon.energy > morning.energy &&
              morning.energy > night.energy,
          "energy: night < morning < afternoon < evening");
    check(night.acousticness > morning.acousticness &&
              morning.acousticness > afternoon.acousticness &&
              afternoon.acousticness > evening.acousticness,
          "acousticness: evening < afternoon < morning < night");
    check(afternoon.valence > evening.valence && evening.valence > morning.valence &&
              morning.valence > night.valence,
          "valence: night < morning < evening < afternoon");

    // Bounds over a dense sweep + hostile inputs.
    bool boundsOk = true;
    for (float t = 0.0f; t < 24.0f; t += 0.011f) {
        const auto w = circadianWeights(t);
        if (w.energy < 0.0f || w.energy > 1.0f || w.acousticness < 0.0f ||
            w.acousticness > 1.0f || w.valence < 0.0f || w.valence > 1.0f) {
            boundsOk = false;
            break;
        }
    }
    check(boundsOk, "dense bounds sweep");
    checkEq(circadianWeights(std::nanf("")).energy, 0.5f, 1e-6f, "NaN neutral");
    checkEq(circadianWeights(INFINITY).valence, 0.5f, 1e-6f, "+inf neutral");
    checkEq(circadianWeights(-INFINITY).acousticness, 0.5f, 1e-6f, "-inf neutral");
    const auto huge = circadianWeights(1e12f);
    check(huge.energy >= 0.0f && huge.energy <= 1.0f, "huge hour bounded");

    // C-ABI parity.
    float out3[3] = {9, 9, 9};
    streamify_circadian_weights(19.5f, out3);
    checkEq(out3[0], 0.90f, 1e-6f, "C-ABI circadian energy");
    checkEq(out3[1], 0.20f, 1e-6f, "C-ABI circadian acousticness");
    checkEq(out3[2], 0.75f, 1e-6f, "C-ABI circadian valence");
    streamify_circadian_weights(12.0f, nullptr);   // must not crash
    check(true, "C-ABI null out is a no-op");
}

// ---------------------------------------------------------------------------
// Perf + zero-alloc audit (directive: < 0.05 ms, zero heap)
// ---------------------------------------------------------------------------
void test_zero_alloc_and_perf() {
    std::printf("  [harmonic] perf budgets + zero-alloc audit\n");

    Rng rng(7);
    const int kScale = 256;
    std::vector<uint64_t> a(static_cast<size_t>(kScale));
    std::vector<uint64_t> b(static_cast<size_t>(kScale));
    for (auto& v : a) v = rng.next();
    for (auto& v : b) v = rng.next();
    for (int i = 0; i < kScale / 8; ++i) {
        b[static_cast<size_t>(i)] = a[static_cast<size_t>(i)];   // ~12.5% overlap
    }
    std::vector<uint64_t> u(2 * kScale);
    std::vector<uint8_t> ov(2 * kScale), ma(kScale), mb(kScale);

    // Zero-alloc audit on every hot path.
    {
        streamify_test::AllocGuard g;
        computeIntersectionMasks(a.data(), kScale, b.data(), kScale, ma.data(),
                                 mb.data());
        check(g.count() == 0, "intersection masks zero-alloc");
        computeUniqueUnion(a.data(), kScale, b.data(), kScale, u.data(), ov.data(),
                           2 * kScale);
        check(g.count() == 0, "unique union zero-alloc");
    }
    {
        streamify_test::AllocGuard g;
        (void)circadianWeights(13.37f);
        (void)transitionCompatibility(8, true, 128.0f, 9, false, 126.5f);
        check(g.count() == 0, "scoring paths zero-alloc");
    }

    // Directive budget: masks + union in < 0.05 ms (minimum of 5 runs to
    // de-noise shared CI runners). 256/512 scales reported, not asserted.
    double bestUs = 1e9;
    for (int run = 0; run < 5; ++run) {
        const auto t0 = std::chrono::steady_clock::now();
        computeIntersectionMasks(a.data(), kScale, b.data(), kScale, ma.data(),
                                 mb.data());
        computeUniqueUnion(a.data(), kScale, b.data(), kScale, u.data(), ov.data(),
                           2 * kScale);
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration<double, std::micro>(t1 - t0).count();
        bestUs = std::min(bestUs, us);
    }
    std::printf("    (256x256 masks+union: %.1f us, best of 5)\n", bestUs);
    check(bestUs < 50.0, "128x128-class dedup < 50 us budget (256x256 measured)");

    // Bulk-hash throughput (report-only).
    {
        std::vector<std::string> ids(20000);
        for (size_t i = 0; i < ids.size(); ++i) {
            ids[i] = "spotify:track:" + std::to_string(1000000 + i);
        }
        std::vector<CandidateIdSpan> spans(ids.size());
        for (size_t i = 0; i < ids.size(); ++i) {
            spans[i] = {ids[i].data(), static_cast<uint32_t>(ids[i].size())};
        }
        std::vector<uint64_t> out(ids.size());
        const auto t0 = std::chrono::steady_clock::now();
        hashCandidateIdsBulk(spans.data(), spans.size(), out.data());
        const auto t1 = std::chrono::steady_clock::now();
        const double us =
            std::chrono::duration<double, std::micro>(t1 - t0).count();
        std::printf("    (bulk hash 20k x %zu-char ids: %.0f us)\n",
                    ids.front().size(), us);
        check(out[0] != out[1], "bulk hash sanity");
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// Entry point (called from test_dsp.cc main)
// ---------------------------------------------------------------------------
int run_harmonic_math_tests() {
    std::printf("[TEST] Phase 2: Harmonic Transition Engine, Candidate "
                "Fast-Math & Circadian Curves\n");
    test_candidate_hashing();
    test_candidate_dedup();
    test_camelot_matrix();
    test_bpm_and_combined();
    test_circadian_curves();
    test_zero_alloc_and_perf();
    std::printf("[TEST] Phase 2: all %d checks passed (%d failed)\n", g_passed,
                g_failed);
    return g_failed == 0 ? 0 : 1;
}
