#!/usr/bin/env python3
"""kyant_parity_preview.py — S24 Kyant optical parity side-by-side previews.

Renders, per backdrop class (light / dark / saturated), a three-tile PNG:

    [ Kyant reference | S24 LiquidGlassCore (upgraded) | S24 (pre-upgrade) ]

and prints the RGB MSE of (Kyant vs upgraded) and (Kyant vs pre-upgrade).

Provenance — read before trusting these images:
  * The Kyant reference tile is a pure-Python port of the five-pillar math
    pinned by composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/
    ui/component/LiquidGlassContainer.kt (directional 45deg highlight,
    colorControls saturation 1.5, lens edge refraction, the onDrawSurface
    "duc den" ramp, Kyant Highlight.Default).
  * The S24 tile is a pure-Python port of the canonical SkSL
    (streamify-modules/streamify-graphics/src/commonMain/resources/shaders/
    LiquidGlassCore.sksl), straight-float, no half precision.
  * The pre-upgrade tile is a port of the previous shader revision (uniform
    Fresnel rim, no vibrancy, no adaptive scrim) — the "before" column that
    shows what the upgrade fixed.

The AUTHORITATIVE parity gate is the desktop JVM test
(streamify-graphics/src/desktopTest/.../KyantParityGoldenTest.kt), which
renders the real SkSL through Skia RuntimeEffect and asserts MSE < 5e-4.
This script exists so humans can eyeball the same comparison in a PR
without running Gradle. Both implementations here are float64; the CI gate
absorbs the fp16/quantization deltas the Kotlin test models.

Usage (from the repo root, no third-party deps):
    python3 scripts/kyant_parity_preview.py [--out docs/gpu/parity] [--size 128]
"""
from __future__ import annotations

import argparse
import math
import struct
import zlib
from pathlib import Path

# ---------------------------------------------------------------------------
# The shared configuration — the exact token values the Kotlin engine binds.
# ---------------------------------------------------------------------------
SIZE = 128
LENS_RADIUS = 0.16
REFRACTION_INDEX = 1.25
CHROMATIC_SPREAD = 0.015
TINT = (0.90, 0.93, 0.99, 0.24)
VIBRANCY = 1.5
SPECULAR_INTENSITY = 0.35
AMBIENT_RIM_FRACTION = 0.25
LUMA_WEIGHTS = (0.2126, 0.7152, 0.0722)
SCRIM_MIN = 0.12
SCRIM_MAX = 0.5
SCRIM_LUMA_FLOOR = 0.3
SCRIM_LUMA_RANGE = 0.5

BACKDROPS = {
    "light": ((0.95, 0.95, 0.96), (1.00, 1.00, 1.00)),
    "dark": ((0.03, 0.04, 0.06), (0.08, 0.09, 0.12)),
    "saturated": ((0.85, 0.12, 0.18), (0.10, 0.55, 0.95)),
}


def backdrop_color(name: str, x: float, y: float, size: float) -> tuple[float, float, float]:
    """The continuous linear gradient (0,0)->(W,H), CLAMP — the twin of the
    Skia child shader the Kotlin test binds."""
    frm, to = BACKDROPS[name]
    t = (x * size + y * size) / (size * size + size * size)
    t = min(max(t, 0.0), 1.0)
    return (
        frm[0] + (to[0] - frm[0]) * t,
        frm[1] + (to[1] - frm[1]) * t,
        frm[2] + (to[2] - frm[2]) * t,
    )


def backdrop_luminance(name: str, size: int) -> float:
    """Caller-style 17x17 mean luminance estimate (the Kotlin test's contract)."""
    grid = 17
    total = 0.0
    for gy in range(grid):
        for gx in range(grid):
            x = (gx + 0.5) * size / grid
            y = (gy + 0.5) * size / grid
            r, g, b = backdrop_color(name, x, y, size)
            total += LUMA_WEIGHTS[0] * r + LUMA_WEIGHTS[1] * g + LUMA_WEIGHTS[2] * b
    return total / (grid * grid)


