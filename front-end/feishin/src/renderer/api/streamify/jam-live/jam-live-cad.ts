/**
 * streamify/jam-live/jam-live-cad.ts - the cad-id hasher for the
 * listening-room lane (P4B).
 *
 * POST-#190-REBASE CONSOLIDATION: this module used to vendor P3A's cad
 * hasher as a pre-rebase sibling. With `streamify/jam/` on main it is now
 * a pure re-export - one frozen semantic, one implementation, exactly like
 * the wasm/web/Android lanes. The listening-room additions (if any land
 * here) stay additive.
 */

export {
    deviceNonceHex,
    deviceNonceOf,
    fnv1a32Of,
    generateCadIdU64,
    normalizeCadField,
} from '../jam/cad';
