// ============================================================================
//  BitmapPalette.cc — see BitmapPalette.h for the pipeline contracts.
// ============================================================================

#include "BitmapPalette.h"

#include <cmath>
#include <cstring>

#include "../util/NeonCompat.h"

#if STREAMIFY_HAVE_NEON
#include <arm_neon.h>
#elif defined(__SSE2__)
#include <emmintrin.h>
#endif

namespace streamify::palette {

namespace {

inline uint32_t packArgb(int r, int g, int b) {
    return 0xFF000000u |
           (static_cast<uint32_t>(r & 0xFF) << 16) |
           (static_cast<uint32_t>(g & 0xFF) << 8) |
           static_cast<uint32_t>(b & 0xFF);
}

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

struct Hsl {
    float h;  // [0,360)
    float s;  // [0,1]
    float l;  // [0,1]
};

Hsl rgbToHsl(int r, int g, int b) {
    const float rf = static_cast<float>(r) * (1.0f / 255.0f);
    const float gf = static_cast<float>(g) * (1.0f / 255.0f);
    const float bf = static_cast<float>(b) * (1.0f / 255.0f);
    const float maxc = rf > gf ? (rf > bf ? rf : bf) : (gf > bf ? gf : bf);
    const float minc = rf < gf ? (rf < bf ? rf : bf) : (gf < bf ? gf : bf);
    const float d = maxc - minc;
    Hsl hsl{0.0f, 0.0f, (maxc + minc) * 0.5f};
    if (d <= 0.000001f) {
        hsl.h = 0.0f;
        hsl.s = 0.0f;
        return hsl;
    }
    hsl.s = d / (1.0f - std::fabs(2.0f * hsl.l - 1.0f));
    if (maxc == rf) {
        hsl.h = 60.0f * std::fmod((gf - bf) / d, 6.0f);
    } else if (maxc == gf) {
        hsl.h = 60.0f * ((bf - rf) / d + 2.0f);
    } else {
        hsl.h = 60.0f * ((rf - gf) / d + 4.0f);
    }
    if (hsl.h < 0.0f) hsl.h += 360.0f;
    return hsl;
}

float hueToRgbComponent(float m2, float m1, float h) {
    if (h < 0.0f) h += 1.0f;
    if (h > 1.0f) h -= 1.0f;
    if (h < 1.0f / 6.0f) return m1 + (m2 - m1) * 6.0f * h;
    if (h < 0.5f) return m2;
    if (h < 2.0f / 3.0f) return m1 + (m2 - m1) * (2.0f / 3.0f - h) * 6.0f;
    return m1;
}

void hslToRgb(float h, float s, float l, int* r, int* g, int* b) {
    if (s <= 0.0f) {
        *r = *g = *b = static_cast<int>(l * 255.0f + 0.5f);
        return;
    }
    const float m2 = l < 0.5f ? l * (1.0f + s) : (l + s - l * s);
    const float m1 = 2.0f * l - m2;
    const float hue = h * (1.0f / 360.0f);
    *r = static_cast<int>(hueToRgbComponent(m2, m1, hue + 1.0f / 3.0f) * 255.0f + 0.5f);
    *g = static_cast<int>(hueToRgbComponent(m2, m1, hue) * 255.0f + 0.5f);
    *b = static_cast<int>(hueToRgbComponent(m2, m1, hue - 1.0f / 3.0f) * 255.0f + 0.5f);
}

// Smooth bell over [center-width, center+width], 1 at center, 0 at edges.
float bell(float x, float center, float width) {
    const float d = (x - center) / width;
    if (d <= -1.0f || d >= 1.0f) return 0.0f;
    return 0.5f + 0.5f * std::cos(3.14159265f * d);
}

}  // namespace

float relativeLuminance(uint32_t rgb) {
    auto lin = [](float c) {
        return c <= 0.04045f ? c / 12.92f
                             : std::pow((c + 0.055f) / 1.055f, 2.4f);
    };
    const float r = lin(static_cast<float>((rgb >> 16) & 0xFF) * (1.0f / 255.0f));
    const float g = lin(static_cast<float>((rgb >> 8) & 0xFF) * (1.0f / 255.0f));
    const float b = lin(static_cast<float>(rgb & 0xFF) * (1.0f / 255.0f));
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

float contrastRatio(uint32_t rgbA, uint32_t rgbB) {
    const float la = relativeLuminance(rgbA);
    const float lb = relativeLuminance(rgbB);
    const float hi = la > lb ? la : lb;
    const float lo = la > lb ? lb : la;
    return (hi + 0.05f) / (lo + 0.05f);
}

int32_t BitmapPaletteExtractor::bytesPerPixel(PixelFormat fmt) {
    switch (fmt) {
    case PixelFormat::kRgba8888:
    case PixelFormat::kArgb8888:
        return 4;
    case PixelFormat::kRgb565:
        return 2;
    }
    return 0;
}

void BitmapPaletteExtractor::clearScratch() {
    std::memset(binPop_, 0, sizeof(binPop_));
    std::memset(binRSum_, 0, sizeof(binRSum_));
    std::memset(binGSum_, 0, sizeof(binGSum_));
    std::memset(binBSum_, 0, sizeof(binBSum_));
}

// ---------------------------------------------------------------------------
// SIMD ingest — indices batched by NEON (16 px) / SSE2 (4 px); the scatter
// add stays scalar (memory-bound either way) and re-reads the pixel bytes
// from L1 for the exact 8-bit channel sums.
// ---------------------------------------------------------------------------
void BitmapPaletteExtractor::ingestRgba(const uint8_t* pixels,
                                        size_t pixelCount) {
    size_t i = 0;
    const uint8_t* p = pixels;
#if STREAMIFY_HAVE_NEON
    for (; i + 16 <= pixelCount; i += 16, p += 64) {
        const uint8x16x4_t px = vld4q_u8(p);
        const uint8x16_t r4 = vshrq_n_u8(px.val[0], 4);
        const uint8x16_t g4 = vshrq_n_u8(px.val[1], 4);
        const uint8x16_t b4 = vshrq_n_u8(px.val[2], 4);
        const uint16x8_t rLo = vshll_n_u8(vget_low_u8(r4), 8);
        const uint16x8_t rHi = vshll_n_u8(vget_high_u8(r4), 8);
        const uint16x8_t gLo = vshll_n_u8(vget_low_u8(g4), 4);
        const uint16x8_t gHi = vshll_n_u8(vget_high_u8(g4), 4);
        const uint16x8_t bLo = vmovl_u8(vget_low_u8(b4));
        const uint16x8_t bHi = vmovl_u8(vget_high_u8(b4));
        alignas(16) uint16_t idx[16];
        vst1q_u16(idx, vorrq_u16(vorrq_u16(rLo, gLo), bLo));
        vst1q_u16(idx + 8, vorrq_u16(vorrq_u16(rHi, gHi), bHi));
        for (int k = 0; k < 16; ++k) {
            const size_t bin = idx[k];
            binPop_[bin]++;
            binRSum_[bin] += p[k * 4 + 0];
            binGSum_[bin] += p[k * 4 + 1];
            binBSum_[bin] += p[k * 4 + 2];
        }
    }
#elif defined(__SSE2__)
    for (; i + 4 <= pixelCount; i += 4, p += 16) {
        const __m128i v = _mm_loadu_si128(reinterpret_cast<const __m128i*>(p));
        const __m128i mask = _mm_set1_epi32(0xFF);
        const __m128i r = _mm_and_si128(v, mask);
        const __m128i g = _mm_and_si128(_mm_srli_epi32(v, 8), mask);
        const __m128i b = _mm_and_si128(_mm_srli_epi32(v, 16), mask);
        const __m128i idx = _mm_add_epi32(
            _mm_add_epi32(_mm_slli_epi32(_mm_srli_epi32(r, 4), 8),
                          _mm_slli_epi32(_mm_srli_epi32(g, 4), 4)),
            _mm_srli_epi32(b, 4));
        alignas(16) int32_t idx4[4];
        _mm_store_si128(reinterpret_cast<__m128i*>(idx4), idx);
        for (int k = 0; k < 4; ++k) {
            const size_t bin = static_cast<size_t>(idx4[k]) & 0xFFFu;
            binPop_[bin]++;
            binRSum_[bin] += p[k * 4 + 0];
            binGSum_[bin] += p[k * 4 + 1];
            binBSum_[bin] += p[k * 4 + 2];
        }
    }
#endif
    // Scalar tail (and the whole path on non-SIMD builds).
    for (; i < pixelCount; ++i, p += 4) {
        const uint32_t bin = (static_cast<uint32_t>(p[0] >> 4) << 8) |
                             (static_cast<uint32_t>(p[1] >> 4) << 4) |
                             static_cast<uint32_t>(p[2] >> 4);
        binPop_[bin]++;
        binRSum_[bin] += p[0];
        binGSum_[bin] += p[1];
        binBSum_[bin] += p[2];
    }
}

void BitmapPaletteExtractor::ingestArgb(const uint8_t* pixels,
                                        size_t pixelCount) {
    size_t i = 0;
    const uint8_t* p = pixels;
#if STREAMIFY_HAVE_NEON
    for (; i + 16 <= pixelCount; i += 16, p += 64) {
        const uint8x16x4_t px = vld4q_u8(p);  // val0=A val1=R val2=G val3=B
        const uint8x16_t r4 = vshrq_n_u8(px.val[1], 4);
        const uint8x16_t g4 = vshrq_n_u8(px.val[2], 4);
        const uint8x16_t b4 = vshrq_n_u8(px.val[3], 4);
        const uint16x8_t rLo = vshll_n_u8(vget_low_u8(r4), 8);
        const uint16x8_t rHi = vshll_n_u8(vget_high_u8(r4), 8);
        const uint16x8_t gLo = vshll_n_u8(vget_low_u8(g4), 4);
        const uint16x8_t gHi = vshll_n_u8(vget_high_u8(g4), 4);
        const uint16x8_t bLo = vmovl_u8(vget_low_u8(b4));
        const uint16x8_t bHi = vmovl_u8(vget_high_u8(b4));
        alignas(16) uint16_t idx[16];
        vst1q_u16(idx, vorrq_u16(vorrq_u16(rLo, gLo), bLo));
        vst1q_u16(idx + 8, vorrq_u16(vorrq_u16(rHi, gHi), bHi));
        for (int k = 0; k < 16; ++k) {
            const size_t bin = idx[k];
            binPop_[bin]++;
            binRSum_[bin] += p[k * 4 + 1];
            binGSum_[bin] += p[k * 4 + 2];
            binBSum_[bin] += p[k * 4 + 3];
        }
    }
#elif defined(__SSE2__)
    for (; i + 4 <= pixelCount; i += 4, p += 16) {
        const __m128i v = _mm_loadu_si128(reinterpret_cast<const __m128i*>(p));
        const __m128i mask = _mm_set1_epi32(0xFF);
        const __m128i r = _mm_and_si128(_mm_srli_epi32(v, 8), mask);
        const __m128i g = _mm_and_si128(_mm_srli_epi32(v, 16), mask);
        const __m128i b = _mm_and_si128(_mm_srli_epi32(v, 24), mask);
        const __m128i idx = _mm_add_epi32(
            _mm_add_epi32(_mm_slli_epi32(_mm_srli_epi32(r, 4), 8),
                          _mm_slli_epi32(_mm_srli_epi32(g, 4), 4)),
            _mm_srli_epi32(b, 4));
        alignas(16) int32_t idx4[4];
        _mm_store_si128(reinterpret_cast<__m128i*>(idx4), idx);
        for (int k = 0; k < 4; ++k) {
            const size_t bin = static_cast<size_t>(idx4[k]) & 0xFFFu;
            binPop_[bin]++;
            binRSum_[bin] += p[k * 4 + 1];
            binGSum_[bin] += p[k * 4 + 2];
            binBSum_[bin] += p[k * 4 + 3];
        }
    }
#endif
    for (; i < pixelCount; ++i, p += 4) {
        const uint32_t bin = (static_cast<uint32_t>(p[1] >> 4) << 8) |
                             (static_cast<uint32_t>(p[2] >> 4) << 4) |
                             static_cast<uint32_t>(p[3] >> 4);
        binPop_[bin]++;
        binRSum_[bin] += p[1];
        binGSum_[bin] += p[2];
        binBSum_[bin] += p[3];
    }
}

void BitmapPaletteExtractor::ingestRgb565(const uint8_t* pixels,
                                          size_t pixelCount) {
    for (size_t i = 0; i < pixelCount; ++i) {
        const size_t off = i * 2;
        const uint32_t v = static_cast<uint32_t>(pixels[off]) |
                           (static_cast<uint32_t>(pixels[off + 1]) << 8);
        const uint32_t r5 = (v >> 11) & 31u;
        const uint32_t g6 = (v >> 5) & 63u;
        const uint32_t b5 = v & 31u;
        // Bit-replication expansion (deterministic, standard).
        const uint32_t r8 = (r5 << 3) | (r5 >> 2);
        const uint32_t g8 = (g6 << 2) | (g6 >> 4);
        const uint32_t b8 = (b5 << 3) | (b5 >> 2);
        const uint32_t bin = (r8 >> 4) << 8 | (g8 >> 4) << 4 | (b8 >> 4);
        binPop_[bin]++;
        binRSum_[bin] += r8;
        binGSum_[bin] += g8;
        binBSum_[bin] += b8;
    }
}

int32_t BitmapPaletteExtractor::compactBins() {
    int32_t n = 0;
    for (int32_t i = 0; i < kHistogramBins; ++i) {
        if (binPop_[static_cast<size_t>(i)] != 0) {
            order_[static_cast<size_t>(n++)] = static_cast<uint16_t>(i);
        }
    }
    return n;
}

// ---------------------------------------------------------------------------
// Modified median cut: split the box with the largest population*variance on
// its max-variance axis at the population-weighted median. Channel values
// live in 0..15 (4-bit bin coordinates) so partitions are counting sorts.
// ---------------------------------------------------------------------------
int32_t BitmapPaletteExtractor::medianCut(int32_t populated) {
    int32_t boxCount = 1;
    boxBegin_[0] = 0;
    boxEnd_[0] = populated;
    for (int32_t i = 0; i <= kMaxSwatches; ++i) {
        boxDead_[static_cast<size_t>(i)] = false;
    }

    uint64_t totalPop = 0;
    for (int32_t i = 0; i < populated; ++i) {
        totalPop += binPop_[order_[static_cast<size_t>(i)]];
    }

    while (boxCount < kMaxSwatches) {
        // Find the splittable box with the best score.
        int32_t bestBox = -1;
        double bestScore = 0.0;
        int32_t bestAxis = -1;
        for (int32_t b = 0; b < boxCount; ++b) {
            if (boxDead_[static_cast<size_t>(b)]) continue;
            const int32_t begin = boxBegin_[b];
            const int32_t end = boxEnd_[b];
            if (end - begin < 2) continue;
            // Weighted stats over the 4-bit channel values.
            uint64_t pop = 0;
            double sum[3] = {0.0, 0.0, 0.0};
            double sumSq[3] = {0.0, 0.0, 0.0};
            for (int32_t i = begin; i < end; ++i) {
                const uint32_t bin = order_[static_cast<size_t>(i)];
                const double w = static_cast<double>(binPop_[bin]);
                const double ch[3] = {static_cast<double>((bin >> 8) & 0xF),
                                      static_cast<double>((bin >> 4) & 0xF),
                                      static_cast<double>(bin & 0xF)};
                pop += binPop_[bin];
                for (int c = 0; c < 3; ++c) {
                    sum[c] += w * ch[c];
                    sumSq[c] += w * ch[c] * ch[c];
                }
            }
            if (pop < 2) continue;
            const double invP = 1.0 / static_cast<double>(pop);
            double var[3];
            double varSum = 0.0;
            int32_t axis = 0;
            for (int c = 0; c < 3; ++c) {
                var[c] = sumSq[c] * invP - sum[c] * invP * sum[c] * invP;
                if (var[c] < 0.0) var[c] = 0.0;
                varSum += var[c];
                if (var[c] > var[axis]) axis = c;
            }
            if (var[axis] <= 0.0) continue;  // single-color box
            // Split budget: don't shard dust below ~1/256 of the image.
            if (pop < totalPop / 256 && boxCount > 4) continue;
            const double score = static_cast<double>(pop) * varSum;
            if (score > bestScore) {
                bestScore = score;
                bestBox = b;
                bestAxis = axis;
            }
        }
        if (bestBox < 0) break;

        // Weighted-median split value on the chosen axis.
        const int32_t begin = boxBegin_[bestBox];
        const int32_t end = boxEnd_[bestBox];
        uint64_t pop = 0;
        uint64_t count[16] = {};
        const int shift = bestAxis == 0 ? 8 : (bestAxis == 1 ? 4 : 0);
        for (int32_t i = begin; i < end; ++i) {
            const uint32_t bin = order_[static_cast<size_t>(i)];
            const uint64_t w = binPop_[bin];
            count[(bin >> shift) & 0xF] += w;
            pop += w;
        }
        uint64_t cum = 0;
        int32_t splitAfter = 15;
        for (int32_t v = 0; v < 16; ++v) {
            cum += count[v];
            if (cum >= (pop + 1) / 2) {
                splitAfter = v;
                break;
            }
        }
        // Guarantee a non-empty right side (variance > 0 on the split axis
        // ensures one exists BELOW the median value). The walk must be
        // allowed to start FROM splitAfter == 15: when the weighted median
        // lands on the topmost populated coordinate (majority mass at the
        // maximum value — vanishingly rare in natural images, trivial for
        // fuzz noise), splitting at 15 leaves the right side empty and the
        // split degenerates. Found by the exact-size local fuzz soak
        // (RGB565 noise image, majority mass at G=15).
        while (splitAfter > 0) {
            uint64_t right = 0;
            for (int32_t v = splitAfter + 1; v < 16; ++v) right += count[v];
            if (right != 0) break;
            --splitAfter;
        }
        // In-place (unstable, deterministic) two-pointer partition.
        int32_t l = begin;
        int32_t r = end - 1;
        while (l <= r) {
            const uint32_t bin = order_[static_cast<size_t>(l)];
            if (((bin >> shift) & 0xF) <= static_cast<uint32_t>(splitAfter)) {
                ++l;
            } else {
                const uint16_t tmp = order_[static_cast<size_t>(r)];
                order_[static_cast<size_t>(r)] = static_cast<uint16_t>(bin);
                order_[static_cast<size_t>(l)] = tmp;
                --r;
            }
        }
        const int32_t splitPoint = l;  // [begin,l) left, [l,end) right
        if (splitPoint <= begin || splitPoint >= end) {
            // Unreachable with the guards above (left is non-empty because
            // the median scan stops on a populated value; right is
            // non-empty because of the splitAfter walk). Still: NEVER
            // retry the same box with identical state — that would be an
            // infinite loop. Retire the box instead; termination is then
            // unconditional (each pass either splits or retires).
            boxDead_[static_cast<size_t>(bestBox)] = true;
            continue;
        }
        // Register the new box.
        boxBegin_[boxCount] = splitPoint;
        boxEnd_[boxCount] = end;
        boxEnd_[bestBox] = splitPoint;
        ++boxCount;
    }
    return boxCount;
}

// ---------------------------------------------------------------------------
// K-means subset refinement over populated bins (bounded iterations).
// ---------------------------------------------------------------------------
void BitmapPaletteExtractor::kmeansRefine(int32_t /*populated*/,
                                          int32_t swatchCount) {
    if (swatchCount <= 0) return;
    double accR[kMaxSwatches] = {};
    double accG[kMaxSwatches] = {};
    double accB[kMaxSwatches] = {};
    uint64_t accPop[kMaxSwatches] = {};

    for (int iter = 0; iter < kKmeansIterations; ++iter) {
        std::memset(accR, 0, sizeof(double) * static_cast<size_t>(swatchCount));
        std::memset(accG, 0, sizeof(double) * static_cast<size_t>(swatchCount));
        std::memset(accB, 0, sizeof(double) * static_cast<size_t>(swatchCount));
        std::memset(accPop, 0, sizeof(uint64_t) * static_cast<size_t>(swatchCount));
        int32_t changed = 0;

        for (int32_t i = 0; i < kHistogramBins; ++i) {
            const uint32_t bin = static_cast<uint32_t>(i);
            if (binPop_[bin] == 0) continue;
            // Representative color: exact 4-bit expansion (deterministic).
            const float r = static_cast<float>(((bin >> 8) & 0xF) * 17);
            const float g = static_cast<float>(((bin >> 4) & 0xF) * 17);
            const float b = static_cast<float>((bin & 0xF) * 17);
            int32_t best = 0;
            float bestD = 1e30f;
            for (int32_t c = 0; c < swatchCount; ++c) {
                const float dr = r - swatchR_[c];
                const float dg = g - swatchG_[c];
                const float db = b - swatchB_[c];
                const float d = dr * dr + dg * dg + db * db;
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            if (assign_[bin] != static_cast<uint8_t>(best)) {
                assign_[bin] = static_cast<uint8_t>(best);
                ++changed;
            }
            accR[best] += static_cast<double>(binRSum_[bin]);
            accG[best] += static_cast<double>(binGSum_[bin]);
            accB[best] += static_cast<double>(binBSum_[bin]);
            accPop[best] += binPop_[bin];
        }

        // Recompute centroids; reseed empty clusters at the farthest bin
        // (deterministic: first maximum wins).
        for (int32_t c = 0; c < swatchCount; ++c) {
            if (accPop[c] > 0) {
                const double inv = 1.0 / static_cast<double>(accPop[c]);
                swatchR_[c] = static_cast<float>(accR[c] * inv);
                swatchG_[c] = static_cast<float>(accG[c] * inv);
                swatchB_[c] = static_cast<float>(accB[c] * inv);
                swatchPop_[c] = accPop[c];
            } else {
                // Empty: steal the bin with the worst fit to its centroid.
                uint32_t farBin = 0xFFFFFFFFu;
                float farD = -1.0f;
                for (int32_t i = 0; i < kHistogramBins; ++i) {
                    const uint32_t bin = static_cast<uint32_t>(i);
                    if (binPop_[bin] == 0) continue;
                    const float r = static_cast<float>(((bin >> 8) & 0xF) * 17);
                    const float g = static_cast<float>(((bin >> 4) & 0xF) * 17);
                    const float b = static_cast<float>((bin & 0xF) * 17);
                    const int32_t oc = assign_[bin] < static_cast<uint8_t>(swatchCount)
                                           ? assign_[bin] : 0;
                    const float dr = r - swatchR_[oc];
                    const float dg = g - swatchG_[oc];
                    const float db = b - swatchB_[oc];
                    const float d = dr * dr + dg * dg + db * db;
                    if (d > farD) {
                        farD = d;
                        farBin = bin;
                    }
                }
                if (farBin != 0xFFFFFFFFu) {
                    assign_[farBin] = static_cast<uint8_t>(c);
                    accR[c] = static_cast<double>(binRSum_[farBin]);
                    accG[c] = static_cast<double>(binGSum_[farBin]);
                    accB[c] = static_cast<double>(binBSum_[farBin]);
                    accPop[c] = binPop_[farBin];
                    const double inv = 1.0 / static_cast<double>(accPop[c]);
                    swatchR_[c] = static_cast<float>(accR[c] * inv);
                    swatchG_[c] = static_cast<float>(accG[c] * inv);
                    swatchB_[c] = static_cast<float>(accB[c] * inv);
                    swatchPop_[c] = accPop[c];
                    changed = 1;  // keep iterating
                }
            }
        }
        if (changed == 0) break;
    }
}

// ---------------------------------------------------------------------------
// Role scoring.
// ---------------------------------------------------------------------------
void BitmapPaletteExtractor::scoreSwatches(int32_t swatchCount,
                                           PaletteResult* out) const {
    struct Meta {
        Hsl hsl;
        float lum;
        double vibrantScore;
        double mutedScore;
    };
    Meta meta[kMaxSwatches];

    uint64_t maxPop = 0;
    int32_t dominant = 0;
    for (int32_t c = 0; c < swatchCount; ++c) {
        const int r = static_cast<int>(swatchR_[c] + 0.5f);
        const int g = static_cast<int>(swatchG_[c] + 0.5f);
        const int b = static_cast<int>(swatchB_[c] + 0.5f);
        meta[c].hsl = rgbToHsl(r, g, b);
        meta[c].lum = relativeLuminance(packArgb(r, g, b));
        const double pop = static_cast<double>(swatchPop_[c]);
        meta[c].vibrantScore =
            pop * meta[c].hsl.s * meta[c].hsl.s *
            bell(meta[c].hsl.l, 0.50f, 0.32f);
        meta[c].mutedScore =
            pop * (1.0 - meta[c].hsl.s) * (1.0 - meta[c].hsl.s) *
            bell(meta[c].hsl.l, 0.45f, 0.40f);
        if (swatchPop_[c] > maxPop) {
            maxPop = swatchPop_[c];
            dominant = c;
        }
    }

    // Primary: best vibrant with 3-pass relaxation; else the dominant.
    int32_t primary = -1;
    const float satPass[3] = {0.35f, 0.20f, 0.0f};
    const float lLo[3] = {0.25f, 0.10f, 0.0f};
    const float lHi[3] = {0.72f, 0.90f, 1.0f};
    for (int pass = 0; pass < 3 && primary < 0; ++pass) {
        double best = 0.0;
        for (int32_t c = 0; c < swatchCount; ++c) {
            if (meta[c].hsl.s >= satPass[pass] &&
                meta[c].hsl.l >= lLo[pass] && meta[c].hsl.l <= lHi[pass] &&
                meta[c].vibrantScore > best) {
                best = meta[c].vibrantScore;
                primary = c;
            }
        }
    }
    if (primary < 0) primary = dominant;

    // Secondary: a genuinely distinct companion (hue or lightness distance).
    int32_t secondary = -1;
    {
        double best = 0.0;
        for (int32_t c = 0; c < swatchCount; ++c) {
            if (c == primary) continue;
            if (static_cast<double>(swatchPop_[c]) <
                0.05 * static_cast<double>(maxPop)) {
                continue;
            }
            const float dh = std::fabs(meta[c].hsl.h - meta[primary].hsl.h);
            const float hueDist = dh > 180.0f ? 360.0f - dh : dh;
            const bool achromatic =
                meta[c].hsl.s < 0.12f || meta[primary].hsl.s < 0.12f;
            const float dL = std::fabs(meta[c].hsl.l - meta[primary].hsl.l);
            const bool distinct = achromatic ? (dL >= 0.25f)
                                             : (hueDist >= 36.0f || dL >= 0.25f);
            if (distinct && static_cast<double>(swatchPop_[c]) > best) {
                best = static_cast<double>(swatchPop_[c]);
                secondary = c;
            }
        }
        if (secondary < 0) {
            // Fall back to the best muted swatch that is not the primary.
            double bestM = 0.0;
            for (int32_t c = 0; c < swatchCount; ++c) {
                if (c == primary) continue;
                if (meta[c].mutedScore > bestM) {
                    bestM = meta[c].mutedScore;
                    secondary = c;
                }
            }
        }
    }

    // Vibrant / muted report colors.
    int32_t vibrant = primary;
    {
        double best = 0.0;
        for (int32_t c = 0; c < swatchCount; ++c) {
            if (meta[c].hsl.s >= 0.35f && meta[c].hsl.l >= 0.25f &&
                meta[c].hsl.l <= 0.72f && meta[c].vibrantScore > best) {
                best = meta[c].vibrantScore;
                vibrant = c;
            }
        }
    }
    int32_t muted = dominant;
    {
        double best = 0.0;
        for (int32_t c = 0; c < swatchCount; ++c) {
            if (meta[c].mutedScore > best) {
                best = meta[c].mutedScore;
                muted = c;
            }
        }
    }

    const int pr = static_cast<int>(swatchR_[primary] + 0.5f);
    const int pg = static_cast<int>(swatchG_[primary] + 0.5f);
    const int pb = static_cast<int>(swatchB_[primary] + 0.5f);
    const uint32_t primaryRgb = packArgb(pr, pg, pb) & 0xFFFFFFu;

    // Text foreground: WCAG pick between black and white.
    const float cWhite = contrastRatio(primaryRgb, 0xFFFFFFu);
    const float cBlack = contrastRatio(primaryRgb, 0x000000u);
    const bool useWhite = cWhite >= cBlack;
    const float contrast = useWhite ? cWhite : cBlack;

    // Ambient glow: darkened, slightly desaturated primary.
    Hsl pHsl = rgbToHsl(pr, pg, pb);
    float glowS = pHsl.s * 0.85f;
    float glowL = pHsl.l * 0.32f;
    if (pHsl.l <= 0.04f) {
        glowL = 0.05f;  // near-black art still needs a visible glow base
    }
    glowL = clampf(glowL, 0.03f, 0.30f);
    int gr, gg, gb;
    hslToRgb(pHsl.h, glowS, glowL, &gr, &gg, &gb);
    out->ambientGlowArgb = packArgb(gr, gg, gb);

    out->primaryArgb = packArgb(pr, pg, pb);
    if (secondary >= 0) {
        out->secondaryArgb = packArgb(
            static_cast<int>(swatchR_[secondary] + 0.5f),
            static_cast<int>(swatchG_[secondary] + 0.5f),
            static_cast<int>(swatchB_[secondary] + 0.5f));
    } else {
        // Monochrome art: derive a darker companion from the primary.
        hslToRgb(pHsl.h, pHsl.s, clampf(pHsl.l * 0.55f, 0.0f, 1.0f), &gr, &gg,
                 &gb);
        out->secondaryArgb = packArgb(gr, gg, gb);
    }
    out->textForegroundArgb = useWhite ? 0xFFFFFFFFu : 0xFF000000u;
    out->foregroundContrastRatio = contrast;
    out->dominantPopulation = static_cast<uint32_t>(
        swatchPop_[primary] > 0xFFFFFFFFull ? 0xFFFFFFFFull
                                            : swatchPop_[primary]);
    out->swatchCount = static_cast<uint32_t>(swatchCount);
    out->vibrantArgb = packArgb(static_cast<int>(swatchR_[vibrant] + 0.5f),
                                static_cast<int>(swatchG_[vibrant] + 0.5f),
                                static_cast<int>(swatchB_[vibrant] + 0.5f));
    out->mutedArgb = packArgb(static_cast<int>(swatchR_[muted] + 0.5f),
                              static_cast<int>(swatchG_[muted] + 0.5f),
                              static_cast<int>(swatchB_[muted] + 0.5f));
}

PaletteStatus BitmapPaletteExtractor::extract(const uint8_t* pixels,
                                              size_t byteLen, int32_t width,
                                              int32_t height, PixelFormat fmt,
                                              PaletteResult* outResult) {
    if (outResult == nullptr) return PaletteStatus::kInvalidArgument;
    if (fmt != PixelFormat::kRgba8888 && fmt != PixelFormat::kArgb8888 &&
        fmt != PixelFormat::kRgb565) {
        return PaletteStatus::kInvalidArgument;
    }
    if (width <= 0 || height <= 0 || width > kMaxDimension ||
        height > kMaxDimension) {
        return PaletteStatus::kBadDimensions;
    }
    if (static_cast<int64_t>(width) * static_cast<int64_t>(height) >
        static_cast<int64_t>(kMaxPixels)) {
        return PaletteStatus::kTooManyPixels;
    }
    const size_t pixelCount =
        static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t needed = pixelCount * static_cast<size_t>(bytesPerPixel(fmt));
    if (pixels == nullptr || byteLen < needed) {
        return pixels == nullptr ? PaletteStatus::kInvalidArgument
                                 : PaletteStatus::kBadBufferSize;
    }

    clearScratch();
    switch (fmt) {
    case PixelFormat::kRgba8888:
        ingestRgba(pixels, pixelCount);
        break;
    case PixelFormat::kArgb8888:
        ingestArgb(pixels, pixelCount);
        break;
    case PixelFormat::kRgb565:
        ingestRgb565(pixels, pixelCount);
        break;
    }

    const int32_t populated = compactBins();
    if (populated == 0) {
        return PaletteStatus::kInvalidArgument;  // zero pixels (w*h > 0 guard
                                                 // makes this unreachable)
    }

    // Median cut seeds the centroids from the box means.
    const int32_t boxes = medianCut(populated);
    for (int32_t b = 0; b < boxes; ++b) {
        uint64_t pop = 0;
        uint64_t rSum = 0, gSum = 0, bSum = 0;
        for (int32_t i = boxBegin_[b]; i < boxEnd_[b]; ++i) {
            const uint32_t bin = order_[static_cast<size_t>(i)];
            pop += binPop_[bin];
            rSum += binRSum_[bin];
            gSum += binGSum_[bin];
            bSum += binBSum_[bin];
        }
        if (pop > 0) {
            const double inv = 1.0 / static_cast<double>(pop);
            swatchR_[b] = static_cast<float>(rSum * inv);
            swatchG_[b] = static_cast<float>(gSum * inv);
            swatchB_[b] = static_cast<float>(bSum * inv);
            swatchPop_[b] = pop;
        }
    }

    kmeansRefine(populated, boxes);
    scoreSwatches(boxes, outResult);
    return PaletteStatus::kOk;
}

}  // namespace streamify::palette
