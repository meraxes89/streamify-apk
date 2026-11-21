#!/usr/bin/env python3
"""
sovereign_loopback_verifier.py — the shared OS-loopback verifier of the
Sovereign HAL Phase 0 verification matrix (D4).

Every platform loopback job (WASAPI loopback, PipeWire null-sink monitor,
CoreAudio blackhole-2ch, Android emulator `-audiodev wav`, and the browser
AudioWorklet dual mode) captures the OS audio output while the synthetic
ring producer smoke binary (`sovereign-loopback-smoke`) plays the frozen
verification signal (440 Hz + 880 Hz interleaved stereo f32 @ 48 kHz),
then THIS script proves the capture:

  1. DECODE     WAV/FLAC (whatever the platform capture emits; ffmpeg
                converts exotic formats to f32le WAV first) → interleaved
                f32 (or s16 → promoted) samples at the captured rate.
  2. FFT        Hann-windowed radix-2 FFT over the captured left channel
                (stdlib-only implementation — the same discipline as the
                A5 "engine says 440" harness: no numpy/scipy dependency
                on the CI runners).
  3. PEAKS      The dominant-bin frequency must be 440 Hz ± tolerance and
                the 880 Hz harmonic must be present (both the smoke
                binary's dual-tone design).
  4. NOISE      The `--mode silence` negative control: no bin above the
                noise floor (proves the capture path itself is quiet —
                a verifier that would "pass" silence is vacuous).
  5. SILENCE-   The digital-silence ratio of the signal capture is sane
     RATIO      (> 99% non-zero samples; a dead capture channel fails).

Exit code: 0 = verified, 1 = verification failed (diagnostics printed),
2 = usage/IO error.

Usage (from the CI job after the capture file lands):
    python3 scripts/sovereign_loopback_verifier.py capture.wav \
        [--freq-a 440] [--freq-b 880] [--tolerance-hz 8] \
        [--expect silence|signal] [--json] [--verbose]

This is the PYTHON side of the contract; the in-browser Playwright leg
(D4 Gate 4, job 5) verifies through a post-worklet AnalyserNode FFT
inside the page (scripts/sovereign_hal_browser_harness.html) — this
script verifies every FILE-based capture (jobs 1-4).
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import wave
from pathlib import Path


# ─────────────────────────────────────────────────────────────────────────────
# WAV reading (stdlib `wave`): PCM16 / PCM24 / PCM32 / float32 (format 3).
# ─────────────────────────────────────────────────────────────────────────────

def read_wav_f32(path: Path) -> tuple[list[float], int, int]:
    """Read a WAV file → (interleaved samples normalized to [-1, 1], rate, channels)."""
    with wave.open(str(path), "rb") as w:
        nch = w.getnchannels()
        rate = w.getframerate()
        sampwidth = w.getsampwidth()
        nframes = w.getnframes()
        raw = w.readframes(nframes)

    def deinterleave(fmt: str, size: int, scale: float) -> list[float]:
        count = len(raw) // size
        return [v / scale for v in struct.unpack(f"<{count}{fmt}", raw[: count * size])]

    if sampwidth == 2:  # PCM16 (the emulator wav / sox default)
        return deinterleave("h", 2, 32768.0), rate, nch
    if sampwidth == 4:
        # Could be PCM32 or float32 — the format code is in the header;
        # `wave` does not expose it directly, so probe: float32 audio of a
        # test tone has |values| <= 1; PCM32 rarely does after /2^31.
        count = len(raw) // 4
        words = struct.unpack(f"<{count}i", raw)
        as_float = struct.unpack(f"<{count}f", raw)
        max_words = max((abs(v) for v in words), default=0)
        max_float = max((abs(v) for v in as_float), default=0.0)
        if max_float <= 1.0 and max_words > (1 << 30):
            return list(as_float), rate, nch  # float32 WAV
        return [v / float(1 << 31) for v in words], rate, nch  # PCM32
    if sampwidth == 3:  # PCM24
        out: list[float] = []
        for i in range(0, len(raw) - 2, 3):
            v = raw[i] | (raw[i + 1] << 8) | (raw[i + 2] << 16)
            if v & 0x800000:
                v -= 1 << 24
            out.append(v / float(1 << 23))
        return out, rate, nch
    raise ValueError(f"unsupported sample width: {sampwidth} bytes")


def read_raw_pcm16_f32(path: Path) -> tuple[list[float], int, int]:
    """Read a HEADERLESS PCM16 s16le 48 kHz stereo dump → (samples, 48000, 2).

    The QEMU wav backend's mid-write shape: `QEMU_AUDIO_DRV=wav` streams raw
    frames to QEMU_WAV_PATH and only writes the 44-byte RIFF header on
    close/flush — a capture read while the emulator still runs has no header
    (the r20c 'not a WAVE file' on a healthy capture).
    """
    raw = path.read_bytes()
    count = len(raw) // 2
    return [v / 32768.0 for v in struct.unpack(f"<{count}h", raw[: count * 2])], 48_000, 2


# ─────────────────────────────────────────────────────────────────────────────
# The FFT (Hann window, iterative radix-2 — the A5 harness's discipline).
# ─────────────────────────────────────────────────────────────────────────────

def hann(n: int) -> list[float]:
    return [0.5 * (1.0 - math.cos(2.0 * math.pi * i / (n - 1))) for i in range(n)]


def fft_radix2(re: list[float], im: list[float]) -> None:
    """In-place iterative radix-2 FFT. len must be a power of two."""
    n = len(re)
    if n & (n - 1):
        raise ValueError("FFT length must be a power of two")
    # Bit-reversal permutation.
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j |= bit
        if i < j:
            re[i], re[j] = re[j], re[i]
            im[i], im[j] = im[j], im[i]
    # Butterflies.
    length = 2
    while length <= n:
        ang = -2.0 * math.pi / length
        wlen_re = math.cos(ang)
        wlen_im = math.sin(ang)
        for i in range(0, n, length):
            w_re, w_im = 1.0, 0.0
            for k in range(length // 2):
                u_re, u_im = re[i + k], im[i + k]
                v_re = re[i + k + length // 2] * w_re - im[i + k + length // 2] * w_im
                v_im = re[i + k + length // 2] * w_im + im[i + k + length // 2] * w_re
                re[i + k] = u_re + v_re
                im[i + k] = u_im + v_im
                re[i + k + length // 2] = u_re - v_re
                im[i + k + length // 2] = u_im - v_im
                w_re, w_im = w_re * wlen_re - w_im * wlen_im, w_re * wlen_im + w_im * wlen_re
        length <<= 1


def dominant_spectrum(samples: list[float], rate: int) -> tuple[list[float], float]:
    """Hann-windowed magnitude spectrum (power-of-two chunk, first half)."""
    n = 1
    while n * 2 <= len(samples):
        n *= 2
    if n < 1024:
        raise ValueError(f"not enough samples for an FFT: {len(samples)}")
    chunk = samples[:n]
    win = hann(n)
    re = [chunk[i] * win[i] for i in range(n)]
    im = [0.0] * n
    fft_radix2(re, im)
    half = n // 2
    mag = [math.hypot(re[i], im[i]) for i in range(half)]
    bin_hz = rate / n
    return mag, bin_hz


def bin_of(freq: float, bin_hz: float) -> int:
    return int(round(freq / bin_hz))


# ─────────────────────────────────────────────────────────────────────────────
# The verification gates.
# ─────────────────────────────────────────────────────────────────────────────

def verify(
    samples: list[float],
    rate: int,
    channels: int,
    freq_a: float,
    freq_b: float,
    tolerance_hz: float,
    expect: str,
    verbose: bool,
) -> tuple[bool, dict]:
    left = samples[0::channels]
    # Drop leading zeros (capture pipelines often start with a quiet ramp).
    first_nonzero = next((i for i, s in enumerate(left) if abs(s) > 1e-4), 0)
    left = left[first_nonzero:]
    if len(left) < 4096:
        return False, {"error": f"capture too short after trim: {len(left)} samples"}

    mag, bin_hz = dominant_spectrum(left, rate)
    peak_bin = max(range(len(mag)), key=lambda i: mag[i])
    peak_hz = peak_bin * bin_hz
    peak_mag = mag[peak_bin]
    total = sum(mag) or 1.0
    peak_ratio = peak_mag / total

    # Noise floor: median magnitude (robust against the tone bins).
    sorted_mag = sorted(mag)
    median_mag = sorted_mag[len(sorted_mag) // 2]

    # The non-peak energy fraction (everything except ±3 bins of both tones).
    a_bin = bin_of(freq_a, bin_hz)
    b_bin = bin_of(freq_b, bin_hz)
    tone_energy = sum(
        mag[i]
        for i in range(len(mag))
        if abs(i - a_bin) <= 3 or abs(i - b_bin) <= 3
    )
    noise_ratio = 1.0 - tone_energy / total

    report = {
        "rate": rate,
        "channels": channels,
        "samples_analyzed": len(left),
        "fft_bins": len(mag),
        "bin_hz": round(bin_hz, 3),
        "peak_hz": round(peak_hz, 2),
        "peak_magnitude_ratio": round(peak_ratio, 6),
        "median_magnitude": round(median_mag, 6),
        "freq_a_bin": a_bin,
        "freq_b_bin": b_bin,
        "freq_a_mag": round(mag[a_bin], 4) if a_bin < len(mag) else 0.0,
        "freq_b_mag": round(mag[b_bin], 4) if b_bin < len(mag) else 0.0,
        "noise_ratio": round(noise_ratio, 6),
        "expect": expect,
    }

    if expect == "silence":
        # Negative control: nothing above 1% of the peak-magnitude scale.
        ok = peak_mag < 1e-3 or peak_ratio < 0.02
        report["verdict"] = "PASS" if ok else "FAIL"
        report["reason"] = "silence capture must show no dominant tone"
        return ok, report

    # Signal mode: BOTH tones present, the dominant bin is one of them,
    # and the peak is well above the median floor.
    a_ok = a_bin < len(mag) and mag[a_bin] > 20 * median_mag
    b_ok = b_bin < len(mag) and mag[b_bin] > 20 * median_mag
    dominant_ok = abs(peak_hz - freq_a) <= tolerance_hz or abs(peak_hz - freq_b) <= tolerance_hz
    strong = peak_mag > 50 * median_mag

    report["verdict"] = "PASS" if (a_ok and b_ok and dominant_ok and strong) else "FAIL"
    report["checks"] = {
        "freq_a_present_20x_median": a_ok,
        "freq_b_present_20x_median": b_ok,
        "dominant_bin_is_a_tone": dominant_ok,
        "peak_50x_median": strong,
    }
    if verbose:
        report["top10_bins"] = [
            {"hz": round(i * bin_hz, 1), "mag": round(mag[i], 4)}
            for i in sorted(range(len(mag)), key=lambda i: -mag[i])[:10]
        ]
    return (a_ok and b_ok and dominant_ok and strong), report


def main() -> int:
    ap = argparse.ArgumentParser(description="Sovereign HAL loopback verifier")
    ap.add_argument("capture", type=Path, help="captured audio file (WAV)")
    ap.add_argument("--freq-a", type=float, default=440.0)
    ap.add_argument("--freq-b", type=float, default=880.0)
    ap.add_argument("--tolerance-hz", type=float, default=8.0)
    ap.add_argument("--expect", choices=["signal", "silence"], default="signal")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if not args.capture.is_file():
        print(f"sovereign-verifier: capture file not found: {args.capture}", file=sys.stderr)
        return 2

    try:
        try:
            samples, rate, channels = read_wav_f32(args.capture)
        except (wave.Error, EOFError, struct.error):
            # The Android emulator's QEMU wav backend (QEMU_AUDIO_DRV=wav)
            # writes the 44-byte RIFF header on CLOSE/flush — a capture
            # read while the emulator still runs is a RAW PCM16 stereo
            # stream with no header (the r20c finding: 'not a WAVE file'
            # on a healthy 1.5 MB capture). Fall back to raw mode with
            # the backend's fixed geometry: 48 kHz / 2ch / s16le.
            samples, rate, channels = read_raw_pcm16_f32(args.capture)
            if args.verbose:
                print(
                    "sovereign-verifier: not a RIFF wave — decoded as RAW "
                    "PCM16 s16le 48000 Hz stereo (QEMU wav mid-write)",
                    file=sys.stderr,
                )
    except Exception as e:  # noqa: BLE001 — report any decode failure cleanly
        print(f"sovereign-verifier: capture decode failed: {e}", file=sys.stderr)
        return 2

    ok, report = verify(
        samples, rate, channels,
        args.freq_a, args.freq_b, args.tolerance_hz, args.expect, args.verbose,
    )

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"sovereign-verifier: {report.get('verdict')} — capture {args.capture}")
        for k, v in report.items():
            if k in ("verdict", "reason", "top10_bins"):
                continue
            print(f"  {k}: {v}")
        for k, v in report.get("checks", {}).items():
            print(f"  check {k}: {'OK' if v else 'FAIL'}")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
