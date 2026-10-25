// ============================================================================
//  palette_extractor_simd.cpp — Phase-5 SIMD palette clustering & WCAG
//  contrast engine implementation (native/src/palette_extractor_simd.cpp)
// ============================================================================
//
//  Stage map (see palette_extractor_simd.h for the architectural contract):
//    Extract()        -> validate -> clear -> ingest (SIMD/strided) ->
//                        Quantize() -> ScoreRoles()
//    Quantize()       -> top-K bin retention (deterministic binary heap) ->
//                        canonical sort -> median cut (counting-sort splits)
//                        -> k-means Lloyd refinement (integer distances)
//    ScoreRoles()     -> HSV role targets with construction clamps,
//                        distinctness ladders, and the guaranteed text
//                        surface (sqrt(21) >= 4.58 > 4.5 pole proof).
//
//  All clustering math is INTEGER (bit-exact across compilers); only the
//  role scoring uses floats, and every float decision there carries an
//  epsilon margin or an integer tie-break, so the same binary always
//  produces the same palette for the same pixels.
// ============================================================================

#include "../include/palette_extractor_simd.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "../util/NeonCompat.h"

#if defined(__SSE2__)
#define STREAMIFY_PALETTE_SSE2 1
#include <emmintrin.h>
#else
#define STREAMIFY_PALETTE_SSE2 0
#endif

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#define STREAMIFY_PALETTE_NEON 1
#include <arm_neon.h>
#else
#define STREAMIFY_PALETTE_NEON 0
#endif

