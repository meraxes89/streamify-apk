-- ============================================================================
-- 00_test_shim.sql — Supabase auth-runtime emulation for LOCAL/CI testing.
-- Applied BEFORE supabase/schema.sql. Never applied to a real project.
--
-- Vanilla Postgres (CI: pgvector/pgvector:pg16) lacks the Supabase
-- auth schema, the anon/authenticated/service_role roles and the JWT
-- plumbing that RLS policies depend on. This shim recreates exactly
-- those pieces so supabase/schema.sql applies unmodified and every
-- policy is exercised by the suite in supabase/sql/tests/rls_test.sql.
--
-- Identity model (mirrors Supabase): the effective user comes from
--   SET ROLE <role>                      — the Postgres role
--   SET request.jwt.claims = '{...}'     — the JWT (sub = user id, role)
-- auth.uid()/auth.jwt()/auth.role() read the claims GUC, exactly like
-- the Supabase SQL functions do.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        CREATE ROLE anon NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        CREATE ROLE authenticated NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        CREATE ROLE service_role NOLOGIN;
    END IF;
END
$$;

CREATE SCHEMA IF NOT EXISTS auth;

-- Stand-in for the Supabase auth.users table (profiles FK + the
-- on_auth_user_created trigger live against it).
CREATE TABLE IF NOT EXISTS auth.users (
    id UUID PRIMARY KEY,
    email TEXT,
    raw_user_meta_data JSONB DEFAULT '{}'::jsonb
);

CREATE OR REPLACE FUNCTION auth.jwt() RETURNS JSONB
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(
        NULLIF(current_setting('request.jwt.claims', true), '')::jsonb,
        '{}'::jsonb
    )
$$;

CREATE OR REPLACE FUNCTION auth.uid() RETURNS UUID
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(auth.jwt() ->> 'sub', '')::uuid
$$;

CREATE OR REPLACE FUNCTION auth.role() RETURNS TEXT
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(NULLIF(auth.jwt() ->> 'role', ''), current_user)
$$;
