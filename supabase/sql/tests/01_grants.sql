-- ============================================================================
-- 01_grants.sql — Supabase-default privilege emulation (test-only).
-- Applied AFTER supabase/schema.sql, BEFORE rls_test.sql.
--
-- Supabase projects grant the API roles (anon / authenticated /
-- service_role) full table + function privileges on the public schema
-- and rely on RLS for protection — this replicates that posture so the
-- test suite proves the POLICIES (not missing grants) are the fence.
-- ============================================================================

GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public
    TO anon, authenticated, service_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public
    TO anon, authenticated, service_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public
    TO anon, authenticated, service_role;
GRANT USAGE ON SCHEMA auth TO anon, authenticated, service_role;
GRANT SELECT ON auth.users TO anon, authenticated, service_role;