namespace streamify {
namespace {

// floor(log2(v)) for v >= 1 — portable stand-in for std::bit_width(v) - 1.
// NDK r25's libc++ predates <bit>'s bit_width; __builtin_clz is a GCC/Clang
// builtin present on every ABI this project ships (aarch64, armv7-a, x86_64).
inline uint32_t FloorLog2NonZero(uint32_t v) {
    return 31u - static_cast<uint32_t>(__builtin_clz(v));
}

}  // namespace
namespace palette_simd {

namespace {

// Fast-math-unfoldable NaN inspection (Phase-4 lesson: isfinite()/comparison
// ladders get folded away under -ffast-math LTO; a bit-pattern check cannot).
inline bool PaletteIsNan(float f) {
    uint32_t bits = 0;
    std::memcpy(&bits, &f, sizeof(bits));
    return (bits & 0x7F800000u) == 0x7F800000u && (bits & 0x007FFFFFu) != 0;
}

inline float Clamp01(float x) {
    return x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x);
}

// WCAG 2.1 sRGB channel linearization.
inline float LinearizeChannel(uint32_t c8) {
    const float c = static_cast<float>(c8) * (1.0f / 255.0f);
    if (c <= 0.04045f) {
        return c * (1.0f / 12.92f);
    }
    return std::pow((c + 0.055f) * (1.0f / 1.055f), 2.4f);
}

struct Hsv {
    float h;  // [0, 360)
    float s;  // [0, 1]
    float v;  // [0, 1]
};

Hsv RgbToHsv(uint8_t r, uint8_t g, uint8_t b) {
    const int mx = r > g ? r : g;
    const int mn = r < g ? r : g;
    const int mx2 = mx > b ? mx : b;
    const int mn2 = mn < b ? mn : b;
    const int c = mx2 - mn2;
    float h = 0.0f;
    if (c > 0) {
        if (mx2 == r) {
            h = 60.0f * (static_cast<float>(g - b) / static_cast<float>(c));
        } else if (mx2 == g) {
            h = 60.0f * (static_cast<float>(b - r) / static_cast<float>(c) + 2.0f);
        } else {
            h = 60.0f * (static_cast<float>(r - g) / static_cast<float>(c) + 4.0f);
        }
        if (h < 0.0f) h += 360.0f;
        if (h >= 360.0f) h -= 360.0f;
    }
    const float s = mx2 == 0 ? 0.0f
                               : static_cast<float>(c) / static_cast<float>(mx2);
    const float v = static_cast<float>(mx2) * (1.0f / 255.0f);
    return Hsv{h, s, v};
}

uint8_t RoundToU8(float x) {
    const float v = x + 0.5f;
    if (v <= 0.0f) return 0;
    if (v >= 255.0f) return 255;
    return static_cast<uint8_t>(v);
}

uint32_t HsvToArgb(float h, float s, float v) {
    s = Clamp01(s);
    v = Clamp01(v);
    while (h < 0.0f) h += 360.0f;
    while (h >= 360.0f) h -= 360.0f;
    const float c = v * s;
    const float hp = h * (1.0f / 60.0f);
    const float hp_mod2 = hp - 2.0f * static_cast<float>(static_cast<int>(hp) / 2);
    const float x = c * (1.0f - (hp_mod2 > 1.0f ? 2.0f - hp_mod2 : hp_mod2));
    float r1 = 0.0f, g1 = 0.0f, b1 = 0.0f;
    int sector = static_cast<int>(hp);
    if (sector < 0) sector = 0;
    if (sector > 5) sector = 5;
    switch (sector) {
        case 0: r1 = c; g1 = x; break;
        case 1: r1 = x; g1 = c; break;
        case 2: g1 = c; b1 = x; break;
        case 3: g1 = x; b1 = c; break;
        case 4: r1 = x; b1 = c; break;
        default: r1 = c; b1 = x; break;
    }
    const float m = v - c;
    const uint8_t r = RoundToU8((r1 + m) * 255.0f);
    const uint8_t g = RoundToU8((g1 + m) * 255.0f);
    const uint8_t b = RoundToU8((b1 + m) * 255.0f);
    return 0xFF000000u | (static_cast<uint32_t>(r) << 16) |
           (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
}

inline uint32_t PackArgb(uint8_t r, uint8_t g, uint8_t b) {
    return 0xFF000000u | (static_cast<uint32_t>(r) << 16) |
           (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
}

// Integer lerp used by the text-surface tint ladder (exact, no float drift).
inline uint32_t BlendArgb(uint32_t a, uint32_t b, uint32_t k256) {
    const uint32_t kr = k256 > 256 ? 256 : k256;
    const uint32_t ka = 256 - kr;
    const auto mix = [ka, kr](uint32_t ca, uint32_t cb) -> uint32_t {
        return (ca * ka + cb * kr + 127) / 256;
    };
    const uint32_t r = mix((a >> 16) & 0xFF, (b >> 16) & 0xFF);
    const uint32_t g = mix((a >> 8) & 0xFF, (b >> 8) & 0xFF);
    const uint32_t bl = mix(a & 0xFF, b & 0xFF);
    return 0xFF000000u | (r << 16) | (g << 8) | bl;
}

}  // namespace

// ---- WCAG 2.1 math -----------------------------------------------------------

float RelativeLuminance(uint32_t argb) {
    const uint32_t r = (argb >> 16) & 0xFFu;
    const uint32_t g = (argb >> 8) & 0xFFu;
    const uint32_t b = argb & 0xFFu;
    return 0.2126f * LinearizeChannel(r) + 0.7152f * LinearizeChannel(g) +
           0.0722f * LinearizeChannel(b);
}

float ContrastRatio(uint32_t argb_a, uint32_t argb_b) {
    const float la = RelativeLuminance(argb_a);
    const float lb = RelativeLuminance(argb_b);
    const float hi = la > lb ? la : lb;
    const float lo = la > lb ? lb : la;
    return (hi + 0.05f) / (lo + 0.05f);
}

bool MeetsContrast(uint32_t argb_a, uint32_t argb_b, float min_ratio) {
    if (PaletteIsNan(min_ratio)) return false;
    if (min_ratio <= 1.0f) return true;  // ratio is definitionally >= 1
    return ContrastRatio(argb_a, argb_b) >= min_ratio;
}

// ---- construction / teardown ---------------------------------------------------

SimdPaletteExtractor::SimdPaletteExtractor() {
    // One aligned block, 64-byte sub-array alignment (NeonCompat mandate):
    //   hist (4 x 128 KB) + bins_ AOS (~112 KB) + assign map (16 KB).
    const size_t hist_bytes = kHistogramBins * sizeof(uint32_t);
    const size_t bins_bytes = kMaxRetainedBins * sizeof(BinEntry);
    const size_t assign_bytes = kMaxRetainedBins * sizeof(uint32_t);
    total_bytes_ = streamify::alignUp(4 * hist_bytes, 64) +
                   streamify::alignUp(bins_bytes, 64) +
                   streamify::alignUp(assign_bytes, 64);
    scratch_ = static_cast<uint8_t*>(
        streamify::alignedAlloc(64, total_bytes_));
    if (scratch_ == nullptr) return;
    uint8_t* p = scratch_;
    hist_pop_ = reinterpret_cast<uint32_t*>(p);  p += alignUp(hist_bytes, 64);
    hist_sum_r_ = reinterpret_cast<uint32_t*>(p); p += alignUp(hist_bytes, 64);
    hist_sum_g_ = reinterpret_cast<uint32_t*>(p); p += alignUp(hist_bytes, 64);
    hist_sum_b_ = reinterpret_cast<uint32_t*>(p); p += alignUp(hist_bytes, 64);
    bins_ = reinterpret_cast<BinEntry*>(p);        p += alignUp(bins_bytes, 64);
    assign_ = reinterpret_cast<uint32_t*>(p);
    ClearHistogram();
}

SimdPaletteExtractor::~SimdPaletteExtractor() {
    streamify::alignedFree(scratch_);
}

void SimdPaletteExtractor::ClearHistogram() {
    if (hist_pop_ == nullptr) return;
    const size_t hist_bytes = kHistogramBins * sizeof(uint32_t);
    std::memset(hist_pop_, 0, hist_bytes);
    std::memset(hist_sum_r_, 0, hist_bytes);
    std::memset(hist_sum_g_, 0, hist_bytes);
    std::memset(hist_sum_b_, 0, hist_bytes);
}

// ---- argument validation -------------------------------------------------------

PaletteError SimdPaletteExtractor::ValidateIngest(const uint8_t* rgba,
                                                  size_t byte_len,
                                                  int32_t width, int32_t height,
                                                  int32_t stride_bytes,
                                                  uint64_t* needed_bytes) {
    if (rgba == nullptr) return PaletteError::kNullArgument;
    if (width <= 0 || height <= 0 || width > kPaletteMaxDim ||
        height > kPaletteMaxDim) {
        return PaletteError::kBadDimensions;
    }
    if (stride_bytes < static_cast<int64_t>(width) * 4 ||
        (stride_bytes & 3) != 0) {
        return PaletteError::kBadStride;
    }
    const uint64_t needed =
        static_cast<uint64_t>(height - 1) *
            static_cast<uint64_t>(stride_bytes) +
        static_cast<uint64_t>(width) * 4ull;
    if (byte_len < needed) return PaletteError::kBadBufferSize;
    if (needed_bytes != nullptr) *needed_bytes = needed;
    return PaletteError::kOk;
}

// ---- scalar ingest ---------------------------------------------------------------

void SimdPaletteExtractor::IngestRowScalar(const uint8_t* row, int32_t width) {
    for (int32_t x = 0; x < width; ++x) {
        const uint8_t* p = row + static_cast<size_t>(x) * 4;
        const uint32_t r = p[0];
        const uint32_t g = p[1];
        const uint32_t b = p[2];
        const uint32_t idx =
            ((r & 0xF8u) << 7) | ((g & 0xF8u) << 2) | (b >> 3);
        ++hist_pop_[idx];
        hist_sum_r_[idx] += r;
        hist_sum_g_[idx] += g;
        hist_sum_b_[idx] += b;
    }
}

// ---- SIMD ingest (SSE2 / NEON, bit-identical to the scalar path) ----------------

void SimdPaletteExtractor::IngestRowSimd(const uint8_t* row, int32_t width) {
#if STREAMIFY_PALETTE_SSE2
    int32_t x = 0;
    alignas(16) uint32_t idx4[4];
    alignas(16) uint32_t r4[4];
    alignas(16) uint32_t g4[4];
    alignas(16) uint32_t b4[4];
    const __m128i mask_ff = _mm_set1_epi32(0xFF);
    const __m128i mask_f8 = _mm_set1_epi32(0xF8);
    for (; x + 4 <= width; x += 4) {
        const __m128i v =
            _mm_loadu_si128(reinterpret_cast<const __m128i*>(
                row + static_cast<size_t>(x) * 4));
        const __m128i r = _mm_and_si128(v, mask_ff);
        const __m128i g = _mm_and_si128(_mm_srli_epi32(v, 8), mask_ff);
        const __m128i b = _mm_and_si128(_mm_srli_epi32(v, 16), mask_ff);
        const __m128i idx =
            _mm_or_si128(_mm_or_si128(_mm_slli_epi32(_mm_and_si128(r, mask_f8), 7),
                                      _mm_slli_epi32(_mm_and_si128(g, mask_f8), 2)),
                         _mm_srli_epi32(b, 3));
        _mm_store_si128(reinterpret_cast<__m128i*>(idx4), idx);
        _mm_store_si128(reinterpret_cast<__m128i*>(r4), r);
        _mm_store_si128(reinterpret_cast<__m128i*>(g4), g);
        _mm_store_si128(reinterpret_cast<__m128i*>(b4), b);
        for (int k = 0; k < 4; ++k) {
            const uint32_t i = idx4[k];
            ++hist_pop_[i];
            hist_sum_r_[i] += r4[k];
            hist_sum_g_[i] += g4[k];
            hist_sum_b_[i] += b4[k];
        }
    }
    for (; x < width; ++x) {
        IngestRowScalar(row + static_cast<size_t>(x) * 4, 1);
    }
#elif STREAMIFY_PALETTE_NEON
    int32_t x = 0;
    alignas(16) uint32_t idx4[4];
    alignas(16) uint32_t r4[4];
    alignas(16) uint32_t g4[4];
    alignas(16) uint32_t b4[4];
    const uint32x4_t mask_ff = vdupq_n_u32(0xFFu);
    const uint32x4_t mask_f8 = vdupq_n_u32(0xF8u);
    for (; x + 4 <= width; x += 4) {
        const uint32x4_t v = vreinterpretq_u32_u8(
            vld1q_u8(row + static_cast<size_t>(x) * 4));
        const uint32x4_t r = vandq_u32(v, mask_ff);
        const uint32x4_t g = vandq_u32(vshrq_n_u32(v, 8), mask_ff);
        const uint32x4_t b = vandq_u32(vshrq_n_u32(v, 16), mask_ff);
        const uint32x4_t idx = vorrq_u32(
            vorrq_u32(vshlq_n_u32(vandq_u32(r, mask_f8), 7),
                      vshlq_n_u32(vandq_u32(g, mask_f8), 2)),
            vshrq_n_u32(b, 3));
        vst1q_u32(idx4, idx);
        vst1q_u32(r4, r);
        vst1q_u32(g4, g);
        vst1q_u32(b4, b);
        for (int k = 0; k < 4; ++k) {
            const uint32_t i = idx4[k];
            ++hist_pop_[i];
            hist_sum_r_[i] += r4[k];
            hist_sum_g_[i] += g4[k];
            hist_sum_b_[i] += b4[k];
        }
    }
    for (; x < width; ++x) {
        IngestRowScalar(row + static_cast<size_t>(x) * 4, 1);
    }
#else
    IngestRowScalar(row, width);
#endif
}

// ---- row-strided (subsampled) SIMD ingest -------------------------------------------
// Subsampling keeps full-width rows (SIMD ingest, contiguous reads) and
// strides only vertically: better cache behavior than per-pixel striding
// AND equal spatial treatment of horizontal band structure (skies,
// foregrounds) that dominates album artwork.
void SimdPaletteExtractor::IngestStridedRows(const uint8_t* rgba,
                                             int32_t width, int32_t height,
                                             int32_t stride_bytes,
                                             uint32_t row_step) {
    size_t sampled = 0;
    for (int32_t y = 0; y < height; y += static_cast<int32_t>(row_step)) {
        IngestRowSimd(rgba + static_cast<size_t>(y) * stride_bytes, width);
        sampled += static_cast<size_t>(width);
    }
    last_sample_count_ = sampled;
}

// ---- public ingest hooks (bit-exactness cross-validation) ---------------------------

PaletteError SimdPaletteExtractor::IngestScalar(const uint8_t* rgba,
                                                size_t byte_len, int32_t width,
                                                int32_t height,
                                                int32_t stride_bytes) {
    uint64_t needed = 0;
    PaletteError e = ValidateIngest(rgba, byte_len, width, height,
                                    stride_bytes, &needed);
    if (e != PaletteError::kOk) return e;
    // Full-image hooks keep u32 per-bin sums safely bounded (262144 px cap).
    if (static_cast<uint64_t>(width) * static_cast<uint64_t>(height) >
        (1ull << 18)) {
        return PaletteError::kBadDimensions;
    }
    ClearHistogram();
    for (int32_t y = 0; y < height; ++y) {
        IngestRowScalar(rgba + static_cast<size_t>(y) * stride_bytes, width);
    }
    last_sample_count_ =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    return PaletteError::kOk;
}

PaletteError SimdPaletteExtractor::IngestSimd(const uint8_t* rgba,
                                              size_t byte_len, int32_t width,
                                              int32_t height,
                                              int32_t stride_bytes) {
    uint64_t needed = 0;
    PaletteError e = ValidateIngest(rgba, byte_len, width, height,
                                    stride_bytes, &needed);
    if (e != PaletteError::kOk) return e;
    if (static_cast<uint64_t>(width) * static_cast<uint64_t>(height) >
        (1ull << 18)) {
        return PaletteError::kBadDimensions;
    }
    ClearHistogram();
    for (int32_t y = 0; y < height; ++y) {
        IngestRowSimd(rgba + static_cast<size_t>(y) * stride_bytes, width);
    }
    last_sample_count_ =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    return PaletteError::kOk;
}

// ---- quantization: retention -> median cut -> k-means ------------------------------

bool SimdPaletteExtractor::Quantize() {
    // ---- stage 1: retain the most populous bins (bucket threshold) ------------
    // Two linear census passes over the 32768-bin histogram replace a
    // heap/partial-sort: populations are bucketed by bit width, all bins in
    // buckets strictly above the boundary population are retained, and the
    // boundary bucket fills the remaining slots in ascending bin-index order
    // (deterministic; marginal bins differ only inside one population
    // bucket). When the populated count fits the cap, everything is kept.
    uint32_t bucket_count[18] = {0};  // pop < 2^18 (budget bound + slack)
    uint32_t populated = 0;
    for (uint32_t bin = 0; bin < kHistogramBins; ++bin) {
        const uint32_t pop = hist_pop_[bin];
        if (pop == 0) continue;
        ++populated;
        ++bucket_count[FloorLog2NonZero(pop)];  // floor(log2(pop))
    }
    if (populated == 0) return false;

    uint32_t retained = 0;
    if (populated <= kMaxRetainedBins) {
        for (uint32_t bin = 0; bin < kHistogramBins; ++bin) {
            if (hist_pop_[bin] == 0) continue;
            BinEntry& e = bins_[retained++];
            e.idx = static_cast<uint16_t>(bin);
            e.pop = hist_pop_[bin];
            e.sum_r = hist_sum_r_[bin];
            e.sum_g = hist_sum_g_[bin];
            e.sum_b = hist_sum_b_[bin];
        }
    } else {
        // Boundary bucket: highest bucket b whose full inclusion would
        // exceed the cap. cum = number of bins in strictly higher buckets.
        int bstar = 0;
        uint32_t cum = 0;
        for (int b = 17; b >= 0; --b) {
            if (bucket_count[b] == 0) continue;
            if (cum + bucket_count[b] > kMaxRetainedBins) {
                bstar = b;
                break;
            }
            cum += bucket_count[b];
        }
        uint32_t need = kMaxRetainedBins - cum;  // slots left for bucket bstar
        for (uint32_t bin = 0; bin < kHistogramBins && retained < kMaxRetainedBins;
             ++bin) {
            const uint32_t pop = hist_pop_[bin];
            if (pop == 0) continue;
            const int b = static_cast<int>(FloorLog2NonZero(pop));
            if (b > bstar) {
                BinEntry& e = bins_[retained++];
                e.idx = static_cast<uint16_t>(bin);
                e.pop = pop;
                e.sum_r = hist_sum_r_[bin];
                e.sum_g = hist_sum_g_[bin];
                e.sum_b = hist_sum_b_[bin];
            } else if (b == bstar && need > 0) {
                --need;
                BinEntry& e = bins_[retained++];
                e.idx = static_cast<uint16_t>(bin);
                e.pop = pop;
                e.sum_r = hist_sum_r_[bin];
                e.sum_g = hist_sum_g_[bin];
                e.sum_b = hist_sum_b_[bin];
            }
        }
    }

    // Canonically order by (population desc, bin index asc) so every later
    // stage is a pure function of this order.
    std::sort(bins_, bins_ + retained, [](const BinEntry& a, const BinEntry& b) {
        if (a.pop != b.pop) return a.pop > b.pop;
        return a.idx < b.idx;
    });
    const uint32_t n = static_cast<uint32_t>(retained);
    // Bin -> cluster map starts clean (k-means reads before writing on pass 1).
    std::memset(assign_, 0, static_cast<size_t>(n) * sizeof(uint32_t));

    // Per-bin exact mean colors (rounded) for the k-means distance metric.
    for (uint32_t i = 0; i < n; ++i) {
        BinEntry& e = bins_[i];
        e.mean_r = static_cast<uint8_t>((e.sum_r + e.pop / 2) / e.pop);
        e.mean_g = static_cast<uint8_t>((e.sum_g + e.pop / 2) / e.pop);
        e.mean_b = static_cast<uint8_t>((e.sum_b + e.pop / 2) / e.pop);
    }

    // ---- stage 2: median cut --------------------------------------------------
    // Boxes are contiguous subranges of bins_. Per-box stats (population +
    // 5-bit channel extents) are computed ONCE when the box is created and
    // cached, so each split only scans the box it actually splits instead
    // of re-walking every box. Split the most populous splittable box along
    // its widest 5-bit channel axis at the population-weighted median.
    {
        // Initial stats for the single all-bins box.
        BoxInfo& b0 = boxinfo_[0];
        b0.begin = 0;
        b0.end = n;
        b0.pop = 0;
        b0.rmin = 31; b0.rmax = 0;
        b0.gmin = 31; b0.gmax = 0;
        b0.bmin = 31; b0.bmax = 0;
        for (uint32_t i = 0; i < n; ++i) {
            const BinEntry& e = bins_[i];
            const int r5 = (e.idx >> 10) & 31;
            const int g5 = (e.idx >> 5) & 31;
            const int b5 = e.idx & 31;
            b0.pop += e.pop;
            if (r5 < b0.rmin) b0.rmin = r5;
            if (r5 > b0.rmax) b0.rmax = r5;
            if (g5 < b0.gmin) b0.gmin = g5;
            if (g5 > b0.gmax) b0.gmax = g5;
            if (b5 < b0.bmin) b0.bmin = b5;
            if (b5 > b0.bmax) b0.bmax = b5;
        }
        box_count_ = 1;
    }
    while (box_count_ < kMedianCutBoxes) {
        int best = -1;
        uint64_t best_pop = 0;
        for (uint32_t b = 0; b < box_count_; ++b) {
            if (boxinfo_[b].end - boxinfo_[b].begin < 2) continue;
            if (boxinfo_[b].pop > best_pop) {  // tie: lowest box index
                best_pop = boxinfo_[b].pop;
                best = static_cast<int>(b);
            }
        }
        if (best < 0) break;
        BoxInfo box = boxinfo_[best];  // copy: partition overwrites [best]

        // Widest 5-bit channel axis from the cached extents (ties resolve
        // R > G > B by evaluation order).
        const int range_r = box.rmax - box.rmin;
        const int range_g = box.gmax - box.gmin;
        const int range_b = box.bmax - box.bmin;
        int axis;
        if (range_r >= range_g && range_r >= range_b) {
            axis = 0;
        } else if (range_g >= range_b) {
            axis = 1;
        } else {
            axis = 2;
        }
        const int vmax = axis == 0 ? box.rmax : axis == 1 ? box.gmax : box.bmax;
        const int vmin = axis == 0 ? box.rmin : axis == 1 ? box.gmin : box.bmin;

        // Population-weighted median of the chosen axis via a counting pass
        // over the 32 possible 5-bit values (no sort, no allocation).
        uint32_t by_value[32] = {0};
        for (uint32_t i = box.begin; i < box.end; ++i) {
            const BinEntry& e = bins_[i];
            const int v5 = axis == 0 ? ((e.idx >> 10) & 31)
                          : axis == 1 ? ((e.idx >> 5) & 31)
                                      : (e.idx & 31);
            by_value[v5] += e.pop;
        }
        uint32_t split_v = static_cast<uint32_t>(vmax);
        uint64_t cum = 0;
        for (int v = vmin; v <= vmax; ++v) {
            cum += by_value[v];
            if (cum * 2 >= box.pop) {
                split_v = static_cast<uint32_t>(v);
                break;
            }
        }
        // Both sides must be non-empty: the weighted median can legally be
        // vmax, in which case step one below the max (range >= 1 is
        // guaranteed because bins are unique).
        if (static_cast<int>(split_v) >= vmax) {
            split_v = static_cast<uint32_t>(vmax - 1);
        }

        // In-place two-way partition (deterministic forward scan with swap)
        // that also accumulates both sub-boxes' cached stats in the same
        // pass.
        BoxInfo left{};
        BoxInfo right{};
        left.begin = box.begin;
        left.end = box.begin;
        left.pop = 0;
        left.rmin = left.gmin = left.bmin = 31;
        left.rmax = left.gmax = left.bmax = 0;
        right = left;
        right.begin = right.end = box.end;
        uint32_t lo = box.begin;
        for (uint32_t i = box.begin; i < box.end; ++i) {
            const int v5 = axis == 0 ? ((bins_[i].idx >> 10) & 31)
                          : axis == 1 ? ((bins_[i].idx >> 5) & 31)
                                      : (bins_[i].idx & 31);
            const bool to_left = v5 <= static_cast<int>(split_v);
            if (to_left && lo != i) {
                std::swap(bins_[lo], bins_[i]);
            }
            BinEntry& e = to_left ? bins_[lo] : bins_[i];
            if (to_left) {
                ++lo;
            }
            const int r5 = (e.idx >> 10) & 31;
            const int g5 = (e.idx >> 5) & 31;
            const int b5 = e.idx & 31;
            BoxInfo& dst = to_left ? left : right;
            dst.pop += e.pop;
            if (r5 < dst.rmin) dst.rmin = r5;
            if (r5 > dst.rmax) dst.rmax = r5;
            if (g5 < dst.gmin) dst.gmin = g5;
            if (g5 > dst.gmax) dst.gmax = g5;
            if (b5 < dst.bmin) dst.bmin = b5;
            if (b5 > dst.bmax) dst.bmax = b5;
            ++dst.end;
        }
        left.begin = box.begin;
        left.end = lo;
        right.begin = lo;
        right.end = box.end;
        // Guard: pathological ties keep one side empty -> drop this split.
        if (left.begin == left.end || right.begin == right.end) break;

        boxinfo_[best] = left;
        boxinfo_[box_count_++] = right;
    }

    // ---- stage 3: seed k-means with the box centroids ------------------------
    for (uint32_t b = 0; b < box_count_; ++b) {
        uint64_t pop = 0, sr = 0, sg = 0, sb = 0;
        for (uint32_t i = boxinfo_[b].begin; i < boxinfo_[b].end; ++i) {
            pop += bins_[i].pop;
            sr += bins_[i].sum_r;
            sg += bins_[i].sum_g;
            sb += bins_[i].sum_b;
        }
        Cluster& c = clusters_[b];
        if (pop > 0) {
            c.r = static_cast<uint8_t>((sr + pop / 2) / pop);
            c.g = static_cast<uint8_t>((sg + pop / 2) / pop);
            c.b = static_cast<uint8_t>((sb + pop / 2) / pop);
        } else {
            c.r = c.g = c.b = 0;
        }
        c.population = pop;
    }
    cluster_count_ = box_count_;

    // ---- stage 4: Lloyd iterations (integer distances, deterministic ties) ---
    for (uint32_t iter = 0; iter < kKmeansMaxIterations; ++iter) {
        bool changed = false;
        // Assignment pass.
        for (uint32_t i = 0; i < n; ++i) {
            const BinEntry& e = bins_[i];
            uint32_t best_c = 0;
            int best_d = 1 << 30;
            for (uint32_t c = 0; c < cluster_count_; ++c) {
                const int dr = static_cast<int>(e.mean_r) - clusters_[c].r;
                const int dg = static_cast<int>(e.mean_g) - clusters_[c].g;
                const int db = static_cast<int>(e.mean_b) - clusters_[c].b;
                const int d = dr * dr + dg * dg + db * db;
                if (d < best_d) {  // tie: lowest cluster index (strict <)
                    best_d = d;
                    best_c = c;
                }
            }
            if (assign_[i] != best_c) {
                assign_[i] = best_c;
                changed = true;
            }
        }
        // Update pass: exact weighted means.
        uint64_t pop_acc[kKmeansClusters] = {0};
        uint64_t sr_acc[kKmeansClusters] = {0};
        uint64_t sg_acc[kKmeansClusters] = {0};
        uint64_t sb_acc[kKmeansClusters] = {0};
        for (uint32_t i = 0; i < n; ++i) {
            const uint32_t c = assign_[i];
            pop_acc[c] += bins_[i].pop;
            sr_acc[c] += bins_[i].sum_r;
            sg_acc[c] += bins_[i].sum_g;
            sb_acc[c] += bins_[i].sum_b;
        }
        for (uint32_t c = 0; c < cluster_count_; ++c) {
            if (pop_acc[c] > 0) {
                clusters_[c].r =
                    static_cast<uint8_t>((sr_acc[c] + pop_acc[c] / 2) / pop_acc[c]);
                clusters_[c].g =
                    static_cast<uint8_t>((sg_acc[c] + pop_acc[c] / 2) / pop_acc[c]);
                clusters_[c].b =
                    static_cast<uint8_t>((sb_acc[c] + pop_acc[c] / 2) / pop_acc[c]);
                clusters_[c].population = pop_acc[c];
            } else {
                // Empty cluster: reseed at the bin farthest from its own
                // assigned centroid (tie: lowest bin index).
                uint32_t far_i = 0;
                int far_d = -1;
                for (uint32_t i = 0; i < n; ++i) {
                    const BinEntry& e = bins_[i];
                    const Cluster& cc = clusters_[assign_[i]];
                    const int dr = static_cast<int>(e.mean_r) - cc.r;
                    const int dg = static_cast<int>(e.mean_g) - cc.g;
                    const int db = static_cast<int>(e.mean_b) - cc.b;
                    const int d = dr * dr + dg * dg + db * db;
                    if (d > far_d) {
                        far_d = d;
                        far_i = i;
                    }
                }
                clusters_[c].r = bins_[far_i].mean_r;
                clusters_[c].g = bins_[far_i].mean_g;
                clusters_[c].b = bins_[far_i].mean_b;
                clusters_[c].population = 0;
                changed = true;  // force one more iteration after reseeding
            }
        }
        if (!changed) break;
    }
    return true;
}

// ---- role scoring -------------------------------------------------------------------

void SimdPaletteExtractor::ScoreRoles(PaletteRoles* out) const {
    if (cluster_count_ == 0) {
        *out = PaletteRoles{};  // defensive; unreachable for valid inputs
        return;
    }
    double total_pop = 0.0;
    for (uint32_t c = 0; c < cluster_count_; ++c) {
        total_pop += static_cast<double>(clusters_[c].population);
    }
    if (total_pop <= 0.0) {
        *out = PaletteRoles{};
        return;
    }

    // Dominant Vibrant: population weight x saturation^2 x value shaping.
    // Ties fall back to the higher-population cluster, then the lower index.
    uint32_t dom_c = 0;
    double dom_score = -1.0;
    uint64_t dom_pop = 0;
    uint32_t dark_c = 0;
    double dark_score = -1.0;
    uint64_t dark_pop = 0;
    uint32_t light_c = 0;
    double light_score = -1.0;
    uint64_t light_pop = 0;
    for (uint32_t c = 0; c < cluster_count_; ++c) {
        const Cluster& cl = clusters_[c];
        const Hsv hsv = RgbToHsv(cl.r, cl.g, cl.b);
        const double pop_w =
            static_cast<double>(cl.population) / total_pop;
        const double v_shape = 0.30 + 0.70 * Clamp01((hsv.v - 0.15f) / 0.85f);
        const double s_dom = pop_w * (0.02 + 0.98 * hsv.s * hsv.s) * v_shape;
        if (s_dom > dom_score ||
            (s_dom == dom_score && cl.population > dom_pop)) {
            dom_score = s_dom;
            dom_pop = cl.population;
            dom_c = c;
        }
        const double s_dark = pop_w * (1.0 - hsv.s) * (1.0 - hsv.v);
        if (s_dark > dark_score ||
            (s_dark == dark_score && cl.population > dark_pop)) {
            dark_score = s_dark;
            dark_pop = cl.population;
            dark_c = c;
        }
        const double s_light = pop_w * hsv.s * hsv.v;
        if (s_light > light_score ||
            (s_light == light_score && cl.population > light_pop)) {
            light_score = s_light;
            light_pop = cl.population;
            light_c = c;
        }
    }

    const Cluster& dom_cl = clusters_[dom_c];
    const uint32_t dominant = PackArgb(dom_cl.r, dom_cl.g, dom_cl.b);

    // Dark Muted (Background): clamp V <= 0.50, S <= 0.40 by construction so
    // the background is always dark and quiet (luminance <= 0.216 provably).
    {
        const Cluster& pick = clusters_[dark_c];
        const Hsv pick_hsv = RgbToHsv(pick.r, pick.g, pick.b);
        const float s2 = pick_hsv.s < 0.40f ? pick_hsv.s : 0.40f;
        float v2 = pick_hsv.v < 0.50f ? pick_hsv.v : 0.50f;
        uint32_t dark = HsvToArgb(pick_hsv.h, s2, v2);
        // Distinctness ladder: shrink V (<= 4 steps) away from an equal
        // dominant, then fixed dark grays.
        for (int step = 0; step < 4 && dark == dominant; ++step) {
            v2 *= 0.6f;
            if (v2 < 0.02f) v2 = 0.02f;
            dark = HsvToArgb(pick_hsv.h, s2, v2);
        }
        static const uint32_t kGrayLadder[4] = {0xFF0A0A0Au, 0xFF101010u,
                                                0xFF1A1A1Au, 0xFF242424u};
        for (int i = 0; i < 4 && dark == dominant; ++i) {
            dark = kGrayLadder[i];
        }
        out->dark_muted = dark;
    }

    // Light Vibrant (Accents): force V >= 0.80; force S >= 0.35 only when the
    // source hue actually exists (grayscale images stay grayscale).
    {
        const Cluster& pick = clusters_[light_c];
        const Hsv pick_hsv = RgbToHsv(pick.r, pick.g, pick.b);
        const bool has_hue =
            (pick.r != pick.g) || (pick.g != pick.b) || (pick.r != pick.b);
        float s2 = pick_hsv.s;
        if (has_hue && s2 < 0.35f) s2 = 0.35f;
        float v2 = pick_hsv.v > 0.80f ? pick_hsv.v : 0.80f;
        uint32_t light = HsvToArgb(pick_hsv.h, s2, v2);
        for (int step = 0; step < 8 && light == dominant; ++step) {
            v2 = v2 * 1.05f + 0.03f;
            if (v2 > 0.98f) v2 = 0.98f;
            light = HsvToArgb(pick_hsv.h, s2, v2);
        }
        static const uint32_t kBrightLadder[4] = {0xFFFAFAFAu, 0xFFF5F5F5u,
                                                  0xFFF0F0F0u, 0xFFEBEBEBu};
        for (int i = 0; i < 4 && light == dominant; ++i) {
            light = kBrightLadder[i];
        }
        out->light_vibrant = light;
    }

    out->dominant_vibrant = dominant;

    // Contrast Text Surface. The HARD constraint is >= 4.5:1 against the
    // dominant role (always satisfiable: the better pole is >= sqrt(21) =
    // 4.58). >= 4.5:1 against the background role is best-effort: prefer a
    // pole that satisfies both when one exists, but NEVER trade away the
    // dominant guarantee for it. The bounded tint ladder only runs when the
    // chosen pole satisfies both constraints and every rung re-verifies.
    {
        const uint32_t white = 0xFFFFFFFFu;
        const uint32_t black = 0xFF000000u;
        const float w_dom = ContrastRatio(white, dominant);
        const float b_dom = ContrastRatio(black, dominant);
        const float w_bg = ContrastRatio(white, out->dark_muted);
        const float b_bg = ContrastRatio(black, out->dark_muted);
        const bool w_ok_dom = w_dom >= kWcagAaTextRatio;
        const bool b_ok_dom = b_dom >= kWcagAaTextRatio;
        const bool w_both = w_ok_dom && w_bg >= kWcagAaTextRatio;
        const bool b_both = b_ok_dom && b_bg >= kWcagAaTextRatio;

        bool white_pole;
        if (w_both && b_both) {
            // Both poles satisfy everything: pick the stronger minimum
            // (ties -> white, documented determinism).
            const float w_min = w_dom < w_bg ? w_dom : w_bg;
            const float b_min = b_dom < b_bg ? b_dom : b_bg;
            white_pole = w_min >= b_min;
        } else {
            // Prefer a both-passing pole; otherwise fall back to the pole
            // that upholds the dominant guarantee.
            white_pole = w_both || (!b_both && w_dom >= b_dom);
        }
        const uint32_t pole = white_pole ? white : black;
        const bool pole_passes_both = white_pole ? w_both : b_both;

        uint32_t text = pole;
        if (pole_passes_both) {
            const uint32_t tint = white_pole ? out->light_vibrant : dominant;
            for (int t = 14; t >= 2; t -= 2) {  // t/16 blend, strongest first
                const uint32_t cand = BlendArgb(pole, tint,
                                                static_cast<uint32_t>(t) * 16);
                if (ContrastRatio(cand, dominant) >= kWcagAaTextRatio &&
                    ContrastRatio(cand, out->dark_muted) >= kWcagAaTextRatio) {
                    text = cand;
                    break;
                }
            }
        }
        out->text_surface = text;
    }
}

// ---- top-level pipeline -------------------------------------------------------------

PaletteError SimdPaletteExtractor::Extract(const uint8_t* rgba,
                                           size_t byte_len, int32_t width,
                                           int32_t height, int32_t stride_bytes,
                                           PaletteRoles* out) {
    if (out == nullptr) return PaletteError::kNullArgument;
    if (!valid()) return PaletteError::kOutOfMemory;
    uint64_t needed = 0;
    const PaletteError e = ValidateIngest(rgba, byte_len, width, height,
                                          stride_bytes, &needed);
    if (e != PaletteError::kOk) return e;

    ClearHistogram();
    const uint64_t total =
        static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
    if (total <= kPaletteMaxSamples) {
        for (int32_t y = 0; y < height; ++y) {
            IngestRowSimd(rgba + static_cast<size_t>(y) * stride_bytes, width);
        }
        last_sample_count_ = static_cast<size_t>(total);
    } else {
        // Row-strided subsampling: full-width SIMD rows every Nth row.
        // rows_target >= 1 for every legal width (<= 16384 << 65536).
        const uint64_t rows_target =
            kPaletteMaxSamples / static_cast<uint64_t>(width);
        const uint32_t row_step = static_cast<uint32_t>(
            (static_cast<uint64_t>(height) + rows_target - 1) / rows_target);
        IngestStridedRows(rgba, width, height, stride_bytes, row_step);
    }

    if (!Quantize()) {
        *out = PaletteRoles{};
        return PaletteError::kOk;  // unreachable for validated inputs
    }
    ScoreRoles(out);
    return PaletteError::kOk;
}

// ---- thread-local convenience entry point -------------------------------------------

PaletteError ExtractPaletteSimd(const uint8_t* rgba, size_t byte_len,
                                int32_t width, int32_t height,
                                int32_t stride_bytes, PaletteRoles* out) {
    thread_local SimdPaletteExtractor extractor;  // ~0.75 MB per thread, once
    if (!extractor.valid()) return PaletteError::kOutOfMemory;
    return extractor.Extract(rgba, byte_len, width, height, stride_bytes, out);
}
}  // namespace palette_simd
}  // namespace streamify
