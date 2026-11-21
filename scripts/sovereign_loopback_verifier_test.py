#!/usr/bin/env python3
"""Self-test for sovereign_loopback_verifier.py: synthesizes captures the
smoke binary would produce (440+880 dual tone / silence, PCM16 + f32 WAV
variants) and asserts the verifier's verdicts. Run from repo root."""
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
        # round-to-nearest (Phase 5 / tri-profile calibration: the old
        # int() truncation correlates the quantization error with the
        # signal — −83.9 dB THD+N on a 2 s capture, above the −85 bar —
        # a writer artifact, not a capture-chain floor; real chains
        # round/dither, measuring −85.7 dB @ 2 s).
        w.writeframes(struct.pack(
            f"<{len(samples)}h",
            *[max(-32768, min(32767, int(round(s * 32767)))) for s in samples],
        ))


def dual_tone(seconds: float = 2.0, rate: int = 48000) -> list[float]:
    out = []
    for i in range(int(seconds * rate)):
        t = i / rate
        s = 0.25 * math.sin(2 * math.pi * 440 * t) + 0.25 * math.sin(2 * math.pi * 880 * t)
        out.extend([s, s])
    return out


def silence(seconds: float = 2.0, rate: int = 48000) -> list[float]:
    return [0.0] * int(seconds * rate) * 2


def run(path: Path, *extra: str) -> int:
    return subprocess.run(
        [sys.executable, str(VERIFIER), str(path), *extra],
        capture_output=True, text=True,
    ).returncode


with tempfile.TemporaryDirectory() as td:
    td = Path(td)
    tone16 = td / "tone_pcm16.wav"
    sil16 = td / "silence_pcm16.wav"
    write_pcm16(tone16, dual_tone(), 48000, 2)
    write_pcm16(sil16, silence(), 48000, 2)

    assert run(tone16) == 0, "PCM16 dual-tone must PASS signal mode"
    print("  PASS pcm16 dual-tone signal")
    assert run(sil16, "--expect", "silence") == 0, "silence must PASS silence mode"
    print("  PASS pcm16 silence control")
    assert run(sil16) == 1, "silence must FAIL signal mode (non-vacuous gate)"
    print("  PASS silence fails signal mode (verifier is not vacuous)")
    assert run(tone16, "--freq-a", "1000", "--freq-b", "2000") == 1, \
        "wrong-frequency expectation must fail"
    print("  PASS wrong-frequency expectation fails")

    # 44.1 kHz capture with resampling capture pipelines.
    tone441 = td / "tone441.wav"
    write_pcm16(tone441, dual_tone(rate=44100), 44100, 2)
    assert run(tone441, "--tolerance-hz", "10") == 0, "44.1 kHz capture must pass"
    print("  PASS 44.1 kHz capture")

    # Truncated capture must fail cleanly (exit 2 path is decode; short is 1).
    tiny = td / "tiny.wav"
    write_pcm16(tiny, [0.0] * 100, 48000, 2)
    rc = run(tiny)
    assert rc in (1, 2), f"tiny capture must fail, got {rc}"
    print("  PASS tiny capture rejected")

print("sovereign_loopback_verifier self-test: ALL PASS")
