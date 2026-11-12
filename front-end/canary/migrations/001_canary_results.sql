-- ============================================================================
-- 001_canary_results.sql — W0.1b residential canary telemetry table.
--
-- Stores ONE row per canary run from the #/canary page (WS-W P0 brief,
-- W0.1b): the 10-client × 2-video probe matrix (browser translation of
-- tools/probe-clients.sh), the four R-C condition verdicts, and the minimal
-- environment facts — user agent string + page origin. NO IP address, no
-- geolocation, no fingerprinting beyond the user agent (the residential
-- class of the network is known from who the PO sent the URL to, not from
-- anything the page collects).
--
-- RLS exception, PO-reviewed as part of the W0.1b scope: this table has a
-- single INSERT-only policy for the anon role and deliberately NO
-- select/update/delete policies for anon. That is a scoped, deliberate
-- exception to the SEC-10 write-hardening posture
-- (supabase/migrations/2026_11_sec10_rls_hardening.sql — inserts elsewhere
-- must be authenticated and rate-limited): the canary is one-way anonymous
-- fleet telemetry by design. The visitor running the page from their
-- residential network has no account to authenticate with, and with only
-- the anon key nobody — including the visitor — can read, enumerate,
-- tamper with, or delete rows. anon's access to this table stops at
-- INSERT; reads stay with the dashboard / service roles.
--
-- Grants: `GRANT USAGE ON SCHEMA public TO anon, authenticated,
-- service_role` plus the table DML grants already exist per repo
-- convention (supabase/sql/tests/01_grants.sql mirrors the Supabase
-- defaults; SEC-10 relies on the same), so nothing extra is granted here.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.canary_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_agent TEXT,
    page_origin TEXT,
    results JSONB NOT NULL,
    rc_conditions JSONB NOT NULL,
    notes TEXT
);

ALTER TABLE public.canary_results ENABLE ROW LEVEL SECURITY;

-- One-way anonymous write: INSERT only. WITH CHECK (TRUE) is the whole
-- point of the PO-reviewed exception above — the canary posts from a
-- browser holding nothing but the anon key. Deliberately NOT created:
-- any SELECT / UPDATE / DELETE policy for anon on this table.
DROP POLICY IF EXISTS "Anon can insert canary results" ON public.canary_results;
CREATE POLICY "Anon can insert canary results" ON public.canary_results
    FOR INSERT TO anon WITH CHECK (TRUE);
