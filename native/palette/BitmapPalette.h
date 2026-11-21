#ifndef STREAMIFY_BITMAP_PALETTE_H
#define STREAMIFY_BITMAP_PALETTE_H
// ============================================================================
//  BitmapPalette.h — high-speed dominant-color extractor for playlist covers
//  and album art (Phase 3, BEHIND.md #57 "custom covers (reuse artwork
//  pipeline + Palette)")
// ============================================================================
//
//  Pipeline (single pass + fixed-budget refinement, zero heap per call):
//    1. SIMD ingest: RGBA8888 / ARGB8888 pixels are histogrammed directly
//       into a 4-bit-per-channel RGB space (4096 bins) with population and
//       per-channel weighted sums — NEON 16-pixel batches on ARM, SSE2
//       4-pixel batches on x86, bit-exact scalar fallback everywhere.
//       RGB565 covers take the scalar path (bit-replication expansion).
//    2. Modified median cut: iterative box splitting on the
//       population-weighted max-variance axis, split at the weighted
//       median, up to 24 boxes. Counting-sort partition on 4-bit values —
//       deterministic by construction.
//    3. K-means subset refinement: bounded Lloyd iterations (<= 6) over
//       the populated bins, seeded from the median-cut centroids; empty
//       clusters reseed at the farthest bin (deterministic).
//    4. Role scoring: vibrant/muted targets with 3-pass relaxation,
//       WCAG relative-luminance text-contrast pick (black/white), and a
//       darkened desaturated ambient-glow derivative for the AGSL shader.
//
//  Output: primary, secondary, text-contrast foreground (black or white,
//  >= 4.5:1 when the palette allows it), and ambient glow ARGB colors —
//  the exact quartet the Compose UI and the AGSL ambient-glow shader need.
//
//  Determinism: same pixels -> same palette, always (fixed iteration
//  budgets, deterministic tie-breaks, no hash ordering anywhere).
//
//  Zero allocation: all scratch is fixed-size member state. One extractor
//  per thread; on Android keep it long-lived (~64 KB of state).
// ============================================================================

#include <cstddef>
#include <cstdint>

namespace streamify::palette {

enum class PixelFormat : int32_t {
    kRgba8888 = 0,  // bytes: R G B A (Android Bitmap ARGB_8888 via
                    // copyPixelsToBuffer / getPixels int array)
    kArgb8888 = 1,  // bytes: A R G B
    kRgb565 = 2,    // u16 LE: rrrrrggg gggbbbbb
};

enum class PaletteStatus : int32_t {
    kOk = 0,
    kInvalidArgument = 1,  // null result pointer / unknown format / null pixels
    kBadDimensions = 2,    // width or height <= 0, or > 32768
    kBadBufferSize = 3,    // byteLen < width * height * bytesPerPixel
    kTooManyPixels = 4,    // width * height > 64M
};

// The 4-color contract for Compose (+ introspection for tests/UI).
struct PaletteResult {
    uint32_t primaryArgb = 0xFF000000;        // dominant vibrant color
    uint32_t secondaryArgb = 0xFF000000;      // distinct companion color
    uint32_t textForegroundArgb = 0xFFFFFFFF; // WCAG black/white pick
    uint32_t ambientGlowArgb = 0xFF000000;    // dark glow for AGSL background
    float foregroundContrastRatio = 1.0f;     // vs primary (WCAG)
    uint32_t dominantPopulation = 0;          // pixels in primary's cluster
    uint32_t swatchCount = 1;                 // final clusters (1..24)
    uint32_t vibrantArgb = 0xFF000000;        // best vibrant candidate
    uint32_t mutedArgb = 0xFF000000;          // best muted candidate
};

class BitmapPaletteExtractor {
public:
    BitmapPaletteExtractor() = default;

    // Non-copyable (64 KB of scratch); default construct per thread.
    BitmapPaletteExtractor(const BitmapPaletteExtractor&) = delete;
    BitmapPaletteExtractor& operator=(const BitmapPaletteExtractor&) = delete;

    PaletteStatus extract(const uint8_t* pixels, size_t byteLen,
                          int32_t width, int32_t height, PixelFormat fmt,
                          PaletteResult* outResult);

    static constexpr int32_t kMaxSwatches = 24;
    static constexpr int32_t kHistogramBins = 4096;  // 4 bits per channel
    static constexpr int32_t kMaxPixels = 64 * 1024 * 1024;
    static constexpr int32_t kMaxDimension = 32768;
    static constexpr int32_t kKmeansIterations = 6;

    // Bytes per pixel for a format (0 for unknown formats).
    static int32_t bytesPerPixel(PixelFormat fmt);

private:
    void clearScratch();
    void ingestRgba(const uint8_t* pixels, size_t pixelCount);
    void ingestArgb(const uint8_t* pixels, size_t pixelCount);
    void ingestRgb565(const uint8_t* pixels, size_t pixelCount);
    int32_t compactBins();
    int32_t medianCut(int32_t populated);
    void kmeansRefine(int32_t populated, int32_t swatchCount);
    void scoreSwatches(int32_t swatchCount, PaletteResult* out) const;

    // --- fixed scratch (member state, ~64 KB) ---
    uint32_t binPop_[kHistogramBins] = {};
    uint64_t binRSum_[kHistogramBins] = {};
    uint64_t binGSum_[kHistogramBins] = {};
    uint64_t binBSum_[kHistogramBins] = {};

    uint16_t order_[kHistogramBins] = {};   // populated bin indices
    int32_t boxBegin_[kMaxSwatches + 1] = {};
    int32_t boxEnd_[kMaxSwatches + 1] = {};
    uint64_t boxPop_[kMaxSwatches + 1] = {};
    // Unsplittable-box retirement flag: guarantees median-cut termination
    // even on inputs the split guards classify as degenerate (each loop
    // pass either splits a box or retires one — never retries state).
    bool boxDead_[kMaxSwatches + 1] = {};

    float swatchR_[kMaxSwatches] = {};
    float swatchG_[kMaxSwatches] = {};
    float swatchB_[kMaxSwatches] = {};
    uint64_t swatchPop_[kMaxSwatches] = {};
    uint8_t assign_[kHistogramBins] = {};
};

// WCAG 2.x relative luminance of an 8-bit sRGB color.
float relativeLuminance(uint32_t rgb);
// WCAG contrast ratio between two 8-bit sRGB colors (>= 1.0).
float contrastRatio(uint32_t rgbA, uint32_t rgbB);

}  // namespace streamify::palette

#endif  // STREAMIFY_BITMAP_PALETTE_H
