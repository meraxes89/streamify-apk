#ifndef STREAMIFY_PALETTE_EXTRACTOR_SIMD_H
#define STREAMIFY_PALETTE_EXTRACTOR_SIMD_H
// ============================================================================
//  palette_extractor_simd.h — Phase-5 high-speed SIMD palette clustering &
//  luminance contrast engine (native/include/palette_extractor_simd.h)
// ============================================================================
//
//  Deliverable 1 of the Phase-5 directive: an ultra-fast color quantizer
//  (median-cut + bounded k-means, all ingest stages vectorized with ARM
//  NEON / x86 SSE2 intrinsics) that derives four UI-ready color roles from
//  any album-cover bitmap in < 1 ms (sub-frame budget at 120 Hz), plus the
//  pure-native WCAG 2.1 AA contrast math that guarantees the dynamic text
//  surface stays readable over the artwork.
//
//  PIPELINE (deterministic, zero heap allocation after construction)
//  ------------------------------------------------------------------
//    raw RGBA8888 bytes (DirectByteBuffer, row stride in bytes)
//      -> deterministic strided subsampling to <= 65536 samples
//      -> SIMD histogram: 5-bit/channel RGB555 space = 32768 bins with
//         exact per-bin population AND exact per-bin channel sums
//         (NEON/SSE2 index math: idx = ((R&0xF8)<<7)|((G&0xF8)<<2)|(B>>3))
//      -> population cap: most populous bins retained via a two-pass
//         bucket threshold (boundary bucket filled in ascending bin index;
//         deterministic, allocation-free — no heap or partial sort)
//      -> median cut: up to 8 boxes with cached per-box stats (population
//         + channel extents), pop-weighted splits along the widest 5-bit
//         channel axis at the weighted median (counting sort over the 32
//         possible axis values — no allocation, no unstable ordering)
//      -> k-means (Lloyd, <= 4 iterations, 8 clusters) seeded from the box
//         centroids, exact 8-bit weighted means, deterministic reseed
//      -> HSV role scoring:
//           Dominant Vibrant .......... population x saturation^2 x value
//           Dark Muted (Background) ... muted + dark candidate, V/S clamped
//                                       to <= 0.50 / <= 0.40 by construction
//           Light Vibrant (Accents) ... vibrant + light candidate, V forced
//                                       to >= 0.80 by construction (S forced
//                                       up only when the source hue exists)
//           Contrast Text Surface ..... white/black pole by best WCAG ratio
//                                       against BOTH the dominant and the
//                                       background roles, then a bounded
//                                       16-step tint ladder toward the
//                                       palette (each rung re-verified)
//
//  DETERMINISM CONTRACT
//  --------------------
//  Same pixels -> same four colors, always: fixed iteration budgets,
//  explicit tie-breaks (R > G > B axis, lowest-index-wins), no hashing, no
//  floating-point equality decisions on the role path (every threshold has
//  an epsilon margin). The SIMD and scalar ingest paths produce BIT-IDENTICAL
//  histograms (cross-validated by the unit suite on every architecture).
//
//  MEMORY FOOTPRINT
//  ----------------
//  One SimdPaletteExtractor owns ~0.56 MB of aligned scratch (4 x 128 KB
//  histogram planes + 56 KB retained-bin array + 8 KB assignment map),
//  assignment map), allocated once in the constructor via posix_memalign
//  (NeonCompat mandate) and reused for every call. Instances are NOT
//  thread-safe: keep one long-lived extractor per thread (the JNI bridge
//  keeps a thread_local one). The stateless WCAG helpers below are pure
//  functions and safe from any thread.
//
//  WCAG 2.1 AA MATH (relative luminance & contrast)
//  ------------------------------------------------
//  Relative luminance per WCAG 2.1 (definition in
//  https://www.w3.org/TR/WCAG21/#dfn-relative-luminance):
//      c_lin = (c/255 <= 0.04045) ? (c/255)/12.92
//                                  : ((c/255 + 0.055)/1.055)^2.4
//      L = 0.2126*R_lin + 0.7152*G_lin + 0.0722*B_lin
//  Contrast ratio = (L_lighter + 0.05) / (L_darker + 0.05), in [1, 21].
//
//  The directive's headline formula (L = 0.2126R + 0.7152G + 0.0722B) is the
//  luminance COMBINATION step above; the channel linearization is what makes
//  it WCAG-correct and is implemented in full.
//
//  TEXT-SURFACE GUARANTEE (and its proof)
//  --------------------------------------
//  ContrastTextSurface(dominant) >= 4.5:1 for EVERY possible input color:
//  the chosen pole P in {black (L=0), white (L=1)} maximizes
//  (L_max(P, D) + 0.05)/(L_min(P, D) + 0.05); since one pole pushes the
//  lighter side and the other the darker side,
//      best_ratio >= sqrt( (1.05/(L+0.05)) * ((L+0.05)/0.05) ) = sqrt(21)
//      sqrt(21) ~= 4.5826 > 4.5                                           QED
//  so the 4.5:1 floor holds even before the tint ladder runs (the ladder
//  only accepts blends that re-verify >= 4.5:1 against the dominant role).
//
//  INPUT CONTRACT (mirrored defensively by the JNI bridge)
//  -------------------------------------------------------
//  * Pixel format: RGBA8888, row-major, 4 bytes/pixel, alpha IGNORED for
//    clustering (Bitmap.copyPixelsToBuffer of an ARGB_8888 bitmap yields
//    exactly this byte order on every Android ABI).
//  * `stride_bytes` is the row stride IN BYTES (>= width * 4; Bitmap
//    rowStride is not guaranteed to be tight — padded rows are the norm).
//  * `byte_len` is the readable length of the buffer in bytes and must be
//    >= (height - 1) * stride_bytes + width * 4 (checked in uint64 math so
//    hostile dimensions cannot overflow before the bound is applied).
// ============================================================================

