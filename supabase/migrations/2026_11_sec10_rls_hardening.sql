-- ============================================================================
-- 2026_11_sec10_rls_hardening.sql — SEC-10 live-database migration.
--
-- Applies the Horizon-0 Supabase hardening to an EXISTING project:
--   (b) tracks ingest: authenticated + per-user rate limit (60/hour),
--       listening_sessions / user_listening_history / track_comments
--       lose every `OR TRUE` / `WITH CHECK (TRUE)`,
--   (c) is_admin() and handle_new_user() lose the hardcoded email,
--       plus a trigger so no client can write profiles.is_admin,
--   (d) get_my_admin_status() RPC (the client's only admin surface) and
--       SET search_path hardening on every SECURITY DEFINER function.
--
-- Fresh installs get all of this from supabase/schema.sql directly;
-- this file is the delta for projects already running the old schema.
-- Idempotent: safe to re-apply. Reviewed against the schema.sql of the
-- same PR (the "db dump diff" review the SEC-10 gate asks for).
-- ============================================================================

-- (b) ingestion attribution column (nullable: existing rows unattributed)
ALTER TABLE public.tracks
    ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL;

-- (c) is_admin(): flag only — the hardcoded maintainer email is gone.
CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- (c) privilege-escalation guard: the client cannot write the flag.
CREATE OR REPLACE FUNCTION public.guard_is_admin_flag()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_admin IS DISTINCT FROM OLD.is_admin THEN
        IF NOT (public.is_admin() OR current_user IN ('postgres', 'supabase_admin', 'service_role')) THEN
            RAISE EXCEPTION 'is_admin is server-managed (SEC-10): use set_user_admin_role';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS profiles_is_admin_guard ON public.profiles;
CREATE TRIGGER profiles_is_admin_guard
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.guard_is_admin_flag();

-- (c) sign-up can never mint an admin (email grant removed).
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email, display_name, avatar_url, is_admin)
    VALUES (
        NEW.id,
        NEW.email,
        COALESCE(NEW.raw_user_meta_data->>'full_name', NEW.raw_user_meta_data->>'name', split_part(NEW.email, '@', 1)),
        COALESCE(NEW.raw_user_meta_data->>'avatar_url', NEW.raw_user_meta_data->>'picture', ''),
        FALSE
    )
    ON CONFLICT (id) DO UPDATE
    SET email = EXCLUDED.email,
        last_active_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- (d) the client's only admin surface.
CREATE OR REPLACE FUNCTION public.get_my_admin_status()
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$;

-- (b) per-user ingest rate limit.
CREATE OR REPLACE FUNCTION public.track_ingest_allowed()
RETURNS BOOLEAN
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN FALSE;
    END IF;
    RETURN (
        SELECT COUNT(*) FROM public.tracks
        WHERE created_by = auth.uid()
          AND created_at > NOW() - INTERVAL '1 hour'
    ) < 60;
END;
$$;

-- (b) policy replacements.
DROP POLICY IF EXISTS "Users can upsert tracks" ON public.tracks;
DROP POLICY IF EXISTS "Users can update track lyrics" ON public.tracks;
DROP POLICY IF EXISTS "Anyone can view tracks" ON public.tracks;
DROP POLICY IF EXISTS "Authenticated users can ingest tracks (rate-limited)" ON public.tracks;
DROP POLICY IF EXISTS "Authenticated users can update track lyrics (rate-limited)" ON public.tracks;
CREATE POLICY "Anyone can view tracks" ON public.tracks FOR SELECT USING (TRUE);
CREATE POLICY "Authenticated users can ingest tracks (rate-limited)" ON public.tracks
    FOR INSERT WITH CHECK (auth.role() = 'authenticated' AND public.track_ingest_allowed());
CREATE POLICY "Authenticated users can update track lyrics (rate-limited)" ON public.tracks
    FOR UPDATE USING (auth.role() = 'authenticated') WITH CHECK (public.track_ingest_allowed());

DROP POLICY IF EXISTS "Participants can update sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Anyone can insert sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Authenticated users can insert sessions" ON public.listening_sessions;
CREATE POLICY "Participants can update sessions" ON public.listening_sessions
    FOR UPDATE USING (auth.uid() = ANY(participant_ids));
CREATE POLICY "Authenticated users can insert sessions" ON public.listening_sessions
    FOR INSERT WITH CHECK (auth.role() = 'authenticated' AND auth.uid() = host_user_id);

DROP POLICY IF EXISTS "Authenticated users can post comments" ON public.track_comments;
CREATE POLICY "Authenticated users can post comments" ON public.track_comments
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Users can record history" ON public.user_listening_history;
CREATE POLICY "Users can record history" ON public.user_listening_history
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- NOTE (SEC-10a): the service_role key from git history (SEC-01) must be
-- rotated in the Supabase dashboard — no repo change can do that. See
-- SECURITY.md "Secret rotation runbook". Verify afterwards by calling the
-- project's /auth/v1/user with the OLD key: it must 401.
