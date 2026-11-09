-- ============================================================================
-- rls_test.sql — SEC-10 RLS hardening regression suite (the SEC-10 gate).
--
-- RUN AGAINST A THROWAWAY DATABASE ONLY (see README.md in this folder).
-- Every assertion here FAILS if the pre-SEC-10 permissive policies are
-- restored — that is the point: `tracks` upsert WITH CHECK (TRUE),
-- `listening_sessions` update `... OR TRUE`, history insert `OR TRUE`,
-- anonymous comments, the hardcoded-email admin grant in is_admin() /
-- handle_new_user, and the client-writable is_admin flag.
--
-- Runner (CI applies): 00_test_shim.sql → supabase/schema.sql →
-- 01_grants.sql → this file, all with psql -v ON_ERROR_STOP=1.
-- ============================================================================

-- ─── Fixture identities (fixed UUIDs so failures are reproducible) ────────
-- alice  11111111-1111-4111-8111-111111111111   regular user
-- bob    22222222-2222-4222-8222-222222222222   regular user, jam participant
-- admin  33333333-3333-4333-8333-333333333333   is_admin = TRUE ("Sireen fan" by name!)
-- legacy 44444444-4444-4444-8444-444444444444   sireenyadav@gmail.com, is_admin = FALSE
-- carol  55555555-5555-4555-8555-555555555555   stranger (no session membership)
-- newbie 66666666-6666-4666-8666-666666666666   fresh signup (handle_new_user test)

INSERT INTO auth.users (id, email) VALUES
    ('11111111-1111-4111-8111-111111111111', 'alice@example.com'),
    ('22222222-2222-4222-8222-222222222222', 'bob@example.com'),
    ('33333333-3333-4333-8333-333333333333', 'admin@example.com'),
    ('44444444-4444-4444-8444-444444444444', 'sireenyadav@gmail.com'),
    ('55555555-5555-4555-8555-555555555555', 'carol@example.com');

INSERT INTO public.profiles (id, email, display_name, is_admin) VALUES
    ('11111111-1111-4111-8111-111111111111', 'alice@example.com', 'Alice', FALSE),
    ('22222222-2222-4222-8222-222222222222', 'bob@example.com', 'Bob', FALSE),
    -- display name says "Sireen fan", flag says admin → MUST be admin.
    ('33333333-3333-4333-8333-333333333333', 'admin@example.com', 'Sireen fan', TRUE),
    -- the pre-SEC-10 hardcoded maintainer email, flag OFF → MUST NOT be admin.
    ('44444444-4444-4444-8444-444444444444', 'sireenyadav@gmail.com', 'Sireen', FALSE),
    ('55555555-5555-4555-8555-555555555555', 'carol@example.com', 'Carol', FALSE);

INSERT INTO public.tracks (id, title, artist, created_by) VALUES
    ('seed-track-1', 'Seeded Track', 'Seeded Artist', NULL);

INSERT INTO public.listening_sessions (id, host_user_id, session_code, participant_ids)
VALUES ('aaaaaaaa-0000-4000-8000-000000000001',
        '11111111-1111-4111-8111-111111111111',
        'STRM01',
        ARRAY['22222222-2222-4222-8222-222222222222'::uuid]);

-- ══════════════════════════════════════════════════════════════════════════
-- 1. Tracks: anonymous ingest is DEAD (old: INSERT ... WITH CHECK (TRUE))
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE anon;
SET LOCAL request.jwt.claims = '{"sub":null,"role":"anon"}';
DO $$
BEGIN
    BEGIN
        INSERT INTO public.tracks (id, title, artist) VALUES ('anon-poison', 'x', 'y');
        RAISE EXCEPTION 'SEC-10 REGRESSION: anonymous INSERT into tracks succeeded (old WITH CHECK (TRUE) policy)';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL; -- RLS rejection — expected under SEC-10
    END;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 2. Tracks: authenticated ingest works, rate limit caps floods at 60/hour
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
-- honest single-row ingest: allowed
INSERT INTO public.tracks (id, title, artist, created_by)
VALUES ('alice-ok-1', 'Alice Track', 'Alice', '11111111-1111-4111-8111-111111111111');
DO $$
DECLARE i INT;
BEGIN
    FOR i IN 2..60 LOOP
        INSERT INTO public.tracks (id, title, artist, created_by)
        VALUES ('alice-flood-' || i, 'T', 'A', '11111111-1111-4111-8111-111111111111');
    END LOOP;
    BEGIN
        INSERT INTO public.tracks (id, title, artist, created_by)
        VALUES ('alice-flood-61', 'T', 'A', '11111111-1111-4111-8111-111111111111');
        RAISE EXCEPTION 'SEC-10 REGRESSION: 61st track ingest within the hour was allowed (rate limit not enforced)';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL; -- rate-limited — expected
    END;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 3. Tracks: anonymous UPDATE is dead (old: UPDATE ... USING (TRUE))
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE anon;
SET LOCAL request.jwt.claims = '{"sub":null,"role":"anon"}';
UPDATE public.tracks SET lyrics = 'POISONED' WHERE id = 'seed-track-1';
DO $$
DECLARE got TEXT;
BEGIN
    SELECT lyrics INTO got FROM public.tracks WHERE id = 'seed-track-1';
    IF got IS DISTINCT FROM NULL THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: anonymous UPDATE modified a track (old USING (TRUE) policy)';
    END IF;
