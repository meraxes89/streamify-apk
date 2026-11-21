#!/usr/bin/env python3
"""test_sovereign_loopback_verifier.py — Phase 5 (RFC Phase A) self-test
battery for the Tri-Oracle Mathematical Grader.

Extends the Phase 0 self-test's discipline (a verifier that would pass
faults is vacuous) to all three oracles:

  CLEAN PASS
    * synthetic dual-tone f32 capture (48 kHz + 44.1 kHz) grades PASS
      under --profile tri with all three oracles OK
    * a clean clock series (0 ppm + 40 ppm) grades PASS / FAIL correctly
      around the 50 ppm bar

  FAULT INJECTION (each MUST fail — the non-vacuousness proofs)
    (a) Oracle 1: ONE dropped sample in the steady-state window → FAIL
        (also: a zero-fill gap, and a single duplicated sample)
    (b) Oracle 2: harmonic aliasing distortion injected at ~-60 dB
        (above the -85 dB bar) → FAIL; a clean 16-bit quantized capture
        still PASSES (the documented -86.6 dB floor)
    (c) Oracle 3: a 75 ppm clock drift skew → FAIL; ±49 ppm passes

  REGRESSION
    * --profile legacy on the ONE-dropped-sample capture still passes
      the peak gates (the Phase 0 gates were blind to single-sample
      glitches — exactly why Oracle 1 exists)

Exit 0 = the verifier is non-vacuous and correctly calibrated.
"""
import math
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VERIFIER = REPO / "scripts" / "sovereign_loopback_verifier.py"


def write_pcm16(path: Path, samples: list[float], rate: int, channels: int) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        # ROUND to nearest, not truncation: int(s*32767) correlates the
        # quantization error with the signal and measures −83.9 dB THD+N
        # on a 2 s capture (above the −85 bar) — a writer artifact, not a
        # capture-chain floor. Real capture chains (parec/QEMU/sox) dither
        # or round; the documented 16-bit floor is the round() row:
        # −85.7 dB @ 2 s / −86.6 dB @ 3 s (measured).
        w.writeframes(struct.pack(
            f"<{len(samples)}h",
            *[max(-32768, min(32767, int(round(s * 32767)))) for s in samples],
        ))


