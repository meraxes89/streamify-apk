/**
 * jam-live-wire tests (P4B) - the frozen 48-byte op row.
 *
 * The golden vectors (fixtures/jam_golden_vectors.json, repo root) pin the
 * rust_48 byte image across runtimes; these tests prove the vendored
 * listening-room codec is byte-identical to that image, plus the codec's
 * own invariants: FNV-1a seal, pad rejection, DTO round-trips, journal-row
 * normalization, and the op-id mint's clock ratchet.
 */

import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import {
    bytesToHex,
    fracBitsOf,
    generateOpId,
    hexToBytes,
    JAM_OP_SIZE,
    type JamJournalRow,
    type JamOp,
    jamOpChecksum,
    jamOpFromBytes,
    jamOpFromDto,
    jamOpFromJournalRow,
    jamOpIsValid,
    jamOpNew,
    jamOpToBytes,
    jamOpToDto,
    OP_ADD,
    OP_REORDER,
    OP_VOTE,
    resetOpMintForTests,
    seedOpCounter,
} from '../jam-live/jam-live-wire';

interface GoldenVector {
    fields: {
        cad_id: string;
        frac_bits: string;
        op_id: string;
        op_type: number;
        policy: number;
        sender: string;
        target: string;
    };
    name: string;
    rust_48: string;
}

const loadGoldenVectors = (): GoldenVector[] => {
    const fixturePath = path.resolve(
        __dirname,
        '../../../../../../../fixtures/jam_golden_vectors.json',
    );
    const raw = JSON.parse(fs.readFileSync(fixturePath, 'utf8')) as { vectors: GoldenVector[] };
    return raw.vectors;
};

const opFromVector = (vector: GoldenVector): JamOp => {
    const senderHex = vector.fields.sender.replace(/^0x/, '').padStart(16, '0').slice(-8);
    return jamOpNew(
        BigInt(vector.fields.op_id),
        hexToBytes(senderHex) ?? new Uint8Array(4),
        vector.fields.op_type,
        vector.fields.policy,
        BigInt(vector.fields.cad_id),
        fracBitsToNumber(BigInt(vector.fields.frac_bits)),
        BigInt(vector.fields.target),
    );
};

const fracBitsToNumber = (bits: bigint): number => {
    const view = new DataView(new ArrayBuffer(8));
    view.setBigUint64(0, bits, true);
    return view.getFloat64(0, true);
};

describe('jam-live-wire: golden vectors (rust_48 byte image)', () => {
    const vectors = loadGoldenVectors();

    it('loads the frozen fixture', () => {
        expect(vectors.length).toBeGreaterThanOrEqual(5);
    });

    for (const vector of vectors) {
        it(`round-trips ${vector.name} byte-identically`, () => {
            const op = opFromVector(vector);
            const bytes = jamOpToBytes(op);
            expect(bytes).toHaveLength(JAM_OP_SIZE);
            expect(bytesToHex(bytes)).toBe(vector.rust_48);

            const decoded = jamOpFromBytes(bytes);
            expect(decoded).not.toBeNull();
            expect(decoded?.opId).toBe(op.opId);
            expect(decoded?.opType).toBe(op.opType);
            expect(decoded?.policyFlags).toBe(op.policyFlags);
            expect(decoded?.trackCadId).toBe(op.trackCadId);
            expect(decoded?.targetAddOpId).toBe(op.targetAddOpId);
            expect(fracBitsOf(decoded?.fracIndex ?? 0)).toBe(fracBitsOf(op.fracIndex));
            expect(jamOpIsValid(decoded as JamOp)).toBe(true);
        });
    }
});