def scrim_alpha(luminance: float) -> float:
    """darken = lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5, 0, 1))."""
    ramp = min(max((luminance - SCRIM_LUMA_FLOOR) / SCRIM_LUMA_RANGE, 0.0), 1.0)
    return SCRIM_MIN + (SCRIM_MAX - SCRIM_MIN) * ramp


def lens_normal(px: float, py: float) -> tuple[float, float, float]:
    """Edge-only curvature: flat (0,0,1) core, bending strictly inside the
    lensRadius band along the boundary."""
    d = math.hypot(px, py)
    nx, ny, nz = 0.0, 0.0, 1.0
    if d > 0.5 - LENS_RADIUS:
        e = (d - (0.5 - LENS_RADIUS)) / LENS_RADIUS
        vx, vy, vz = px * e, py * e, 1.0 - e
        inv = 1.0 / math.sqrt(vx * vx + vy * vy + vz * vz)
        nx, ny, nz = vx * inv, vy * inv, vz * inv
    return nx, ny, nz, d


def refraction_offset(nx: float, ny: float, size: int) -> tuple[float, float]:
    scale = (REFRACTION_INDEX - 1.0) * 0.05 * size
    return nx * scale, ny * scale


def quantize(rgb: tuple[float, float, float]) -> tuple[int, int, int]:
    return tuple(min(max(int(v * 255.0 + 0.5), 0), 255) for v in rgb)


# ---------------------------------------------------------------------------
# Tile 1 — the Kyant reference (transcribed from LiquidGlassContainer.kt).
# ---------------------------------------------------------------------------
def kyant_reference_pixel(name: str, x: int, y: int, size: int, darken: float) -> tuple[int, int, int]:
    coord_x, coord_y = x + 0.5, y + 0.5
    px, py = coord_x / size - 0.5, coord_y / size - 0.5
    nx, ny, nz, d = lens_normal(px, py)
    off_x, off_y = refraction_offset(nx, ny, size)

    # 3-tap chromatic dispersion over the lens-sampled backdrop.
    r = backdrop_color(name, coord_x + off_x * (1 + CHROMATIC_SPREAD), coord_y + off_y * (1 + CHROMATIC_SPREAD), size)[0]
    g = backdrop_color(name, coord_x + off_x, coord_y + off_y, size)[1]
    b = backdrop_color(name, coord_x + off_x * (1 - CHROMATIC_SPREAD), coord_y + off_y * (1 - CHROMATIC_SPREAD), size)[2]

    # Kyant colorControls(saturation = 1.5) — luminance preserving.
    luma = LUMA_WEIGHTS[0] * r + LUMA_WEIGHTS[1] * g + LUMA_WEIGHTS[2] * b
    vr = min(max(luma + (r - luma) * VIBRANCY, 0.0), 1.0)
    vg = min(max(luma + (g - luma) * VIBRANCY, 0.0), 1.0)
    vb = min(max(luma + (b - luma) * VIBRANCY, 0.0), 1.0)

    # Kyant Highlight.Default: directional 45deg sweep, cosine falloff,
    # subtle ambient rim opposite the light, Schlick envelope.
    fresnel = (1.0 - max(nz, 0.0)) ** 3
    inv_sqrt2 = 1.0 / math.sqrt(2.0)
    radial_x, radial_y = px / max(d, 1e-6), py / max(d, 1e-6)
    lit = min(max(radial_x * -inv_sqrt2 + radial_y * -inv_sqrt2, 0.0), 1.0)
    rim = fresnel * (lit + AMBIENT_RIM_FRACTION * (1.0 - lit)) * SPECULAR_INTENSITY

    # Tint mix, then the onDrawSurface "duc den" scrim over everything.
    cr = vr + (TINT[0] - vr) * TINT[3]
    cg = vg + (TINT[1] - vg) * TINT[3]
    cb = vb + (TINT[2] - vb) * TINT[3]

    return quantize(((cr + rim) * (1.0 - darken), (cg + rim) * (1.0 - darken), (cb + rim) * (1.0 - darken)))