#include <cstddef>
#include <cstdint>

namespace streamify {
namespace palette_simd {

// ---- validation bounds -------------------------------------------------------
inline constexpr int32_t kPaletteMaxDim = 16384;        // width / height cap
inline constexpr size_t kPaletteMaxSamples = 1u << 16;  // 65536-sample budget
inline constexpr size_t kHistogramBins = 1u << 15;      // RGB555: 32768 bins
inline constexpr size_t kMaxRetainedBins = 2048;        // population cap
inline constexpr uint32_t kMedianCutBoxes = 8;
inline constexpr uint32_t kKmeansClusters = 8;
inline constexpr uint32_t kKmeansMaxIterations = 4;

// WCAG 2.1 AA contrast for normal-size text / UI components.
inline constexpr float kWcagAaTextRatio = 4.5f;

// ---- error codes (shared verbatim by the JNI bridge return values) ----------
enum class PaletteError : int32_t {
    kOk = 0,
    kNullArgument = -1,  // null pixels / null out roles
    kBadDimensions = -2, // width or height <= 0 or > kPaletteMaxDim
    kBadStride = -3,     // stride < width * 4 (or not 4-aligned)
    kBadBufferSize = -4, // byte_len shorter than the last row's tail
    kOutOfMemory = -5,   // extractor scratch allocation failed (valid()==false)
};

// ---- the four-role contract (ARGB, alpha always 0xFF) ------------------------
struct PaletteRoles {
    uint32_t dominant_vibrant = 0xFF000000;  // dominant color of the artwork
    uint32_t dark_muted = 0xFF000000;        // background surface (dark, muted)
    uint32_t light_vibrant = 0xFF000000;     // accent color (light, vibrant)
    uint32_t text_surface = 0xFFFFFFFF;      // text/button overlay, >= 4.5:1
                                             // vs dominant_vibrant (guaranteed)
};

// ---- WCAG 2.1 pure math (stateless, thread-safe, host-testable) --------------

// Relative luminance of an 0xAARRGGBB color in [0, 1] (alpha ignored).
float RelativeLuminance(uint32_t argb);

// WCAG 2.1 contrast ratio of two 0xAARRGGBB colors, in [1, 21].
// contrast(a, a) == 1; contrast(a, b) == contrast(b, a) (both asserted in the
// unit suite and property-fuzzed).
float ContrastRatio(uint32_t argb_a, uint32_t argb_b);

// True iff the pair meets `min_ratio` (defensive: non-finite or negative
// thresholds never pass; a threshold <= 1.0 always passes).
bool MeetsContrast(uint32_t argb_a, uint32_t argb_b, float min_ratio);

// ---- the SIMD palette extractor ----------------------------------------------
//
// One instance per thread (see MEMORY FOOTPRINT above). All Extract() paths
// are zero-allocation: the constructor pre-allocates every scratch buffer.
class SimdPaletteExtractor {
public:
    SimdPaletteExtractor();
    ~SimdPaletteExtractor();

    SimdPaletteExtractor(const SimdPaletteExtractor&) = delete;
    SimdPaletteExtractor& operator=(const SimdPaletteExtractor&) = delete;

    // False only when the aligned scratch allocation failed (OOM at startup).
    bool valid() const { return hist_pop_ != nullptr; }

    // Full pipeline (ingest -> median cut -> k-means -> roles). Returns
    // kOk and fills `out` on success; on any error `out` is untouched.
    PaletteError Extract(const uint8_t* rgba, size_t byte_len, int32_t width,
                         int32_t height, int32_t stride_bytes,
                         PaletteRoles* out);

