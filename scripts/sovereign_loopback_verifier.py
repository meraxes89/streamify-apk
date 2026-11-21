#!/usr/bin/env python3
"""
sovereign_loopback_verifier.py — the Tri-Oracle Mathematical Grader of the
Sovereign HAL Zero-Hardware Audio Testing Fleet (Phase 5 / RFC Phase A,
Pillar 2).

Every platform loopback job (WASAPI loopback, PipeWire null-sink monitor,
CoreAudio blackhole-2ch, Android emulator `-audiodev wav`, and the browser
AudioWorklet dual mode) captures the OS audio output while a smoke binary
plays the frozen verification signal, then THIS script grades the capture
with THREE strict mathematical oracles on top of the Phase 0 peak-FFT gates:

  0. LEGACY    Peak-FFT gates (both tones present, dominant bin is a tone,
               silence negative control) — unchanged, still first.
  1. ORACLE 1  Second-Derivative Discontinuity & Glitch Hunter:
               steady-state PCM (first/last 50 ms envelope trimmed) is
               scanned sample-by-sample PER CHANNEL for slope
               discontinuities |x[i+1] - 2·x[i] + x[i-1]|. For the clean
               440+880 Hz dual tone at 48 kHz with peak amplitude A the
               analytical bound is A·(2π·880/48000)² ≈ 0.0133·A… — using
               the per-capture PEAK as A keeps the bound self-calibrating;
               the FAIL threshold is 4× the analytical bound (measured
               clean chains: opus decode 0.68×, AAC 0.65×, synthetic
               0.67×, 16-bit quantized 0.67× — a single dropped sample or
               zero-fill gap lands 10-100× above it).
  2. ORACLE 2  THD+N ≤ -85 dB (synthetic/f32 captures): a 4-term
               Blackman-Harris window (−92 dB sidelobes) over the
               steady-state capture, DC and ±5 FFT bins around BOTH
               fundamentals notched, residual-vs-fundamental RMS ratio in
               dB. Calibration (measured, scripts/p5_calibration.py):
                 * synthetic f32 dual-tone .................. −90.2 dB
                 * synthetic 16-bit quantized ............... −86.6 dB
                 * opus_dual_tone_48k (lossy codec) ......... −49.4 dB
                 * aac_dual_tone_44k1   (lossy codec) ....... −29.3 dB
               The −85.0 dB bar is therefore enforced by default on
               PCM/synthetic captures (the smoke-binary legs) and — via
               --thdn-limit-db — RAISED to the documented codec ceiling on
               legs whose source is a lossy codec (opus legs run with
               --thdn-limit-db -45; the codec's own noise floor is the
               physics, not a HAL defect). 24-bit/f32 capture is requested
               wherever the platform supports it; a 16-bit capture chain
               is AUTO-DETECTED (WAV sampwidth 2) and its quantization
               floor (−86.6 dB measured) is documented in the report —
               still under the −85 bar with 1.6 dB of margin.
  3. ORACLE 3  Least-Squares DAC Presentation-Clock Drift: the captured
               DacPresentationClock series ((wall_ns, dac_frames)) is fit
               by ordinary least squares dac_frames = m·wall_ns + b;
               drift_ppm = (m·1e9 / sample_rate − 1)·1e6 must satisfy
               |drift| ≤ 50 ppm (RFC Phase A; the Phase 1 ±100 ppm gate
               tightens to ±50 ppm here). The series arrives either as a
               two/three-column CSV (--clock-csv wall_ns,dac_frames[,rate])
               or a JSON sidecar (--clock-json, list of
               {monotonic_ns, dac_frame_position, sample_rate}) — the shape
               sovereign_cpal_loopback / sovereign_android_loopback already
               sample. When no clock series is supplied, Oracle 3 is
               REPORTED as "skipped" (never silently green) — CI chaos
               legs that must prove the clock pass --clock-csv explicitly.

Exit code: 0 = verified, 1 = verification failed (diagnostics printed),
2 = usage/IO error.

Usage (from the CI job after the capture file lands):
    python3 scripts/sovereign_loopback_verifier.py capture.wav \
        [--freq-a 440] [--freq-b 880] [--tolerance-hz 8] \
        [--expect silence|signal] [--profile legacy|tri] \
        [--glitch-threshold-mult 4.0] [--thdn-limit-db -85.0] \
        [--drift-limit-ppm 50.0] [--clock-csv clock.csv] \
        [--clock-json clock.json] [--duration-sec 15] \
        [--json] [--verbose]

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

def read_wav_f32(path: Path) -> tuple[list[float], int, int, int]:
    """Read a WAV file → (interleaved samples normalized to [-1, 1], rate,
    channels, sampwidth). sampwidth travels with the samples so Oracle 2
    can document the 16-bit quantization floor when a capture chain forces
    s16.

    Parses the RIFF chunks DIRECTLY instead of stdlib `wave`: sox's
    float32 captures (`-e floating-point -b 32`) carry format tag 3, which
    `wave` rejects outright ('unknown format: 3'), and WAVE_FORMAT_EXTENSIBLE
    (0xFFFE) needs its SubFormat GUID decoded. Chunk sizes are trusted
    only as far as the file's ACTUAL length (the r20f hardening: piped
    writers — ffmpeg to stdout, QEMU's flush — leave placeholder RIFF
    sizes like 0x3FFFFFFF because they cannot seek back to patch them;
    slicing caps every chunk at the real end of file)."""
    raw_all = path.read_bytes()
    if len(raw_all) < 44 or raw_all[:4] != b"RIFF" or raw_all[8:12] != b"WAVE":
        # Not a RIFF wave at all (the QEMU headerless mid-write shape) —
        # raise the same error class the caller's raw-PCM fallback catches.
        raise wave.Error("not a RIFF WAVE file")

    fmt_body: bytes | None = None
    data_body: bytes | None = None
    pos = 12
    while pos + 8 <= len(raw_all):
        cid = raw_all[pos : pos + 4]
        size = struct.unpack("<I", raw_all[pos + 4 : pos + 8])[0]
        body = raw_all[pos + 8 : pos + 8 + size]  # capped at file end (r20f)
        if cid == b"fmt " and fmt_body is None:
            fmt_body = body
        elif cid == b"data" and data_body is None:
            data_body = body
        pos += 8 + size + (size & 1)  # chunks are word-aligned
    if fmt_body is None or data_body is None or len(fmt_body) < 16:
        raise wave.Error("RIFF chunks incomplete (fmt/data missing)")

    tag, nch, rate, _brate, _align, bits = struct.unpack(
        "<HHIIHH", fmt_body[:16]
    )
    # WAVE_FORMAT_EXTENSIBLE: the real format rides in the SubFormat GUID's
    # first 2 bytes (0x0001 PCM, 0x0003 IEEE float).
    if tag == 0xFFFE and len(fmt_body) >= 40:
        tag = struct.unpack("<H", fmt_body[24:26])[0]
    if tag == 3 and bits == 32:
        count = len(data_body) // 4
        return list(struct.unpack(f"<{count}f", data_body[: count * 4])), rate, nch, 4
    if tag == 1:
        if bits == 16:
            count = len(data_body) // 2
            return [
                v / 32768.0
                for v in struct.unpack(f"<{count}h", data_body[: count * 2])
            ], rate, nch, 2
        if bits == 32:
            count = len(data_body) // 4
            return [
                v / float(1 << 31)
                for v in struct.unpack(f"<{count}i", data_body[: count * 4])
            ], rate, nch, 4
        if bits == 24:
            out: list[float] = []
            for i in range(0, len(data_body) - 2, 3):
                v = data_body[i] | (data_body[i + 1] << 8) | (data_body[i + 2] << 16)
                if v & 0x800000:
                    v -= 1 << 24
                out.append(v / float(1 << 23))
            return out, rate, nch, 3
    raise ValueError(f"unsupported WAV format: tag={tag} bits={bits}")


def read_raw_pcm16_f32(path: Path, rate: int) -> tuple[list[float], int, int, int]:
    """Read a HEADERLESS PCM16 s16le stereo dump → (samples, rate, 2, 2).

    The QEMU wav backend's mid-write shape: `QEMU_AUDIO_DRV=wav` streams raw
    frames to QEMU_WAV_PATH and only writes the 44-byte RIFF header on
    close/flush — a capture read while the emulator still runs has no header
    (the r20c 'not a WAVE file' on a healthy capture).

    The stream RATE is genuinely unknown too: the backend records at the
    rate the guest HAL negotiated (44.1 kHz on the emulator's OpenSL legs,
    48 kHz on AAudio legs), so the caller passes the rate to TRY and owns
    the detection (main() tries 48k then 44.1k and keeps whichever
    verifies — the r20d finding: the 440 tone appeared at 478.9 Hz because
    a 44.1 kHz capture was decoded as 48 kHz).
    """
    raw = path.read_bytes()
    count = len(raw) // 2
    return [v / 32768.0 for v in struct.unpack(f"<{count}h", raw[: count * 2])], rate, 2, 2


# ─────────────────────────────────────────────────────────────────────────────
# The FFT (Hann window for the legacy gates; Blackman-Harris for THD+N —
# iterative radix-2, the A5 harness's stdlib-only discipline).
# ─────────────────────────────────────────────────────────────────────────────

def hann(n: int) -> list[float]:
    return [0.5 * (1.0 - math.cos(2.0 * math.pi * i / (n - 1))) for i in range(n)]


def blackman_harris(n: int) -> list[float]:
    """4-term Blackman-Harris window: −92 dB peak sidelobes (the RFC's
    leakage-minimizing choice — Hann's −31 dB first sidelobe would smear
    tone energy into the residual and floor the measurable THD+N)."""
    a0, a1, a2, a3 = 0.35875, 0.48829, 0.14128, 0.01168
    denom = n - 1
    return [
        a0
        - a1 * math.cos(2.0 * math.pi * i / denom)
        + a2 * math.cos(4.0 * math.pi * i / denom)
        - a3 * math.cos(6.0 * math.pi * i / denom)
        for i in range(n)
    ]


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
# ORACLE 1 — Second-Derivative Discontinuity & Glitch Hunter (RFC Phase A).
# ─────────────────────────────────────────────────────────────────────────────

def glitch_hunt(
    channels_data: list[list[float]],
    rate: int,
    freq_b: float,
    threshold_mult: float,
) -> dict:
    """Scan steady-state per-channel PCM for second-difference violations.

    channels_data: per-channel sample lists ALREADY steady-state trimmed
    (the caller cuts the first/last 50 ms envelope). The threshold is
    threshold_mult × the analytical dual-tone second-difference bound
    A·(2π·f_max/rate)², where A is the per-channel peak amplitude and
    f_max the highest verification tone (880 Hz by default). A single
    dropped sample, a buffer-wrap discontinuity or a zero-fill gap puts
    |d2| at 2·A or more — 10-100× above the bound; a clean chain rides at
    ≤ 0.7× (measured: synthetic 0.67×, 16-bit 0.67×, opus 0.68×, aac
    0.65×)."""
    per_channel = []
    total_violations = 0
    f_max = freq_b
    for ci, ch in enumerate(channels_data):
        peak = max((abs(s) for s in ch), default=0.0)
        if peak < 1e-6:
            per_channel.append({
                "channel": ci, "peak": 0.0, "analytical_bound": 0.0,
                "max_d2": 0.0, "ratio": 0.0, "violations": 0, "ok": True,
                "note": "silent channel",
            })
            continue
        analytical = peak * (2.0 * math.pi * f_max / rate) ** 2
        threshold = threshold_mult * analytical
        max_d2 = 0.0
        violations = 0
        first_violation_idx = -1
        for i in range(1, len(ch) - 1):
            d2 = ch[i + 1] - 2.0 * ch[i] + ch[i - 1]
            a = d2 if d2 >= 0 else -d2
            if a > max_d2:
                max_d2 = a
            if a > threshold:
                violations += 1
                if first_violation_idx < 0:
                    first_violation_idx = i
        total_violations += violations
        per_channel.append({
            "channel": ci,
            "peak": round(peak, 6),
            "analytical_bound": round(analytical, 8),
            "threshold": round(threshold, 8),
            "max_d2": round(max_d2, 8),
            "ratio": round(max_d2 / analytical, 3) if analytical > 0 else 0.0,
            "violations": violations,
            "first_violation_sample": first_violation_idx,
            "first_violation_ms": round(first_violation_idx * 1000.0 / rate, 2)
            if first_violation_idx >= 0 else None,
            "ok": violations == 0,
        })
    return {
        "oracle": "glitch-hunter",
        "threshold_mult": threshold_mult,
        "total_violations": total_violations,
        "per_channel": per_channel,
        "ok": total_violations == 0,
    }


# ─────────────────────────────────────────────────────────────────────────────
# ORACLE 2 — THD+N (Blackman-Harris, DC + ±5-bin notch, ≤ limit dB).
# ─────────────────────────────────────────────────────────────────────────────

def thdn_measure(
    mono: list[float],
    rate: int,
    freq_a: float,
    freq_b: float,
    limit_db: float,
    sampwidth: int,
) -> dict:
    """Residual (harmonic + aliasing + noise) vs fundamental energy, dB.

    Calibration floors (measured on this repo's fixtures,
    scripts/p5_calibration.py): synthetic f32 −90.2 dB, synthetic 16-bit
    −86.6 dB, opus decode −49.4 dB, aac decode −29.3 dB. The RFC's
    −85.0 dB bar is the DEFAULT (synthetic/PCM legs); lossy-codec legs
    pass --thdn-limit-db matching their documented codec ceiling."""
    n = 1
    while n * 2 <= len(mono):
        n *= 2
    n = min(n, 1 << 19)  # cap the window at 2^19 (~10.9 s @ 48k)
    if n < 4096:
        return {
            "oracle": "thdn", "ok": False,
            "reason": f"steady-state too short for THD+N: {len(mono)} samples",
        }
    chunk = mono[:n]
    win = blackman_harris(n)
    re = [chunk[i] * win[i] for i in range(n)]
    im = [0.0] * n
    fft_radix2(re, im)
    half = n // 2
    power = [re[i] * re[i] + im[i] * im[i] for i in range(half)]
    bin_hz = rate / n
    a_bin = bin_of(freq_a, bin_hz)
    b_bin = bin_of(freq_b, bin_hz)
    # Notch DC (and its leakage skirt) + ±5 bins around BOTH fundamentals.
    notch: set[int] = {0, 1}
    for b in (a_bin, b_bin):
        for d in range(-5, 6):
            notch.add(b + d)
    fund = sum(power[i] for i in range(half) if i in notch)
    resid = sum(power[i] for i in range(half) if i not in notch)
    thdn_db = 10.0 * math.log10(resid / fund) if fund > 0 else float("inf")
    report = {
        "oracle": "thdn",
        "fft_size": n,
        "bin_hz": round(bin_hz, 3),
        "notch_bins": sorted(i for i in notch if i < half),
        "thdn_db": round(thdn_db, 2) if math.isfinite(thdn_db) else None,
        "limit_db": limit_db,
        "capture_sampwidth": sampwidth,
    }
    if sampwidth == 2:
        report["quantization_floor_note"] = (
            "16-bit capture chain: quantization floor −85.7 dB @ 2 s / "
            "−86.6 dB @ 3 s / −89.0 dB @ 6 s (measured, round-to-nearest "
            "quantizer) — under the −85.0 bar; 24-bit/f32 requested "
            "wherever the platform supports it"
        )
    report["ok"] = math.isfinite(thdn_db) and thdn_db <= limit_db
    return report


# ─────────────────────────────────────────────────────────────────────────────
# ORACLE 3 — Least-Squares DAC Presentation-Clock Drift (|drift| ≤ 50 ppm).
# ─────────────────────────────────────────────────────────────────────────────

def load_clock_series(
    csv_path: Path | None, json_path: Path | None
) -> list[tuple[int, int, int]]:
    """Load the DacPresentationClock series → [(wall_ns, dac_frames, rate)].

    CSV: `wall_ns,dac_frames[,sample_rate]` per line (# comments and blank
    lines skipped). JSON: a list of {monotonic_ns, dac_frame_position,
    sample_rate} objects (the DacPresentationClock field names) or the
    sovereign_cpal_loopback player.json shape {"clock_samples": [...]}."""
    series: list[tuple[int, int, int]] = []
    if csv_path is not None:
        for line in csv_path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split(",")]
            if len(parts) < 2:
                continue
            wall, frames = int(parts[0]), int(parts[1])
            rate = int(parts[2]) if len(parts) > 2 else 48_000
            series.append((wall, frames, rate))
    if json_path is not None:
        data = json.loads(json_path.read_text())
        if isinstance(data, dict):
            # player.json sidecar shape.
            data = data.get("clock_samples", data.get("clock", []))
        for s in data:
            if isinstance(s, dict):
                series.append((
                    int(s["monotonic_ns"]),
                    int(s["dac_frame_position"]),
                    int(s.get("sample_rate", 48_000)),
                ))
            elif isinstance(s, (list, tuple)) and len(s) >= 2:
                series.append((int(s[0]), int(s[1]),
                               int(s[2]) if len(s) > 2 else 48_000))
    # Monotonic order + dedup (the sampling loop can re-read one seq).
    series.sort(key=lambda t: t[0])
    dedup: list[tuple[int, int, int]] = []
    for w, f, r in series:
        if dedup and dedup[-1][1] == f:
            continue
        dedup.append((w, f, r))
    return dedup


def clock_drift(series: list[tuple[int, int, int]], limit_ppm: float) -> dict:
    """OLS fit dac_frames = m·wall_ns + b over the sampled window.

    drift_ppm = (m·1e9 / sample_rate − 1)·1e6 — the fractional deviation
    of the DAC's presented-frame rate from its nominal rate. The RFC's
    acceptance is |drift| ≤ 50 ppm (Phase 1's ±100 ppm gate tightened).
    Also reports the fit's max residual: a window with a single stall
    (e.g. the callback thread starved >100 ms) shows a step the linear
    fit cannot absorb, surfaced as residual_ns."""
    if len(series) < 8:
        return {
            "oracle": "clock-drift", "ok": False, "skipped": True,
            "reason": f"clock series too small for OLS ({len(series)} points; "
                      "need ≥8 — pass --clock-csv/--clock-json)",
        }
    rates = {r for _, _, r in series}
    rate = sorted(rates)[len(rates) // 2]  # the dominant nominal rate
    xs = [w for w, _, r in series if r == rate]
    ys = [f for _, f, r in series if r == rate]
    n = len(xs)
    if n < 8:
        return {
            "oracle": "clock-drift", "ok": False, "skipped": True,
            "reason": f"dominant-rate series too small ({n} points)",
        }
    span_ns = xs[-1] - xs[0]
    if span_ns <= 0:
        return {"oracle": "clock-drift", "ok": False,
                "reason": "non-monotonic wall clock series"}
    # Ordinary least squares (numerically centered to avoid catastrophic
    # cancellation: xs are ~1e14-1e18 ns magnitudes).
    x0 = xs[0]
    xc = [(x - x0) * 1e-9 for x in xs]  # seconds since first sample
    y0 = ys[0]
    yc = [y - y0 for y in ys]
    mx = sum(xc) / n
    my = sum(yc) / n
    sxx = sum((x - mx) ** 2 for x in xc)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xc, yc))
    if sxx <= 0:
        return {"oracle": "clock-drift", "ok": False,
                "reason": "degenerate clock window (zero span)"}
    m = sxy / sxx  # frames per second
    b = my - m * mx
    drift_ppm = (m / rate - 1.0) * 1e6
    residuals_ms = [(yc[i] - (m * xc[i] + b)) / rate * 1000.0 for i in range(n)]
    max_abs_resid_ms = max(abs(r) for r in residuals_ms)
    return {
        "oracle": "clock-drift",
        "points": n,
        "nominal_rate": rate,
        "span_s": round(span_ns / 1e9, 3),
        "fit_frames_per_s": round(m, 3),
        "drift_ppm": round(drift_ppm, 2),
        "limit_ppm": limit_ppm,
        "max_residual_ms": round(max_abs_resid_ms, 3),
        "ok": abs(drift_ppm) <= limit_ppm,
    }


# ─────────────────────────────────────────────────────────────────────────────
# The verification gates.
# ─────────────────────────────────────────────────────────────────────────────

def steady_state(
    samples: list[float], channels: int, rate: int, trim_ms: float = 50.0,
    max_ms: float | None = None,
) -> tuple[list[list[float]], list[float]]:
    """Per-channel steady-state views + the mono mixdown.

    Cuts the first/last trim_ms of the capture (startup ramp-in and
    teardown envelope — the RFC's Oracle 1 window), then splits channels.
    Leading near-silence (capture pipelines often start with a quiet
    ramp) is ALSO trimmed for the mono view (the legacy gates' r20e
    discipline).

    max_ms caps the analyzed window (the LOOPED-FIXTURE legs: the opus
    fixture is ~3 s and the player loops it to cover the 6 s legs — the
    loop SPLICE is a waveform discontinuity at ~272x the analytical
    bound that Oracle 1 correctly flags. Legs playing the looped opus
    fixture pass --steady-ms 2500 so the oracles grade the FIRST
    fixture period; the splice is the fixture's design, not a HAL
    defect — the Phase 5 Linux-torture run's finding)."""
    frames = len(samples) // channels
    trim = int(trim_ms * rate / 1000.0)
    # Content alignment FIRST (the Phase 5 Linux-torture finding): CI
    # captures (parec / sox / QEMU wav) open the sink BEFORE the player
    # starts, so the capture carries 1-3 s of dead silence and the tone
    # ONSET (0 -> waveform) lands mid-window — a genuine discontinuity
    # Oracle 1 correctly flags at >250x the bound. Skip to the first
    # non-silent FRAME, THEN apply the envelope trim and the steady cap,
    # so the oracles grade steady-state CONTENT, not the lead-in.
    mono_probe = [
        sum(abs(samples[f * channels + c]) for c in range(channels)) / channels
        for f in range(frames)
    ]
    content_start = next(
        (i for i, s in enumerate(mono_probe) if abs(s) > 1e-4), 0
    )
    lo = content_start + trim
    hi = max(lo + 1, frames - trim)
    if max_ms is not None:
        hi = min(hi, lo + int(max_ms * rate / 1000.0))
    chans: list[list[float]] = [
        [samples[f * channels + c] for f in range(lo, hi)]
        for c in range(channels)
    ]
    mono_all = [
        sum(samples[f * channels + c] for c in range(channels)) / channels
        for f in range(frames)
    ]
    mono = mono_all[content_start:]
    mono = mono[trim:] if len(mono) > 2 * trim else mono
    return chans, mono


def verify(
    samples: list[float],
    rate: int,
    channels: int,
    sampwidth: int,
    freq_a: float,
    freq_b: float,
    tolerance_hz: float,
    expect: str,
    profile: str,
    glitch_mult: float,
    thdn_limit_db: float,
    drift_limit_ppm: float,
    clock_series: list[tuple[int, int, int]] | None,
    steady_ms: float | None,
    verbose: bool,
) -> tuple[bool, dict]:
    # Mono mixdown (L+R)/2 — the fixtures are CHANNEL-SEPARATED dual
    # tones (opus_dual_tone_48k: 440 Hz lives in L, 880 Hz in R; the
    # Phase 1 commit that added them says "440 Hz (L) / 880 Hz (R)").
    # The r20e API-34 finding: analyzing LEFT ONLY made the 880 Hz bin
    # structurally invisible (mag 0.03 = pure spectral leakage), and
    # three legs passed only because their noisier capture chains
    # happened to raise the median floor under the leakage. A mixdown
    # shows BOTH tones on every capture chain.
    if channels >= 2:
        frames = len(samples) // channels
        left = [(samples[i * channels] + samples[i * channels + 1]) / 2.0
                for i in range(frames)]
    else:
        left = list(samples)
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
        "profile": profile,
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
    legacy_ok = a_ok and b_ok and dominant_ok and strong

    # ── The Tri-Oracle profile: three mathematical gates on top. ─────────
    if profile != "tri":
        report["verdict"] = "PASS" if legacy_ok else "FAIL"
        return legacy_ok, report

    oracles: dict[str, dict] = {}
    # Steady-state views (50 ms startup/teardown envelope trimmed).
    chans, mono_ss = steady_state(samples, channels, rate, max_ms=steady_ms)
    oracles["oracle_1_glitch_hunter"] = glitch_hunt(chans, rate, freq_b, glitch_mult)
    oracles["oracle_2_thdn"] = thdn_measure(
        mono_ss, rate, freq_a, freq_b, thdn_limit_db, sampwidth
    )
    oracles["oracle_3_clock_drift"] = (
        clock_drift(clock_series or [], drift_limit_ppm)
        if clock_series is not None
        else {
            "oracle": "clock-drift", "skipped": True,
            "reason": "no clock series supplied (--clock-csv / --clock-json); "
                      "chaos legs that must prove the clock pass it explicitly",
        }
    )
    report["oracles"] = oracles
    o1 = oracles["oracle_1_glitch_hunter"]["ok"]
    o2 = oracles["oracle_2_thdn"]["ok"]
    o3 = oracles["oracle_3_clock_drift"].get("skipped", False) or oracles["oracle_3_clock_drift"]["ok"]
    tri_ok = legacy_ok and o1 and o2 and o3
    report["verdict"] = "PASS" if tri_ok else "FAIL"
    return tri_ok, report


def main() -> int:
    ap = argparse.ArgumentParser(description="Sovereign HAL Tri-Oracle loopback verifier")
    ap.add_argument("capture", type=Path, help="captured audio file (WAV)")
    ap.add_argument("--freq-a", type=float, default=440.0)
    ap.add_argument("--freq-b", type=float, default=880.0)
    ap.add_argument("--tolerance-hz", type=float, default=8.0)
    ap.add_argument("--expect", choices=["signal", "silence"], default="signal")
    ap.add_argument("--profile", choices=["legacy", "tri"], default="tri",
                    help="legacy = Phase 0 peak-FFT gates only; tri = the "
                         "Phase 5 Tri-Oracle Mathematical Grader (default)")
    ap.add_argument("--glitch-threshold-mult", type=float, default=4.0,
                    help="Oracle 1 threshold in multiples of the analytical "
                         "dual-tone second-difference bound (default 4.0)")
    ap.add_argument("--thdn-limit-db", type=float, default=-85.0,
                    help="Oracle 2 THD+N ceiling in dB (default -85.0 for "
                         "synthetic/PCM legs; opus legs pass their documented "
                         "codec ceiling, e.g. -45)")
    ap.add_argument("--drift-limit-ppm", type=float, default=50.0,
                    help="Oracle 3 |clock drift| ceiling in ppm (default 50)")
    ap.add_argument("--clock-csv", type=Path, default=None,
                    help="DacPresentationClock series CSV: wall_ns,dac_frames"
                         "[,sample_rate] per line")
    ap.add_argument("--clock-json", type=Path, default=None,
                    help="DacPresentationClock series JSON (list or the "
                         "player.json clock_samples shape)")
    ap.add_argument("--steady-ms", type=float, default=None,
                    help="cap the analyzed steady-state window (looped-fixture "
                         "legs: the opus fixture's ~3 s loop splice is a "
                         "waveform discontinuity the glitch hunter correctly "
                         "flags — pass 2500 to grade the first period)")
    ap.add_argument("--duration-sec", type=float, default=15.0,
                    help="intended capture duration (informational: the PR "
                         "CI legs run 15 s, the nightly soak 60 s; the "
                         "oracles measure whatever the capture contains)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if not args.capture.is_file():
        print(f"sovereign-verifier: capture file not found: {args.capture}", file=sys.stderr)
        return 2

    clock_series = None
    if args.clock_csv is not None or args.clock_json is not None:
        try:
            clock_series = load_clock_series(args.clock_csv, args.clock_json)
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as e:
            print(f"sovereign-verifier: clock series load failed: {e}", file=sys.stderr)
            return 2

    # Decode: RIFF wav first; on a headerless stream (QEMU's wav backend
    # mid-write — it only writes the 44-byte RIFF header on close/flush)
    # fall back to RAW PCM16 s16le stereo with the rate UNKNOWN (the
    # guest HAL negotiates 44.1k or 48k per route): try both and keep
    # whichever verifies (r20d: the OpenSL legs' 440 Hz tone landed at
    # 478.9 Hz under a 48k read).
    ok: bool = False
    report: dict = {}
    try:
        samples, rate, channels, sampwidth = read_wav_f32(args.capture)
    except (wave.Error, EOFError, struct.error):
        if args.verbose:
            print(
                "sovereign-verifier: not a RIFF wave — RAW PCM16 s16le stereo; "
                "trying 48000 Hz then 44100 Hz (QEMU wav mid-write)",
                file=sys.stderr,
            )
        for try_rate in (48_000, 44_100):
            s, r, c, sw = read_raw_pcm16_f32(args.capture, try_rate)
            ok, report = verify(
                s, r, c, sw, args.freq_a, args.freq_b, args.tolerance_hz,
                args.expect, args.profile, args.glitch_threshold_mult,
                args.thdn_limit_db, args.drift_limit_ppm, clock_series,
                args.steady_ms, args.verbose,
            )
            if ok:
                break
    except Exception as e:  # noqa: BLE001 — report any decode failure cleanly
        print(f"sovereign-verifier: capture decode failed: {e}", file=sys.stderr)
        return 2
    else:
        ok, report = verify(
            samples, rate, channels, sampwidth,
            args.freq_a, args.freq_b, args.tolerance_hz,
            args.expect, args.profile, args.glitch_threshold_mult,
            args.thdn_limit_db, args.drift_limit_ppm, clock_series,
            args.steady_ms, args.verbose,
        )

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"sovereign-verifier: {report.get('verdict')} — capture {args.capture}")
        for k, v in report.items():
            if k in ("verdict", "reason", "top10_bins", "oracles", "checks"):
                continue
            print(f"  {k}: {v}")
        for k, v in report.get("checks", {}).items():
            print(f"  check {k}: {'OK' if v else 'FAIL'}")
        for name, o in report.get("oracles", {}).items():
            head = f"  {name}: "
            if o.get("skipped"):
                print(head + f"SKIPPED ({o.get('reason', 'no series')})")
                continue
            if name == "oracle_1_glitch_hunter":
                print(head + ("OK" if o["ok"] else "FAIL")
                      + f" — violations={o['total_violations']} at "
                        f"{o['threshold_mult']}× analytical bound")
                for pc in o["per_channel"]:
                    print(f"    ch{pc['channel']}: peak={pc['peak']} "
                          f"max_d2={pc.get('max_d2')} "
                          f"ratio={pc.get('ratio')}× "
                          f"violations={pc['violations']}"
                          + (f" first@{pc['first_violation_ms']}ms"
                             if pc.get("first_violation_ms") is not None else ""))
            elif name == "oracle_2_thdn":
                print(head + ("OK" if o["ok"] else "FAIL")
                      + f" — THD+N={o.get('thdn_db')} dB "
                        f"(limit {o['limit_db']} dB, fft={o.get('fft_size')})")
                if "quantization_floor_note" in o:
                    print(f"    {o['quantization_floor_note']}")
            elif name == "oracle_3_clock_drift":
                print(head + ("OK" if o["ok"] else "FAIL")
                      + f" — drift={o.get('drift_ppm')} ppm "
                        f"(limit ±{o.get('limit_ppm')} ppm, "
                        f"{o.get('points')} pts, span {o.get('span_s')} s, "
                        f"max_resid {o.get('max_residual_ms')} ms)")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
