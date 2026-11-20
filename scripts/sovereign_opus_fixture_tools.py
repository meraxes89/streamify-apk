#!/usr/bin/env python3
"""Sovereign HAL Phase 1 (D3): build the raw server-framed Opus fixture.

Extracts packets from the Ogg-Opus fixture and emits the #225 EBML
extractor's framing: 19-byte OpusHead + [UInt16BE len][opus packet]*.
"""
import struct
import pathlib


def parse_ogg_packets(ogg: bytes):
    packets, i = [], 0
    while i + 27 <= len(ogg):
        if ogg[i:i + 4] != b"OggS":
            break
        seg_count = ogg[i + 26]
        seg_table = ogg[i + 27:i + 27 + seg_count]
        body_start = i + 27 + seg_count
        body_len = sum(seg_table)
        body = ogg[body_start:body_start + body_len]
        pos = acc = 0
        for s in seg_table:
            acc += s
            if s < 255:
                packets.append(body[pos:pos + acc])
                pos += acc
                acc = 0
        i = body_start + body_len
    return packets


def main():
    base = pathlib.Path(__file__).resolve().parent.parent / "fixtures" / "audio"
    ogg = (base / "opus_dual_tone_48k.ogg").read_bytes()
    packets = parse_ogg_packets(ogg)
    assert len(packets) >= 3 and packets[0][:8] == b"OpusHead" and packets[1][:8] == b"OpusTags", \
        f"unexpected ogg packet layout: {[p[:8] for p in packets[:3]]}"
    opus_head, audio = packets[0], packets[2:]
    out = bytearray(opus_head)
    for p in audio:
        assert len(p) < 65536, "packet too long for UInt16BE framing"
        out += struct.pack(">H", len(p)) + p
    (base / "opus_dual_tone_48k.raw_opus").write_bytes(bytes(out))
    print(f"raw fixture: {len(out)} bytes, {len(audio)} packets, ~{len(audio) * 20} ms")


if __name__ == "__main__":
    main()