    // ---- test introspection (bit-exact cross-validation) --------------------
    // Scalar ingest of a FULL image (no subsampling): populates the histogram
    // state. Bounds-checked exactly like Extract.
    PaletteError IngestScalar(const uint8_t* rgba, size_t byte_len,
                              int32_t width, int32_t height,
                              int32_t stride_bytes);
    // SIMD ingest (NEON / SSE2 depending on the build; falls back to the
    // scalar loop on other architectures). MUST leave a histogram that is
    // byte-identical to IngestScalar's on the same input.
    PaletteError IngestSimd(const uint8_t* rgba, size_t byte_len,
                            int32_t width, int32_t height,
                            int32_t stride_bytes);

    // Direct view of the live histogram (for the cross-validation memcmp).
    // Layout: kHistogramBins entries; pop in [0, kPaletteMaxSamples].
    const uint32_t* histogram_populations() const { return hist_pop_; }
    const uint32_t* histogram_sum_r() const { return hist_sum_r_; }
    const uint32_t* histogram_sum_g() const { return hist_sum_g_; }
    const uint32_t* histogram_sum_b() const { return hist_sum_b_; }

    // Number of samples the LAST Extract() call ingested (subsampled count).
    size_t last_sample_count() const { return last_sample_count_; }

private:
    // Shared argument validation for the ingest entry points.
    static PaletteError ValidateIngest(const uint8_t* rgba, size_t byte_len,
                                       int32_t width, int32_t height,
                                       int32_t stride_bytes,
                                       uint64_t* needed_bytes);

    void ClearHistogram();
    void IngestRowScalar(const uint8_t* row, int32_t width);
    void IngestRowSimd(const uint8_t* row, int32_t width);

    // Row-strided (subsampled) ingest: full-width SIMD rows every Nth row.
    void IngestStridedRows(const uint8_t* rgba, int32_t width,
                           int32_t height, int32_t stride_bytes,
                           uint32_t row_step);

    // Stages 2-4: bucket retention -> canonical sort -> median cut ->
    // k-means. Fills clusters_[..] / cluster_count_. Returns false when no
    // populated bins exist (unreachable for validated inputs).
    bool Quantize();

    // Stage 5: role scoring from the k-means clusters.
    void ScoreRoles(PaletteRoles* out) const;

    // ---- stage state ----------------------------------------------------------
    // Parallel populated-bin entry (one AOS record per retained bin).
    struct BinEntry {
        uint16_t idx;               // RGB555 bin index
        uint32_t pop;               // population
        uint32_t sum_r, sum_g, sum_b; // exact channel sums
        uint8_t mean_r, mean_g, mean_b; // exact rounded means (k-means metric)
    };
    struct BoxInfo {
        uint32_t begin;
        uint32_t end;
        uint64_t pop;
        int rmin, rmax, gmin, gmax, bmin, bmax;  // cached 5-bit extents
    };
    struct Cluster {
        uint8_t r = 0, g = 0, b = 0;
        uint64_t population = 0;
    };

    uint8_t* scratch_ = nullptr;      // single aligned allocation
    size_t total_bytes_ = 0;
    uint32_t* hist_pop_ = nullptr;    // [kHistogramBins] population per bin
    uint32_t* hist_sum_r_ = nullptr;  // [kHistogramBins] exact R sum per bin
    uint32_t* hist_sum_g_ = nullptr;  // [kHistogramBins] exact G sum per bin
    uint32_t* hist_sum_b_ = nullptr;  // [kHistogramBins] exact B sum per bin
    BinEntry* bins_ = nullptr;        // [kMaxRetainedBins] retained bins
    uint32_t* assign_ = nullptr;      // [kMaxRetainedBins] bin -> cluster

    BoxInfo boxinfo_[kMedianCutBoxes];
    uint32_t box_count_ = 0;
    Cluster clusters_[kKmeansClusters];
    uint32_t cluster_count_ = 0;
    size_t last_sample_count_ = 0;
};

// ---- free-function convenience (uses a thread_local extractor) ---------------
// Thread-safe by construction (one extractor per thread); first call on a
// thread allocates its ~0.75 MB scratch once, every later call is
// allocation-free. Semantics identical to SimdPaletteExtractor::Extract.
PaletteError ExtractPaletteSimd(const uint8_t* rgba, size_t byte_len,
                                int32_t width, int32_t height,
                                int32_t stride_bytes, PaletteRoles* out);

}  // namespace palette_simd
}  // namespace streamify

#endif  // STREAMIFY_PALETTE_EXTRACTOR_SIMD_H
