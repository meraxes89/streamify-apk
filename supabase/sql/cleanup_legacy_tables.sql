-- ============================================================================
-- cleanup_legacy_tables.sql — Purge legacy dead tables and poisoned track rows
--
-- Executed on 2026-11-24 against live Supabase project dbgtvzgwixerhnwvhtil.
-- 
-- Summary of operations:
--   1. Purge legacy trk_* poisoned tracks and user_likes from old pre-CMP app.
--   2. Drop 12 abandoned/dead tables from speculative RFCs (edge compute,
--      Markov models, graph co-occurrence, hook telemetry, chronotypes).
-- ============================================================================

-- 1. Purge poisoned legacy track hashes (trk_<hash>)
DELETE FROM public.user_likes WHERE track_id LIKE 'trk_%';
DELETE FROM public.tracks WHERE id LIKE 'trk_%';

-- 2. Drop abandoned RFC tables with zero consumers in KMP
DROP TABLE IF EXISTS public.edge_compute_results CASCADE;
DROP TABLE IF EXISTS public.edge_compute_tasks CASCADE;
DROP TABLE IF EXISTS public.edge_node_activity CASCADE;
DROP TABLE IF EXISTS public.track_cooccurrence_graph CASCADE;
DROP TABLE IF EXISTS public.track_markov_2nd CASCADE;
DROP TABLE IF EXISTS public.track_hook_telemetry CASCADE;
DROP TABLE IF EXISTS public.user_device_context CASCADE;
DROP TABLE IF EXISTS public.user_listening_patterns CASCADE;
DROP TABLE IF EXISTS public.user_musical_chronotype CASCADE;
DROP TABLE IF EXISTS public.user_track_plays CASCADE;
DROP TABLE IF EXISTS public.canary_results CASCADE;
DROP TABLE IF EXISTS public.user_play_events CASCADE;

-- 3. Drop legacy listening_sessions and jam_ops (superseded by MetroServer WebSocket relay)
DROP TABLE IF EXISTS public.jam_ops CASCADE;
DROP TABLE IF EXISTS public.listening_sessions CASCADE;
DROP FUNCTION IF EXISTS public.jam_heartbeat CASCADE;
DROP FUNCTION IF EXISTS public.jam_mutation CASCADE;
DROP FUNCTION IF EXISTS public.jam_takeover CASCADE;
DROP FUNCTION IF EXISTS public.jam_touch_presence CASCADE;
DROP FUNCTION IF EXISTS public.terminate_jam_session CASCADE;
DROP FUNCTION IF EXISTS public.get_admin_jam_sessions CASCADE;

-- 4. Drop abandoned track_comments (superseded by Innertube / YouTube Music native comments in KMP)
DROP TABLE IF EXISTS public.track_comments CASCADE;
DROP FUNCTION IF EXISTS public.delete_comment_admin(UUID) CASCADE;
DROP FUNCTION IF EXISTS public.get_admin_recent_comments(INT) CASCADE;

-- 5. Drop abandoned admin_broadcasts (superseded by Ed25519-signed RemoteAnnouncementSource / AnnouncementEngine)
DROP TABLE IF EXISTS public.admin_broadcasts CASCADE;
DROP FUNCTION IF EXISTS public.toggle_admin_broadcast(UUID, BOOLEAN) CASCADE;

-- 6. Drop abandoned friend_connections (unimplemented social placeholder, 0 consumers)
DROP TABLE IF EXISTS public.friend_connections CASCADE;

-- 7. Update get_admin_dashboard_stats to detach from dropped tables
CREATE OR REPLACE FUNCTION public.get_admin_dashboard_stats()
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    result JSONB;
    v_total_users INT;
    v_total_tracks INT;
    v_total_playlists INT;
    v_total_likes INT;
    v_total_plays BIGINT;
    v_dau_24h INT;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    SELECT COUNT(*) INTO v_total_users FROM public.profiles;
    SELECT COUNT(*) INTO v_total_tracks FROM public.tracks;
    SELECT COUNT(*) INTO v_total_playlists FROM public.playlists;
    SELECT COUNT(*) INTO v_total_likes FROM public.user_likes;
    SELECT COALESCE(SUM(total_plays), 0) INTO v_total_plays FROM public.profiles;
    SELECT COUNT(*) INTO v_dau_24h FROM public.profiles WHERE last_active_at > NOW() - INTERVAL '24 hours';

    result := jsonb_build_object(
        'total_users', v_total_users,
        'total_tracks', v_total_tracks,
        'total_playlists', v_total_playlists,
        'active_jam_sessions', 0,
        'total_comments', 0,
        'total_likes', v_total_likes,
        'total_plays', v_total_plays,
        'dau_24h', v_dau_24h,
        'server_status', 'Operational',
        'engine_mode', 'PostgreSQL 15 + pgvector 0.5.1'
    );
    RETURN result;
END;
$function$;