END $$;
ROLLBACK;

-- authenticated lyrics update still works (the crowd-sourced feature survives)
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
UPDATE public.tracks SET lyrics = 'legit lyrics' WHERE id = 'seed-track-1';
DO $$
DECLARE got TEXT;
BEGIN
    SELECT lyrics INTO got FROM public.tracks WHERE id = 'seed-track-1';
    IF got IS DISTINCT FROM 'legit lyrics' THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: authenticated lyrics UPDATE failed';
    END IF;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 4. Listening sessions: strangers get default-deny (old: `... OR TRUE`)
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
-- bob IS a participant: the feature keeps working
SET LOCAL request.jwt.claims = '{"sub":"22222222-2222-4222-8222-222222222222","role":"authenticated"}';
UPDATE public.listening_sessions SET position_ms = 42
WHERE id = 'aaaaaaaa-0000-4000-8000-000000000001';
DO $$
DECLARE got BIGINT;
BEGIN
    SELECT position_ms INTO got FROM public.listening_sessions
    WHERE id = 'aaaaaaaa-0000-4000-8000-000000000001';
    IF got IS DISTINCT FROM 42 THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: participant UPDATE failed';
    END IF;
END $$;
-- carol is a stranger: silently touches zero rows under SEC-10
SET LOCAL request.jwt.claims = '{"sub":"55555555-5555-4555-8555-555555555555","role":"authenticated"}';
UPDATE public.listening_sessions SET position_ms = 999
WHERE id = 'aaaaaaaa-0000-4000-8000-000000000001';
DO $$
DECLARE got BIGINT;
BEGIN
    SELECT position_ms INTO got FROM public.listening_sessions
    WHERE id = 'aaaaaaaa-0000-4000-8000-000000000001';
    IF got IS DISTINCT FROM 42 THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: stranger modified a session (old OR TRUE policy)';
    END IF;
END $$;
ROLLBACK;

-- anonymous session seeding is dead (old: INSERT ... WITH CHECK (TRUE))
BEGIN;
SET LOCAL ROLE anon;
SET LOCAL request.jwt.claims = '{"sub":null,"role":"anon"}';
DO $$
BEGIN
    BEGIN
        INSERT INTO public.listening_sessions (host_user_id, session_code)
        VALUES (NULL, 'ANON01');
        RAISE EXCEPTION 'SEC-10 REGRESSION: anonymous INSERT into listening_sessions succeeded';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 5. History: cross-user insert is dead (old: `auth.uid() = user_id OR TRUE`)
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
DO $$
BEGIN
    BEGIN
        INSERT INTO public.user_listening_history (user_id, track_id)
        VALUES ('22222222-2222-4222-8222-222222222222', 'seed-track-1');
        RAISE EXCEPTION 'SEC-10 REGRESSION: alice inserted history FOR bob (old OR TRUE policy)';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END $$;
-- own history still records
INSERT INTO public.user_listening_history (user_id, track_id)
VALUES ('11111111-1111-4111-8111-111111111111', 'seed-track-1');
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 6. Comments: anonymous posting is dead (old: INSERT ... WITH CHECK (TRUE))
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE anon;
SET LOCAL request.jwt.claims = '{"sub":null,"role":"anon"}';
DO $$
BEGIN
    BEGIN
        INSERT INTO public.track_comments (track_id, user_id, user_name, timestamp_ms, comment_text)
        VALUES ('seed-track-1', NULL, 'anon', 0, 'spam');
        RAISE EXCEPTION 'SEC-10 REGRESSION: anonymous INSERT into track_comments succeeded';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 7. Admin identity: flag-only, no email, no display-name heuristics
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
-- the OLD hardcoded maintainer email with the flag OFF: NOT admin
SET LOCAL request.jwt.claims = '{"sub":"44444444-4444-4444-8444-444444444444","email":"sireenyadav@gmail.com","role":"authenticated"}';
DO $$
BEGIN
    IF public.is_admin() THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: is_admin() still honors the hardcoded email';
    END IF;
    IF public.get_my_admin_status() THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: get_my_admin_status() honors the hardcoded email';
    END IF;
