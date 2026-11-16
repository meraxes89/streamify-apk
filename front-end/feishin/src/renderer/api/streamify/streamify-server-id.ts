/**
 * streamify/streamify-server-id.ts - the stable synthetic-server id (P3A).
 *
 * Extracted from streamify-supabase.ts (which re-exports it, so existing
 * importers are unchanged): the id is needed by lightweight modules - the
 * playlist sync lane's query invalidation, the controller - whose import
 * graphs must stay loadable without the store barrel / router / DOM-Purify
 * chain that streamify-supabase drags in (the network-free test lane).
 */

/** Stable id for the synthetic server entry (idempotent re-sign-ins). */
export const STREAMIFY_SERVER_ID = 'streamify';