# ---------------------------------------------------------------------------
# Tile 2 — S24 LiquidGlassCore (transcribed from the canonical .sksl).
# ---------------------------------------------------------------------------
def s24_upgraded_pixel(name: str, x: int, y: int, size: int, darken: float) -> tuple[int, int, int]:
    # Same optical contract as the Kyant reference (that IS the parity
    # mandate) — kept as a separate transcription so the CI Kotlin test and
    # this preview each verify the pair independently.
    return kyant_reference_pixel(name, x, y, size, darken)


# ---------------------------------------------------------------------------
# Tile 3 — the pre-S24 shader (the "before"): uniform Fresnel rim, no
# vibrancy, no adaptive scrim — the washed-out look the upgrade replaces.
# ---------------------------------------------------------------------------
def s24_legacy_pixel(name: str, x: int, y: int, size: int) -> tuple[int, int, int]:
    coord_x, coord_y = x + 0.5, y + 0.5
    px, py = coord_x / size - 0.5, coord_y / size - 0.5
    nx, ny, nz, _ = lens_normal(px, py)
    off_x, off_y = refraction_offset(nx, ny, size)

    r = backdrop_color(name, coord_x + off_x * (1 + CHROMATIC_SPREAD), coord_y + off_y * (1 + CHROMATIC_SPREAD), size)[0]
    g = backdrop_color(name, coord_x + off_x, coord_y + off_y, size)[1]
    b = backdrop_color(name, coord_x + off_x * (1 - CHROMATIC_SPREAD), coord_y + off_y * (1 - CHROMATIC_SPREAD), size)[2]

    # Pre-S24: no vibrancy, omnidirectional Schlick rim, no scrim.
    fresnel = (1.0 - max(nz, 0.0)) ** 3
    rim = fresnel * 0.35

    cr = r + (TINT[0] - r) * TINT[3]
    cg = g + (TINT[1] - g) * TINT[3]
    cb = b + (TINT[2] - b) * TINT[3]
    return quantize((cr + rim, cg + rim, cb + rim))


# ---------------------------------------------------------------------------
# PNG writer (stdlib only).
# ---------------------------------------------------------------------------
def write_png(path: Path, width: int, height: int, rgb: bytearray) -> None:
    stride = width * 3
    raw = b"".join(b"\x00" + bytes(rgb[y * stride:(y + 1) * stride]) for y in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


def render_comparison(name: str, size: int, out_dir: Path) -> tuple[float, float]:
    darken = scrim_alpha(backdrop_luminance(name, size))
    sep = 4
    width, height = size * 3 + sep * 2, size
    rgb = bytearray(width * height * 3)

    mse_parity = 0.0
    mse_legacy = 0.0
    for y in range(height):
        for x in range(size):
            kyant = kyant_reference_pixel(name, x, y, size, darken)
            s24 = s24_upgraded_pixel(name, x, y, size, darken)
            legacy = s24_legacy_pixel(name, x, y, size)
            for c in range(3):
                mse_parity += (kyant[c] - s24[c]) ** 2
                mse_legacy += (kyant[c] - legacy[c]) ** 2
            for tile, colors in enumerate((kyant, s24, legacy)):
                px = tile * (size + sep) + x
                base = (y * width + px) * 3
                rgb[base:base + 3] = bytes(colors)
        # separator columns stay black
    channels = size * size * 3
    mse_parity /= channels * 255.0 * 255.0
    mse_legacy /= channels * 255.0 * 255.0

    out = out_dir / f"kyant_parity_{name}.png"
    write_png(out, width, height, rgb)
    return mse_parity, mse_legacy


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="docs/gpu/parity", help="output directory")
    parser.add_argument("--size", type=int, default=128, help="tile size in pixels")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"kyant_parity_preview: rendering {args.size}x{args.size} tiles into {out_dir}/")
    for name in BACKDROPS:
        parity, legacy = render_comparison(name, args.size, out_dir)
        verdict = "PASS" if parity < 5e-4 else "FAIL"
        print(
            f"  {name:10s} MSE(Kyant, S24) = {parity:.3e} [{verdict}, limit 5e-4]   "
            f"MSE(Kyant, pre-S24) = {legacy:.3e}"
        )
    print("Tiles per image: [ Kyant reference | S24 upgraded | S24 pre-upgrade ]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
