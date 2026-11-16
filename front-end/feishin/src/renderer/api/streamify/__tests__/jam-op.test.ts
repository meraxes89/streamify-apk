/**
 * jam-op.test.ts - the frozen 48-byte op row lock (P3A).
 *
 * Pins the wire format the Rust/Android/wasm engines already froze (see
 * fixtures/jam_golden_vectors.json): the byte layout, the FNV-1a-32 seal,
 * the pad rejection, the op-id ratchet. A byte-image drift here breaks
 * cross-runtime op exchange, so every offset is asserted explicitly.
 */

import { describe, expect, it } from 'vitest';

import {
    bytesToHex,
    fracBitsOf,
    fracFromBits,
    generateOpId,
    hexToBytes,
    JAM_OP_SIZE,
    jamOpFromBytes,
    jamOpFromDto,
    jamOpIsValid,
    jamOpNew,
    jamOpToBytes,
    jamOpToDto,
    OP_ADD,
    OP_REMOVE,
    OP_REORDER,
    resetOpMintForTests,
    seedOpCounter,
} from '../jam/jam-op';

const NONCE = hexToBytes('deadbeef') ?? new Uint8Array(4);

function readHex(bytes: Uint8Array): string {
    return bytesToHex(bytes);
}

describe('jam-op — the 48-byte wire row', () => {
    it('packs the documented little-endian layout at every offset', () => {
        const op = jamOpNew(
            0x0102030405060708n,
            NONCE,
            OP_ADD,
            0x02,
            0x1112131415161718n,
            0.5,
            0x191a1b1c1d1e1f20n,
        );
        const bytes = jamOpToBytes(op);
        expect(bytes.length).toBe(JAM_OP_SIZE);
        expect(bytes.length).toBe(48);
        expect(readHex(bytes.subarray(0, 8))).toBe('0807060504030201'); // op_id LE
        expect(readHex(bytes.subarray(8, 12))).toBe('deadbeef'); // sender_nonce
        expect(bytes[12]).toBe(OP_ADD); // op_type
        expect(bytes[13]).toBe(0x02); // policy_flags
        expect(readHex(bytes.subarray(14, 16))).toBe('0000'); // pad1 zero
        expect(readHex(bytes.subarray(16, 24))).toBe('1817161514131211'); // cad LE
        // frac raw IEEE-754 bits LE: 0.5 = 0x3FE0000000000000
        expect(readHex(bytes.subarray(24, 32))).toBe('000000000000e03f');
        expect(readHex(bytes.subarray(32, 40))).toBe('201f1e1d1c1b1a19'); // target LE
        expect(readHex(bytes.subarray(44, 48))).toBe('00000000'); // pad2 zero
        // checksum occupies [40..44): FNV-1a-32 over [0..40) - verified below
    });

    it('round-trips bytes -> op -> bytes exactly', () => {
        const op = jamOpNew(
            1770000000000000123n,
            NONCE,
            OP_REORDER,
            0,
            0xabcdefabcdefabcdn,
            2.25,
            1770000000000000042n,
        );
        const bytes = jamOpToBytes(op);
        const back = jamOpFromBytes(bytes);
        expect(back).not.toBeNull();
        expect(back?.opId).toBe(op.opId);
        expect(back?.opType).toBe(OP_REORDER);
        expect(back?.trackCadId).toBe(op.trackCadId);
        expect(back?.fracIndex).toBe(2.25);
        expect(back?.targetAddOpId).toBe(op.targetAddOpId);
        expect(back?.checksum).toBe(op.checksum);
        expect(readHex(jamOpToBytes(back!))).toBe(readHex(bytes));
    });

    it('seals the FNV-1a-32 checksum and rejects tampered payloads', () => {
        const op = jamOpNew(42n, NONCE, OP_ADD, 0, 7n, 0.5, 0n);
        expect(jamOpIsValid(op)).toBe(true);
        // Flip one payload byte (the frac) - the seal must break.
        const tampered = { ...op, fracIndex: 0.75 };
        expect(jamOpIsValid(tampered)).toBe(false);
        // Flip the cad - still breaks.
        expect(jamOpIsValid({ ...op, trackCadId: 8n })).toBe(false);
        // A recomputed seal makes the tampered payload valid again (that is
        // how a SENDER seals; receivers must never do this).
        const resealed = jamOpNew(
            op.opId,
            NONCE,
            op.opType,
            op.policyFlags,
            8n,
            op.fracIndex,
            op.targetAddOpId,
        );
        expect(jamOpIsValid(resealed)).toBe(true);
    });

    it('rejects non-zero pads, wrong lengths, and corrupt input', () => {
        const op = jamOpNew(1n, NONCE, OP_ADD, 0, 1n, 0.5, 0n);
        const bytes = jamOpToBytes(op);
        bytes[14] = 1; // pad1 violation
        expect(jamOpFromBytes(bytes)).toBeNull();
        const bytes2 = jamOpToBytes(op);
        bytes2[45] = 1; // pad2 violation
        expect(jamOpFromBytes(bytes2)).toBeNull();
        expect(jamOpFromBytes(bytes.subarray(0, 47))).toBeNull(); // wrong length
        expect(jamOpFromBytes(new Uint8Array(48))).not.toBeNull(); // all-zero row parses
    });

    it('crosses the DTO boundary with u64s as decimal strings', () => {
        const op = jamOpNew(12345678901234567890n, NONCE, OP_REMOVE, 0, 99n, 1.5, 5n);
        const dto = jamOpToDto(op);
        expect(dto.opId).toBe('12345678901234567890'); // exact above 2^53
        expect(dto.cadId).toBe('99');
        expect(dto.target).toBe('5');
        expect(dto.senderNonce).toBe('deadbeef');
        expect(dto.frac).toBe(1.5);
        const back = jamOpFromDto(dto);
        expect(back).not.toBeNull();
        expect(back?.opId).toBe(12345678901234567890n);
        expect(back?.checksum).toBe(op.checksum);
        expect(jamOpFromDto({ ...dto, opType: 9 })).toBeNull(); // unknown type
        expect(jamOpFromDto({ ...dto, opId: 'not-a-number' })).toBeNull();
        expect(jamOpFromDto({ ...dto, frac: Number.NaN })).toBeNull();
    });

    it('mints strictly monotonic op ids, ratcheting over clock step-back', () => {
        resetOpMintForTests(0n, 0);
        const a = generateOpId(1000n);
        const b = generateOpId(1000n); // same ms - the ratchet bumps the ms
        const c = generateOpId(1000n);
        expect(a < b).toBe(true);
        expect(b < c).toBe(true);
        expect(a >> 16n).toBe(1000n);
        expect(a & 0xffffn).toBe(0n);
        expect(b >> 16n).toBe(1001n); // ratcheted to prev+1 (the Rust rule)
        expect(c >> 16n).toBe(1002n);
        // Wall clock steps BACKWARD: the ratchet yields prev+1, never less.
        const d = generateOpId(500n);
        expect(d > c).toBe(true);
        expect(d >> 16n).toBe(1003n);
        // The counter advances every mint (fetch_add discipline).
        expect(b & 0xffffn).toBe(1n);
        expect(d & 0xffffn).toBe(3n);
        resetOpMintForTests();
    });

    it('seeds the counter per device (cross-device same-ms overlap)', () => {
        resetOpMintForTests(3000n, 0);
        const a = generateOpId(3000n) & 0xffffn;
        seedOpCounter(0x9000);
        const b = generateOpId(3000n) & 0xffffn;
        expect(a).toBe(0n);
        expect(b).toBe(0x9000n);
        resetOpMintForTests();
    });

    it('converts fracs to exact raw bits and back', () => {
        expect(fracBitsOf(0.5)).toBe(0x3fe0000000000000n);
        expect(fracBitsOf(0.0625)).toBe(0x3fb0000000000000n);
        expect(fracFromBits(0x3fe0000000000000n)).toBe(0.5);
        // A midpoints-exhausted value round-trips exactly.
        const tiny = 0.5000000000000002;
        expect(fracFromBits(fracBitsOf(tiny))).toBe(tiny);
    });
});