def write_f32(path: Path, samples: list[float], rate: int, channels: int) -> None:
    # stdlib `wave` cannot WRITE format-3 float WAVs; emit RIFF manually
    # (44-byte canonical header — the verifier's reader accepts it).
    data = struct.pack(f"<{len(samples)}f", *samples)
    nch = channels
    byte_rate = rate * nch * 4
    with open(path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", 36 + len(data)))
        f.write(b"WAVE")
        f.write(b"fmt ")
        f.write(struct.pack("<IHHIIHH", 16, 3, nch, rate, byte_rate, nch * 4, 32))
        f.write(b"data")
        f.write(struct.pack("<I", len(data)))
        f.write(data)


def dual_tone(seconds: float = 2.0, rate: int = 48000,
              amp: float = 0.25) -> list[float]:
    """Interleaved stereo dual tone (both channels carry both tones — the
    Oracle-1 per-channel scan needs tone energy on EVERY channel)."""
    out = []
    for i in range(int(seconds * rate)):
        t = i / rate
        s = amp * math.sin(2 * math.pi * 440 * t) + amp * math.sin(2 * math.pi * 880 * t)
        out.extend([s, s])
    return out


def silence(seconds: float = 2.0, rate: int = 48000) -> list[float]:
    return [0.0] * int(seconds * rate) * 2


def run(path: Path, *extra: str) -> int:
    return subprocess.run(
        [sys.executable, str(VERIFIER), str(path), *extra],
        capture_output=True, text=True,
    ).returncode


def run_out(path: Path, *extra: str) -> tuple[int, str]:
    p = subprocess.run(
        [sys.executable, str(VERIFIER), str(path), *extra],
        capture_output=True, text=True,
    )
    return p.returncode, p.stdout + p.stderr


def make_clock_csv(path: Path, seconds: float, drift_ppm: float,
                   rate: int = 48000, step_ms: int = 50) -> None:
    """A DAC presentation-clock series with an EXACT fractional drift."""
    lines = []
    steps = int(seconds * 1000 / step_ms)
    effective = rate * (1.0 + drift_ppm / 1e6)
    for i in range(steps + 1):
        wall_ns = i * step_ms * 1_000_000
        frames = int(effective * (i * step_ms) / 1000.0)
        lines.append(f"{wall_ns},{frames},{rate}")
    path.write_text("\n".join(lines) + "\n")


with tempfile.TemporaryDirectory() as td:
    td = Path(td)

    # ── Clean captures ─────────────────────────────────────────────────
    tone_f32 = td / "tone_f32.wav"
    write_f32(tone_f32, dual_tone(), 48000, 2)
    assert run(tone_f32) == 0, "f32 dual-tone must PASS tri profile (default)"
    print("  PASS f32 dual-tone — tri profile, all three oracles")

    tone16 = td / "tone_pcm16.wav"
    write_pcm16(tone16, dual_tone(), 48000, 2)
    assert run(tone16) == 0, \
        "16-bit dual-tone must PASS tri (documented −86.6 dB quantization floor)"
    print("  PASS pcm16 dual-tone — quantization floor documented, still under −85 dB")

    sil16 = td / "silence_pcm16.wav"
    write_pcm16(sil16, silence(), 48000, 2)
    assert run(sil16, "--expect", "silence") == 0, "silence must PASS silence mode"
    assert run(sil16) == 1, "silence must FAIL signal mode (non-vacuous gate)"
    print("  PASS silence controls")

    tone441 = td / "tone441.wav"
    write_pcm16(tone441, dual_tone(rate=44100), 44100, 2)
    assert run(tone441, "--tolerance-hz", "10") == 0, "44.1 kHz capture must pass"
    print("  PASS 44.1 kHz capture")

    # ── Oracle 3 — clean + drifted clock series ────────────────────────
    clk_ok = td / "clock_ok.csv"
    make_clock_csv(clk_ok, 15.0, drift_ppm=0.0)
    make_clock_csv(td / "clock_40.csv", 15.0, drift_ppm=40.0)
    make_clock_csv(td / "clock_49.csv", 15.0, drift_ppm=49.0)
    make_clock_csv(td / "clock_75.csv", 15.0, drift_ppm=75.0)
    make_clock_csv(td / "clock_m75.csv", 15.0, drift_ppm=-75.0)

    assert run(tone_f32, "--clock-csv", str(clk_ok)) == 0, "clean clock must PASS"
    assert run(tone_f32, "--clock-csv", str(td / "clock_40.csv")) == 0, \
        "+40 ppm must PASS (within ±50)"
    assert run(tone_f32, "--clock-csv", str(td / "clock_49.csv")) == 0, \
        "+49 ppm must PASS (just inside ±50)"
    print("  PASS clock drift 0 / +40 / +49 ppm (inside the ±50 ppm bar)")

    # ── Fault (a): single dropped sample → Oracle 1 FAIL ───────────────
    # 1 s in (past the 50 ms startup trim), delete ONE frame from BOTH
    # channels (the loopback's frame quantum — a dropped buffer frame).
    dropped = dual_tone()
    idx = 48000 * 2  # sample index of frame 48000 (1 s in)
    del dropped[idx : idx + 2]
    dropped_wav = td / "dropped.wav"
    write_f32(dropped_wav, dropped, 48000, 2)
    assert run(dropped_wav) == 1, "ONE dropped sample must FAIL Oracle 1"
    print("  FAULT-CAUGHT oracle 1: single dropped sample")

    # The Phase 0 legacy profile was blind to it (the regression proof).
    assert run(dropped_wav, "--profile", "legacy") == 0, \
        "legacy profile stays peak-gate-only (documents why Oracle 1 exists)"
    print("  PASS regression: legacy profile blind to the single-sample glitch (by design)")

    # A zero-fill gap (underrun arm mid-capture): 3 ms of zeros, 1 s in
    # (well inside the steady-state window — the first fault test placed
    # it at the capture's very END, inside the 50 ms teardown trim, and
    # proved nothing).
    gap = dual_tone(seconds=3.0)
    g0 = 48000 * 2  # frame 48000 = 1.0 s into the 3 s capture
    gap[g0 : g0 + 144 * 2] = [0.0] * (144 * 2)
    gap_wav = td / "gap.wav"
    write_f32(gap_wav, gap, 48000, 2)
    assert run(gap_wav) == 1, "a 3 ms zero-fill gap must FAIL Oracle 1"
    print("  FAULT-CAUGHT oracle 1: zero-fill underrun gap")

    # A single duplicated frame (double-read wrap), 2 s in.
    dup = dual_tone(seconds=3.0)
    d0 = 48000 * 2 * 2
    dup[d0 : d0] = dup[d0 : d0 + 2]
    dup_wav = td / "dup.wav"
    write_f32(dup_wav, dup, 48000, 2)
    assert run(dup_wav) == 1, "a duplicated frame must FAIL Oracle 1"
    print("  FAULT-CAUGHT oracle 1: duplicated frame (buffer-wrap double read)")

    # ── Fault (b): harmonic aliasing distortion → Oracle 2 FAIL ────────
    # Inject 3rd/5th/7th harmonics of 440 Hz at ~-60 dB relative — far
    # above the -85 dB residual bar, invisible to peak gates.
    harm = dual_tone()
    h0 = 48000 * 2  # start 1 s in (steady state)
    for i in range(48000):  # 1 s of distortion
        t = i / 48000
        d = 0.001 * (
            math.sin(2 * math.pi * 3 * 440 * t)
            + math.sin(2 * math.pi * 5 * 440 * t)
            + math.sin(2 * math.pi * 7 * 440 * t)
        )
        for c in range(2):
            harm[h0 + i * 2 + c] += d
    harm_wav = td / "harmonic.wav"
    write_f32(harm_wav, harm, 48000, 2)
    assert run(harm_wav) == 1, "harmonic distortion (>-85 dB residual) must FAIL Oracle 2"
    print("  FAULT-CAUGHT oracle 2: harmonic distortion above the −85 dB bar")

    # The same capture under the opus-leg codec ceiling stays PASS (the
    # documented calibration: codec noise floors are physics, not HAL bugs).
    assert run(harm_wav, "--thdn-limit-db", "-45") == 0, \
        "codec-leg ceiling (−45 dB) absorbs sub-ceiling distortion"
    print("  PASS oracle 2 source-aware ceiling (opus legs at −45 dB)")

    # ── Fault (c): clock drift skew → Oracle 3 FAIL ────────────────────
    assert run(tone_f32, "--clock-csv", str(td / "clock_75.csv")) == 1, \
        "+75 ppm drift must FAIL Oracle 3"
    assert run(tone_f32, "--clock-csv", str(td / "clock_m75.csv")) == 1, \
        "−75 ppm drift must FAIL Oracle 3"
    print("  FAULT-CAUGHT oracle 3: ±75 ppm clock drift skew")

    # JSON sidecar shape (sovereign_cpal_loopback's player.json).
    clk_json = td / "clock.json"
    series = []
    for i in range(0, 301):
        wall_ns = i * 50_000_000
        frames = int(48000 * (1.0 + 75.0 / 1e6) * (i * 0.05))
        series.append({"monotonic_ns": wall_ns, "dac_frame_position": frames,
                       "sample_rate": 48000})
    clk_json.write_text(
        '{"clock_samples": ' + json_dumps(series) + "}"
        if (json_dumps := __import__("json").dumps) else "[]"
    )
    assert run(tone_f32, "--clock-json", str(clk_json)) == 1, \
        "JSON sidecar +75 ppm drift must FAIL Oracle 3"
    print("  FAULT-CAUGHT oracle 3: JSON sidecar clock series (75 ppm)")

    # No clock supplied → skipped is REPORTED and never fails the run.
    rc, out = run_out(tone_f32)
    assert rc == 0 and "SKIPPED" in out, \
        "no clock series must report SKIPPED (visible, not silently green)"
    print("  PASS oracle 3 skip is reported, never silent")

    # Truncated capture must fail cleanly (exit 2 path is decode; short is 1).
    tiny = td / "tiny.wav"
    write_pcm16(tiny, [0.0] * 100, 48000, 2)
    rc = run(tiny)
    assert rc in (1, 2), f"tiny capture must fail, got {rc}"
    print("  PASS tiny capture rejected")

print("sovereign_loopback_verifier Tri-Oracle self-test: ALL PASS")