describe('jam-live-wire: codec invariants', () => {
    const nonce = hexToBytes('00a11ce0') ?? new Uint8Array(4);

    it('seals and validates the FNV-1a checksum', () => {
        const op = jamOpNew(0x0000000100000001n, nonce, OP_ADD, 1, 0x0000beef0000cafen, 0.3, 0n);
        expect(jamOpIsValid(op)).toBe(true);

        const tampered = { ...op, trackCadId: 0x0000beef0000caffn };
        expect(jamOpIsValid(tampered as JamOp)).toBe(false);
        expect(jamOpChecksum(tampered)).not.toBe(op.checksum);
    });

    it('rejects wrong lengths and non-zero pads', () => {
        expect(jamOpFromBytes(new Uint8Array(47))).toBeNull();
        expect(jamOpFromBytes(new Uint8Array(49))).toBeNull();

        const bytes = jamOpToBytes(jamOpNew(1n, nonce, OP_ADD, 0, 2n, 0.5, 0n));
        bytes[14] = 1; // pad1
        expect(jamOpFromBytes(bytes)).toBeNull();

        const bytes2 = jamOpToBytes(jamOpNew(1n, nonce, OP_ADD, 0, 2n, 0.5, 0n));
        bytes2[45] = 1; // pad2
        expect(jamOpFromBytes(bytes2)).toBeNull();
    });

    it('round-trips the DTO with u64 decimal strings', () => {
        const op = jamOpNew(
            0xdeadbeefcafebaben,
            nonce,
            OP_REORDER,
            0,
            0xa1b2c3d4e5f60718n,
            1.5,
            0x0000000100000002n,
        );
        const dto = jamOpToDto(op);
        expect(dto.opId).toBe('16045690984503098046');
        expect(dto.cadId).toBe('11651590505119483672');
        expect(dto.target).toBe('4294967298');
        expect(dto.senderNonce).toBe('00a11ce0');

        const back = jamOpFromDto(dto);
        expect(back).not.toBeNull();
        expect(back?.opId).toBe(op.opId);
        expect(back?.trackCadId).toBe(op.trackCadId);
        expect(back?.opType).toBe(OP_REORDER);
        expect(jamOpIsValid(back as JamOp)).toBe(true);
    });

    it('normalizes journal rows (string and number u64s) and rejects garbage', () => {
        const fracBits = fracBitsOf(0.75).toString(10);

        const row: JamJournalRow = {
            frac_bits: fracBits,
            op_id: '123',
            op_type: OP_VOTE,
            policy_flags: 0,
            sender_nonce: '1055704864',
            target_add_op_id: '789',
            track_cad_id: '456',
        };
        const fromRow = jamOpFromJournalRow(row);
        expect(fromRow).not.toBeNull();
        expect(fromRow?.opId).toBe(123n);
        expect(fromRow?.trackCadId).toBe(456n);
        expect(fromRow?.targetAddOpId).toBe(789n);
        expect(fromRow?.fracIndex).toBeCloseTo(0.75, 15);
        expect(jamOpIsValid(fromRow as JamOp)).toBe(true);

        // numeric delivery is accepted too
        const numericRow: JamJournalRow = { ...row, op_id: 123, track_cad_id: 456 };
        expect(jamOpFromJournalRow(numericRow)?.opId).toBe(123n);

        // rejections
        expect(jamOpFromJournalRow({ ...row, op_type: 0 })).toBeNull();
        expect(jamOpFromJournalRow({ ...row, op_type: 5 })).toBeNull();
        expect(jamOpFromJournalRow({ ...row, op_id: 'not-a-number' })).toBeNull();
        expect(jamOpFromJournalRow({ ...row, op_id: '-5' })).toBeNull();
        expect(jamOpFromJournalRow({ ...row, sender_nonce: '99999999999' })).toBeNull();
        expect(jamOpFromJournalRow({ ...row, frac_bits: 'zzz' })).toBeNull();
    });
});

describe('jam-live-wire: op-id mint (hybrid logical clock)', () => {
    it('ratchets over clock step-back and mints strictly increasing ids', () => {
        resetOpMintForTests(1000n);
        const a = generateOpId(2000n);
        const b = generateOpId(2000n); // same ms -> counter bump
        const c = generateOpId(1999n); // wall clock went back -> ratchet
        expect(b).toBeGreaterThan(a);
        expect(c).toBeGreaterThan(b);
        expect(a >> 16n).toBe(2000n);
        // The vendored Rust discipline ratchets the MS on same-ms calls
        // (strict monotonicity beats counter sub-encoding).
        expect(b >> 16n).toBe(2001n);
        expect(c >> 16n).toBe(2002n);
        expect(Number(a & 0xffffn)).toBeLessThan(0x10000);
    });

    it('seeds the counter per device', () => {
        resetOpMintForTests(0n, 0);
        seedOpCounter(0x1234);
        const id = generateOpId(5000n);
        expect(id & 0xffffn).toBe(0x1234n);
    });
});
