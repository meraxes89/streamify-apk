/**
 * streamify/jam-live/jam-live-frac.ts - the fractional ordering engine for
 * the listening-room lane (P4B).
 *
 * POST-#190-REBASE CONSOLIDATION: pure re-export of P3A's
 * `streamify/jam/fractional-index.ts` (the Rust/Kotlin mirror) - one
 * frozen semantic, one implementation.
 */

export {
    FRAC_FIRST_INDEX,
    FRAC_HEAD,
    fracAfter,
    fracBefore,
    fracBetween,
    fracCollide,
    fracIndexForInsertAt,
    ulpOf,
} from '../jam/fractional-index';
