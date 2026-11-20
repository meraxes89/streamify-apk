#!/usr/bin/env python3
"""Golden-vector fixtures for the WS-A playback data plane (A3/A5, issue #151).

Generates the checked-in WAV fixtures the live-audio self-test harness
(`rust/crates/streamify-contract/tests/test_live_audio_selftest.rs`) and the
decode suite (`rust/crates/streamify-playback/tests/test_decode.rs`) consume:

  * playback_selftest_440hz_48k.wav  — 1.5 s, 440 Hz, stereo f32 LE @ 48 kHz
    (the canonical geometry: exercises decode passthrough + the LUFS chain +
    the "engine says 440" FFT gate)
  * playback_selftest_440hz_44k1.wav — 1.5 s, 440 Hz, stereo f32 LE @ 44.1 kHz
    (exercises the CanonicalSrc rubato resample leg end-to-end)

WAVE_FORMAT_IEEE_FLOAT (tag 3), 32-bit little-endian — the format symphonia's
`pcm` + `wav` features decode natively. Deterministic by construction (pure
phase-accumulated sine, no RNG), so the Rust suites can pin exact frame counts
and the FFT dominant bin.

Regenerate with:  python3 scripts/generate_playback_fixtures.py
(Outputs are checked in — the SHA-256 discipline lives in the Rust tests,
which hash whatever bytes are present, so a regeneration only changes the
pinned digests, never the acceptance logic.)
"""

from __future__ import annotations

import math
import struct
from pathlib import Path

FIXTURE_DIR = Path(__file__).resolve().parent.parent / "fixtures" / "audio"
CLIP_SECONDS = 1.5
FREQ_HZ = 440.0
AMPLITUDE = 0.5  # -6 dBFS: comfortably inside the LUFS ±20 dB normalize window


def write_sine_wav(path: Path, sample_rate: int) -> None:
    frames = int(sample_rate * CLIP_SECONDS)
    data = bytearray()
    phase = 0.0
    step = 2.0 * math.pi * FREQ_HZ / sample_rate
    for _ in range(frames):
        s = math.sin(phase) * AMPLITUDE
        phase += step
        for _ in range(2):  # interleaved stereo
            data += struct.pack("<f", s)

    # Canonical 44-byte RIFF/WAVE header for f32 LE stereo.
    header = b"RIFF"
    header += struct.pack("<I", 36 + len(data))
    header += b"WAVE"
    header += b"fmt "
    header += struct.pack("<IHHIIHH", 16, 3, 2, sample_rate,
                          sample_rate * 4 * 2, 4 * 2, 32)
    header += b"data"
    header += struct.pack("<I", len(data))

    path.write_bytes(header + bytes(data))
    print(f"{path.name}: {frames} frames @ {sample_rate} Hz, "
          f"{len(header) + len(data)} bytes")


def main() -> None:
    FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    write_sine_wav(FIXTURE_DIR / "playback_selftest_440hz_48k.wav", 48_000)
    write_sine_wav(FIXTURE_DIR / "playback_selftest_440hz_44k1.wav", 44_100)


if __name__ == "__main__":
    main()
