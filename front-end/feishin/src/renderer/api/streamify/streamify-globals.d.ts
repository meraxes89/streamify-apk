/**
 * Runtime configuration globals for the Streamify server type (P1).
 *
 * Web deployments provide these through settings.js (see
 * settings.js.template at the fork root): values live in deployment
 * configuration, never in the tree.
 */
declare global {
    interface Window {
        /** Explicit opt-out for the /streamify-auth gate ('false' disables). */
        STREAMIFY_AUTH?: boolean | string;
        /** P2-A: the resolver relay base (empty = relay off). */
        STREAMIFY_RELAY_BASE?: string;
        /** Public-by-design anon key of the Streamify Supabase project. */
        STREAMIFY_SUPABASE_ANON_KEY?: string;
        /** Base URL of the Streamify Supabase project. */
        STREAMIFY_SUPABASE_URL?: string;
    }
}

export {};
