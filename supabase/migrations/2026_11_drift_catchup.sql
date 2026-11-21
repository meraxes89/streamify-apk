-- ============================================================================
-- 2026_11_drift_catchup.sql — live-project column drift catch-up.
--
-- Found while reconciling the live backend (project dbgtvzgwixerhnwvhtil,
-- "zephyr4289's Project") against the repo's supabase/ contract on
-- 2026-11-10. The live database predates several schema.sql column
-- additions; CREATE TABLE IF NOT EXISTS cannot retrofit columns onto
-- existing tables, so this file carries the delta for projects already
-- running an older base schema:
--
--   * profiles.bio / favorite_genre / is_private — the profile editor
--     (SupabaseClient.updateProfile) PATCHes these; missing columns made
--     every profile update 400 (PGRST204).
--   * profiles.top_track — BUGFIX. The stats overhaul migration
--     (2026_01_stats_overhaul.sql) UPDATEs profiles.top_track from
--     upsert_user_telemetry() and SupabaseStatsClient SELECTs it, but no
--     schema file ever created it — upsert_user_telemetry() failed at
--     runtime with "column top_track does not exist" on every install,
--     fresh or live. schema.sql now defines it for fresh projects; this
--     delta adds it to existing ones.
--   * listening_sessions.host_clock_timestamp — host wall-clock epoch
--     used by the Jam drift correction (documented in supabase.md).
--   * playlists.likes_count — collaborative playlist like counter.
--
-- Idempotent: safe to re-apply. Fresh installs that build straight from
-- supabase/schema.sql get all of these columns directly and re-running
-- this file is a no-op.
-- ============================================================================

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS bio TEXT DEFAULT 'Music lover on Streamify 🎧',
    ADD COLUMN IF NOT EXISTS favorite_genre TEXT DEFAULT 'All',
    ADD COLUMN IF NOT EXISTS is_private BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS top_track TEXT;

ALTER TABLE public.listening_sessions
    ADD COLUMN IF NOT EXISTS host_clock_timestamp BIGINT DEFAULT 0;

ALTER TABLE public.playlists
    ADD COLUMN IF NOT EXISTS likes_count INT DEFAULT 0;