END $$;
-- "Sireen fan" display name WITH the flag: admin (SEC-09 gate: flag decides)
SET LOCAL request.jwt.claims = '{"sub":"33333333-3333-4333-8333-333333333333","role":"authenticated"}';
DO $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: flagged admin is not recognized';
    END IF;
    IF NOT public.get_my_admin_status() THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: get_my_admin_status() lost the flagged admin';
    END IF;
END $$;
-- regular user: no admin anywhere
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
DO $$
BEGIN
    IF public.is_admin() OR public.get_my_admin_status() THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: regular user counts as admin';
    END IF;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 8. is_admin is server-managed: the client cannot write the flag
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
-- own-profile updates keep working (display name etc.)
UPDATE public.profiles SET display_name = 'Alice 2.0'
WHERE id = '11111111-1111-4111-8111-111111111111';
-- but is_admin in the payload is dead (guard trigger)
DO $$
BEGIN
    BEGIN
        UPDATE public.profiles SET is_admin = TRUE
        WHERE id = '11111111-1111-4111-8111-111111111111';
        RAISE EXCEPTION 'SEC-10 REGRESSION: authenticated user self-promoted via profiles UPDATE payload';
    EXCEPTION WHEN raise_exception OR insufficient_privilege THEN
        NULL;
    END;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 9. Admin RPCs: non-admins (incl. the legacy email account) are denied,
--    the flagged admin manages roles through set_user_admin_role
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}';
DO $$
BEGIN
    BEGIN
        PERFORM public.set_user_admin_role('22222222-2222-4222-8222-222222222222', TRUE);
        RAISE EXCEPTION 'SEC-10 REGRESSION: non-admin called set_user_admin_role';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;
END $$;
SET LOCAL request.jwt.claims = '{"sub":"44444444-4444-4444-8444-444444444444","email":"sireenyadav@gmail.com","role":"authenticated"}';
DO $$
BEGIN
    BEGIN
        PERFORM public.get_admin_dashboard_stats();
        RAISE EXCEPTION 'SEC-10 REGRESSION: hardcoded-email account (flag off) reached admin RPCs';
    EXCEPTION WHEN raise_exception THEN
        NULL;
    END;
END $$;
-- the flagged admin promotes bob through the RPC (guard trigger allows: is_admin())
SET LOCAL request.jwt.claims = '{"sub":"33333333-3333-4333-8333-333333333333","role":"authenticated"}';
SELECT public.set_user_admin_role('22222222-2222-4222-8222-222222222222', TRUE);
SET LOCAL request.jwt.claims = '{"sub":"22222222-2222-4222-8222-222222222222","role":"authenticated"}';
DO $$
BEGIN
    IF NOT public.get_my_admin_status() THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: RPC promotion did not grant the flag';
    END IF;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 10. Sign-up can never mint an admin (old: handle_new_user email grant)
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES ('66666666-6666-4666-8666-666666666666', 'sireenyadav@gmail.com', '{}'::jsonb);
DO $$
DECLARE got BOOLEAN;
BEGIN
    SELECT is_admin INTO got FROM public.profiles
    WHERE id = '66666666-6666-4666-8666-666666666666';
    IF got IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'SEC-10 REGRESSION: handle_new_user granted admin by hardcoded email on signup';
    END IF;
END $$;
ROLLBACK;

-- ══════════════════════════════════════════════════════════════════════════
-- 11. Admin's FOR ALL track access survives (moderation still possible)
-- ══════════════════════════════════════════════════════════════════════════
BEGIN;
SET LOCAL ROLE authenticated;
SET LOCAL request.jwt.claims = '{"sub":"33333333-3333-4333-8333-333333333333","role":"authenticated"}';
UPDATE public.tracks SET lyrics = 'admin curated lyrics' WHERE id = 'seed-track-1';
DO $$
DECLARE got TEXT;
BEGIN
    SELECT lyrics INTO got FROM public.tracks WHERE id = 'seed-track-1';
    IF got IS DISTINCT FROM 'admin curated lyrics' THEN
        RAISE EXCEPTION 'SEC-10 BREAKAGE: admin lost full track access';
    END IF;
END $$;
ROLLBACK;

-- Suite result banner (psql echoes; ON_ERROR_STOP already enforced every step)
SELECT 'SEC-10 RLS hardening suite: ALL CHECKS PASSED' AS result;
