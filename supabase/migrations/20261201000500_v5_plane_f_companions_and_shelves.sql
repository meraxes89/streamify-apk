-- ============================================================================
-- Sync v5 Plane F: Companion Read-Models, Admin Analytics & Shelves
-- Strictly additive over frozen Planes A-E.
-- Targets: PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil / S11, S22)
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. Active Session Queue Sidecar (Epoch-Locked to Plane C handoff_epoch)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.user_handoff_queue_snapshot (
    user_id          UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    handoff_epoch    BIGINT NOT NULL CHECK (handoff_epoch > 0),
    device_id        TEXT NOT NULL CHECK (char_length(device_id) BETWEEN 1 AND 128),
    queue_track_ids  TEXT[] NOT NULL DEFAULT '{}'::TEXT[]
                         CHECK (cardinality(queue_track_ids) <= 50),
    queue_index      INTEGER NOT NULL DEFAULT 0 CHECK (queue_index >= 0 AND queue_index < 50),
    shuffle_enabled  BOOLEAN NOT NULL DEFAULT false,
    repeat_mode      TEXT NOT NULL DEFAULT 'off'
                         CHECK (repeat_mode IN ('off', 'one', 'all')),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.user_handoff_queue_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_handoff_queue_snapshot FORCE ROW LEVEL SECURITY;
REVOKE ALL ON public.user_handoff_queue_snapshot FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.user_handoff_queue_snapshot TO authenticated;

DROP POLICY IF EXISTS user_handoff_queue_snapshot_select_own ON public.user_handoff_queue_snapshot;
CREATE POLICY user_handoff_queue_snapshot_select_own
    ON public.user_handoff_queue_snapshot
    FOR SELECT TO authenticated
    USING ((SELECT auth.uid()) = user_id);

-- ============================================================================
-- 2. Active Session Handoff RPCs (Delegate State to Plane C)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.update_active_session_v5(
    p_device_id       TEXT,
    p_device_name     TEXT,
    p_platform        TEXT,
    p_is_playing      BOOLEAN,
    p_track_id        TEXT,
    p_position_ms     BIGINT DEFAULT 0,
    p_duration_ms     BIGINT DEFAULT NULL,
    p_playback_rate   REAL DEFAULT 1.0,
    p_source_context  TEXT DEFAULT 'unknown',
    p_source_id       TEXT DEFAULT NULL,
    p_track_title     TEXT DEFAULT NULL,
    p_artist_name     TEXT DEFAULT NULL,
    p_artwork_url     TEXT DEFAULT NULL,
    p_queue_track_ids TEXT[] DEFAULT NULL,
    p_queue_index     INTEGER DEFAULT 0,
    p_shuffle_enabled BOOLEAN DEFAULT false,
    p_repeat_mode     TEXT DEFAULT 'off'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_plane_c_res   JSONB;
    v_handoff_epoch BIGINT;
    v_norm_queue    TEXT[] := '{}'::TEXT[];
    v_safe_index    INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF public._plane_c_is_device_revoked(v_user_id, p_device_id) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
    END IF;

    IF COALESCE(p_is_playing, false) THEN
        v_plane_c_res := public.heartbeat_presence_v2(
            p_device_id      => p_device_id,
            p_device_name    => p_device_name,
            p_platform       => p_platform,
            p_is_playing     => TRUE,
            p_track_id       => p_track_id,
            p_position_ms    => p_position_ms,
            p_duration_ms    => p_duration_ms,
            p_playback_rate  => p_playback_rate,
            p_source_context => p_source_context,
            p_source_id      => p_source_id,
            p_track_title    => p_track_title,
            p_artist_name    => p_artist_name,
            p_artwork_url    => p_artwork_url
        );
    ELSE
        v_plane_c_res := public.stop_presence(
            p_device_id      => p_device_id,
            p_reason         => 'paused',
            p_save_handoff   => TRUE,
            p_track_id       => p_track_id,
            p_position_ms    => p_position_ms,
            p_duration_ms    => p_duration_ms,
            p_source_context => p_source_context,
            p_source_id      => p_source_id,
            p_track_title    => p_track_title,
            p_artist_name    => p_artist_name,
            p_artwork_url    => p_artwork_url
        );
    END IF;

    IF (v_plane_c_res->>'status') <> 'OK' THEN
        RETURN v_plane_c_res;
    END IF;

    SELECT handoff_epoch INTO v_handoff_epoch
    FROM public.user_playback_handoff
    WHERE user_id = v_user_id;

    IF v_handoff_epoch IS NOT NULL AND p_queue_track_ids IS NOT NULL THEN
        SELECT COALESCE(array_agg(n.canonical_id ORDER BY limited.ord), '{}'::TEXT[])
        INTO v_norm_queue
        FROM (
            SELECT raw_id, ord
            FROM unnest(p_queue_track_ids) WITH ORDINALITY AS t(raw_id, ord)
            LIMIT 50
        ) limited
        CROSS JOIN LATERAL public._plane_d_normalize_track_id(limited.raw_id) n;

        v_safe_index := GREATEST(0, LEAST(COALESCE(p_queue_index, 0), GREATEST(cardinality(v_norm_queue) - 1, 0)));

        INSERT INTO public.user_handoff_queue_snapshot (
            user_id, handoff_epoch, device_id, queue_track_ids,
            queue_index, shuffle_enabled, repeat_mode, updated_at
        ) VALUES (
            v_user_id, v_handoff_epoch, btrim(p_device_id), v_norm_queue,
            v_safe_index, COALESCE(p_shuffle_enabled, false),
            CASE WHEN p_repeat_mode IN ('off', 'one', 'all') THEN p_repeat_mode ELSE 'off' END,
            clock_timestamp()
        )
        ON CONFLICT (user_id) DO UPDATE SET
            handoff_epoch   = EXCLUDED.handoff_epoch,
            device_id       = EXCLUDED.device_id,
            queue_track_ids = EXCLUDED.queue_track_ids,
            queue_index     = EXCLUDED.queue_index,
            shuffle_enabled = EXCLUDED.shuffle_enabled,
            repeat_mode     = EXCLUDED.repeat_mode,
            updated_at      = clock_timestamp();
    END IF;

    RETURN v_plane_c_res || jsonb_build_object(
        'handoff_epoch', COALESCE(v_handoff_epoch::TEXT, '0'),
        'queue_saved', (p_queue_track_ids IS NOT NULL AND v_handoff_epoch IS NOT NULL)
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.fetch_active_session_v5(
    p_exclude_device_id TEXT DEFAULT NULL,
    p_include_catalog   BOOLEAN DEFAULT TRUE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id    UUID := auth.uid();
    v_plane_c    JSONB;
    v_handoff    RECORD;
    v_queue      RECORD;
    v_queue_json JSONB := NULL;
    v_catalog    JSONB := jsonb_build_object('tracks', '{}'::jsonb);
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    v_plane_c := public.get_live_presence(p_exclude_device_id => p_exclude_device_id);
    IF (v_plane_c->>'status') <> 'OK' THEN
        RETURN v_plane_c;
    END IF;

    SELECT handoff_epoch, device_id INTO v_handoff
    FROM public.user_playback_handoff
    WHERE user_id = v_user_id;

    IF FOUND THEN
        SELECT queue_track_ids, queue_index, shuffle_enabled, repeat_mode, updated_at
        INTO v_queue
        FROM public.user_handoff_queue_snapshot
        WHERE user_id = v_user_id
          AND handoff_epoch = v_handoff.handoff_epoch;

        IF FOUND THEN
            v_queue_json := jsonb_build_object(
                'handoff_epoch', v_handoff.handoff_epoch::TEXT,
                'track_ids', to_jsonb(v_queue.queue_track_ids),
                'queue_index', v_queue.queue_index,
                'shuffle_enabled', v_queue.shuffle_enabled,
                'repeat_mode', v_queue.repeat_mode,
                'updated_at', v_queue.updated_at
            );

            IF COALESCE(p_include_catalog, TRUE) AND cardinality(v_queue.queue_track_ids) > 0 THEN
                v_catalog := public._plane_d_build_catalog_sidecar(v_queue.queue_track_ids);
            END IF;
        END IF;
    END IF;

    RETURN v_plane_c || jsonb_build_object(
        'queue', v_queue_json,
        'catalog', v_catalog
    );
END;
$$;

-- ============================================================================
-- 3. Admin Analytics & Fleet RPCs (Gated by public.is_admin() -> 42501)
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_admin_dashboard_stats();
CREATE OR REPLACE FUNCTION public.get_admin_dashboard_stats()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_users     RECORD;
    v_plays     RECORD;
    v_catalog   RECORD;
    v_trend_14d JSONB;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'admin privileges required' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_users FROM public.admin_active_users_overview;

    SELECT
        COALESCE(SUM(play_count) FILTER (WHERE listen_date >= (current_date - 1)), 0)          AS plays_24h,
        COALESCE(SUM(listening_seconds) FILTER (WHERE listen_date >= (current_date - 1)), 0)   AS seconds_24h,
        COALESCE(SUM(play_count) FILTER (WHERE listen_date >= (current_date - 7)), 0)          AS plays_7d,
        COALESCE(SUM(listening_seconds) FILTER (WHERE listen_date >= (current_date - 7)), 0)   AS seconds_7d,
        COALESCE(SUM(play_count) FILTER (WHERE listen_date >= (current_date - 30)), 0)         AS plays_30d,
        COALESCE(SUM(listening_seconds) FILTER (WHERE listen_date >= (current_date - 30)), 0)  AS seconds_30d,
        (SELECT COALESCE(SUM(total_plays), 0) FROM public.profiles)                            AS lifetime_plays,
        (SELECT COALESCE(SUM(listening_seconds), 0) FROM public.profiles)                      AS lifetime_seconds
    INTO v_plays
    FROM public.user_listening_daily
    WHERE listen_date >= (current_date - 30);

    SELECT
        COUNT(*)                                                                           AS total_tracks,
        COUNT(*) FILTER (WHERE metadata_authority >= 50 AND COALESCE(duration_sec, 0) > 0) AS verified_tracks,
        COUNT(*) FILTER (WHERE duration_sec IS NULL OR duration_sec <= 0)                  AS zero_duration_tracks,
        ROUND(COALESCE(AVG(metadata_quality), 0), 1)                                       AS avg_quality_score
    INTO v_catalog
    FROM public.track_index;

    WITH days AS (
        SELECT generate_series(current_date - 13, current_date, INTERVAL '1 day')::DATE AS d
    ),
    dau AS (
        SELECT activity_date AS d, COUNT(*)::INT AS active_users
        FROM public.user_daily_activity
        WHERE activity_date >= current_date - 13
        GROUP BY activity_date
    ),
    plays AS (
        SELECT listen_date AS d, SUM(play_count)::BIGINT AS plays, SUM(listening_seconds)::BIGINT AS seconds
        FROM public.user_listening_daily
        WHERE listen_date >= current_date - 13
        GROUP BY listen_date
    )
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'date', days.d,
                'dau', COALESCE(dau.active_users, 0),
                'plays', COALESCE(plays.plays, 0),
                'listening_seconds', COALESCE(plays.seconds, 0)
            )
            ORDER BY days.d ASC
        ),
        '[]'::jsonb
    )
    INTO v_trend_14d
    FROM days
    LEFT JOIN dau ON dau.d = days.d
    LEFT JOIN plays ON plays.d = days.d;

    RETURN jsonb_build_object(
        'computed_at', now(),
        'users', jsonb_build_object(
            'online_5m', v_users.online_users_5m,
            'playing_now', v_users.playing_now_users,
            'dau_24h', v_users.dau_24h,
            'wau_7d', v_users.wau_7d,
            'mau_30d', v_users.mau_30d,
            'stickiness_dau_mau_pct', v_users.stickiness_dau_mau_pct,
            'total_profiles', v_users.total_profiles
        ),
        'devices', jsonb_build_object(
            'online_5m', v_users.online_devices_5m,
            'playing_now', v_users.playing_now_devices,
            'active_24h', v_users.active_devices_24h,
            'total_registered', v_users.total_registered_devices,
            'revoked', v_users.revoked_devices
        ),
        'playback', jsonb_build_object(
            'plays_24h', v_plays.plays_24h,
            'listening_seconds_24h', v_plays.seconds_24h,
            'plays_7d', v_plays.plays_7d,
            'listening_seconds_7d', v_plays.seconds_7d,
            'plays_30d', v_plays.plays_30d,
            'listening_seconds_30d', v_plays.seconds_30d,
            'lifetime_plays', v_plays.lifetime_plays,
            'lifetime_listening_seconds', v_plays.lifetime_seconds
        ),
        'catalog', jsonb_build_object(
            'total_tracks', v_catalog.total_tracks,
            'verified_tracks', v_catalog.verified_tracks,
            'zero_duration_tracks', v_catalog.zero_duration_tracks,
            'avg_quality_score', v_catalog.avg_quality_score
        ),
        'trend_14d', v_trend_14d
    );
END;
$$;

DROP FUNCTION IF EXISTS public.get_admin_edge_compute_stats();
CREATE OR REPLACE FUNCTION public.get_admin_edge_compute_stats()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_plane_a JSONB;
    v_plane_b JSONB;
    v_plane_c JSONB;
    v_plane_d JSONB;
    v_storage JSONB;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'admin privileges required' USING ERRCODE = '42501';
    END IF;

    SELECT jsonb_build_object(
        'changelog_ops_24h', (SELECT COUNT(*) FROM public.sync_changelog_v5 WHERE created_at >= now() - INTERVAL '24 hours'),
        'changelog_total_rows', (SELECT COUNT(*) FROM public.sync_changelog_v5),
        'syncing_users_24h', (SELECT COUNT(DISTINCT user_id) FROM public.sync_changelog_v5 WHERE created_at >= now() - INTERVAL '24 hours'),
        'mutations_applied_24h', (SELECT COUNT(*) FROM public.sync_mutations_applied WHERE applied_at >= now() - INTERVAL '24 hours' AND status = 'applied'),
        'mutations_stale_24h', (SELECT COUNT(*) FROM public.sync_mutations_applied WHERE applied_at >= now() - INTERVAL '24 hours' AND status = 'stale'),
        'users_with_v5_state', (SELECT COUNT(*) FROM public.user_sync_state WHERE head_rev > 0)
    ) INTO v_plane_a;

    SELECT jsonb_build_object(
        'active_checkpoints_24h', (SELECT COUNT(*) FROM public.playback_session_checkpoints WHERE last_checkpoint_at >= now() - INTERVAL '24 hours'),
        'checkpoints_total_rows', (SELECT COUNT(*) FROM public.playback_session_checkpoints),
        'wrapped_snapshots_total', (SELECT COUNT(*) FROM public.user_wrapped_snapshots),
        'listening_partitions', (
            SELECT COUNT(*) FROM pg_inherits ih
            JOIN pg_class p ON p.oid = ih.inhparent
            WHERE p.relname = 'user_listening_history'
        )
    ) INTO v_plane_b;

    SELECT jsonb_build_object(
        'live_presence_leases', (SELECT COUNT(*) FROM public.user_presence WHERE expires_at > now()),
        'live_playing_leases', (SELECT COUNT(*) FROM public.user_presence WHERE is_playing AND expires_at > now()),
        'expired_unswept_presence_rows', (SELECT COUNT(*) FROM public.user_presence WHERE expires_at <= now()),
        'handoff_snapshots_total', (SELECT COUNT(*) FROM public.user_playback_handoff),
        'legacy_unknown_platform_devices', (SELECT COUNT(*) FROM public.user_devices WHERE NOT is_revoked AND platform = 'legacy_unknown')
    ) INTO v_plane_c;

    SELECT jsonb_build_object(
        'queue_queued', (SELECT COUNT(*) FROM public.catalog_enrichment_queue WHERE status = 'queued'),
        'queue_processing', (SELECT COUNT(*) FROM public.catalog_enrichment_queue WHERE status = 'processing'),
        'queue_stale_leases', (SELECT COUNT(*) FROM public.catalog_enrichment_queue WHERE status = 'processing' AND locked_until < now()),
        'queue_dead_letter', (SELECT COUNT(*) FROM public.catalog_enrichment_queue WHERE status = 'dead_letter'),
        'oldest_queued_age_sec', COALESCE(
            (SELECT EXTRACT(EPOCH FROM (now() - MIN(enqueued_at)))::BIGINT
             FROM public.catalog_enrichment_queue WHERE status = 'queued'),
            0
        ),
        'authority_breakdown', COALESCE(
            (SELECT jsonb_object_agg(metadata_authority::TEXT, cnt)
             FROM (SELECT metadata_authority, COUNT(*) AS cnt FROM public.track_index GROUP BY metadata_authority) s),
            '{}'::jsonb
        )
    ) INTO v_plane_d;

    SELECT jsonb_build_object(
        'sync_changelog_v5_bytes', pg_total_relation_size('public.sync_changelog_v5'),
        'user_listening_daily_bytes', pg_total_relation_size('public.user_listening_daily'),
        'user_presence_bytes', pg_total_relation_size('public.user_presence'),
        'track_index_bytes', pg_total_relation_size('public.track_index'),
        'mv_shelf_community_hits_bytes', COALESCE(pg_total_relation_size(to_regclass('public.mv_shelf_community_hits')), 0)
    ) INTO v_storage;

    RETURN jsonb_build_object(
        'computed_at', now(),
        'plane_a_sync', v_plane_a,
        'plane_b_events', v_plane_b,
        'plane_c_presence', v_plane_c,
        'plane_d_catalog', v_plane_d,
        'storage_bytes', v_storage
    );
END;
$$;

DROP FUNCTION IF EXISTS public.get_admin_top_tracks(INT);
CREATE OR REPLACE FUNCTION public.get_admin_top_tracks(
    p_limit       INT DEFAULT 25,
    p_window_days INT DEFAULT 30
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_limit INT := LEAST(GREATEST(COALESCE(p_limit, 25), 1), 100);
    v_days  INT := LEAST(GREATEST(COALESCE(p_window_days, 30), 1), 365);
    v_items JSONB;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'admin privileges required' USING ERRCODE = '42501';
    END IF;

    WITH agg AS (
        SELECT
            COALESCE(n.canonical_id, h.track_id) AS canonical_track_id,
            COUNT(*)::BIGINT                     AS play_count,
            COUNT(DISTINCT h.user_id)::INT       AS unique_listeners,
            COALESCE(SUM(h.listened_duration_sec), 0)::BIGINT AS total_listened_sec,
            MAX(h.played_at)                     AS last_played_at
        FROM public.user_listening_history h
        LEFT JOIN LATERAL public._plane_d_normalize_track_id(h.track_id) n ON TRUE
        WHERE h.played_at >= now() - make_interval(days => v_days)
          AND h.play_counted
        GROUP BY COALESCE(n.canonical_id, h.track_id)
        ORDER BY play_count DESC, unique_listeners DESC
        LIMIT v_limit
    )
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'track_id', a.canonical_track_id,
                'title', COALESCE(t.title, 'Unknown Track'),
                'artist_name', COALESCE(t.artist_name, 'Unknown Artist'),
                'album_name', t.album_name,
                'duration_sec', t.duration_sec,
                'thumb_url', t.thumb_url,
                'metadata_authority', COALESCE(t.metadata_authority, 0),
                'play_count', a.play_count,
                'unique_listeners', a.unique_listeners,
                'total_listened_sec', a.total_listened_sec,
                'last_played_at', a.last_played_at
            )
            ORDER BY a.play_count DESC, a.unique_listeners DESC
        ),
        '[]'::jsonb
    )
    INTO v_items
    FROM agg a
    LEFT JOIN public.track_index t ON t.id = a.canonical_track_id;

    RETURN jsonb_build_object(
        'computed_at', now(),
        'window_days', v_days,
        'items', v_items
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.get_admin_fleet_overview()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_rows JSONB;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'admin privileges required' USING ERRCODE = '42501';
    END IF;

    SELECT COALESCE(
        jsonb_agg(
            to_jsonb(f)
            ORDER BY f.active_devices_30d DESC, f.total_registered_devices DESC
        ),
        '[]'::jsonb
    )
    INTO v_rows
    FROM public.admin_fleet_overview f;

    RETURN jsonb_build_object(
        'computed_at', now(),
        'fleet', v_rows
    );
END;
$$;

-- ============================================================================
-- 4. Materialized Hybrid Home Shelf Read-Models (k >= 3 & Verified Catalog)
-- ============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS public.mv_shelf_community_hits AS
WITH per_user_track AS (
    SELECT
        n.canonical_id AS track_id,
        h.user_id,
        COUNT(*) FILTER (WHERE h.played_at >= now() - INTERVAL '7 days')::INT AS plays_7d,
        COUNT(*)::INT AS plays_30d
    FROM public.user_listening_history h
    CROSS JOIN LATERAL public._plane_d_normalize_track_id(h.track_id) n
    WHERE h.played_at >= now() - INTERVAL '30 days'
      AND h.play_counted
    GROUP BY n.canonical_id, h.user_id
),
ranked AS (
    SELECT
        put.track_id,
        COUNT(*)::INT AS distinct_listeners,
        SUM(put.plays_7d)::BIGINT AS plays_7d,
        SUM(put.plays_30d)::BIGINT AS plays_30d,
        ROUND(SUM(2.0 * ln(1 + put.plays_7d) + 1.0 * ln(1 + put.plays_30d))::NUMERIC, 4) AS velocity_score
    FROM per_user_track put
    GROUP BY put.track_id
    HAVING COUNT(*) >= 3
)
SELECT
    r.track_id,
    r.distinct_listeners,
    r.plays_7d,
    r.plays_30d,
    r.velocity_score,
    t.title,
    t.artist_name,
    t.album_name,
    t.artist_id,
    t.album_id,
    t.duration_sec,
    t.thumb_url,
    t.metadata_authority,
    t.catalog_version,
    now() AS refreshed_at
FROM ranked r
JOIN public.track_index t ON t.id = r.track_id
WHERE t.metadata_authority >= 50
  AND t.enrichment_status = 'enriched'
  AND t.duration_sec IS NOT NULL AND t.duration_sec > 0
  AND t.thumb_url IS NOT NULL AND t.thumb_url ~ '^https://'
  AND NOT public._plane_d_is_placeholder(t.title)
  AND NOT public._plane_d_is_placeholder(t.artist_name)
ORDER BY r.velocity_score DESC, r.distinct_listeners DESC
LIMIT 500;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_shelf_community_hits_pk
    ON public.mv_shelf_community_hits (track_id);

CREATE INDEX IF NOT EXISTS idx_mv_shelf_community_hits_rank
    ON public.mv_shelf_community_hits (velocity_score DESC, distinct_listeners DESC);

CREATE MATERIALIZED VIEW IF NOT EXISTS public.mv_shelf_hourly_community_tracks AS
WITH per_user_hour AS (
    SELECT
        EXTRACT(HOUR FROM (h.played_at AT TIME ZONE 'UTC'))::SMALLINT AS utc_hour,
        n.canonical_id AS track_id,
        h.user_id,
        COUNT(*)::INT AS plays_90d
    FROM public.user_listening_history h
    CROSS JOIN LATERAL public._plane_d_normalize_track_id(h.track_id) n
    WHERE h.played_at >= now() - INTERVAL '90 days'
      AND h.play_counted
    GROUP BY 1, 2, 3
),
hourly_agg AS (
    SELECT
        utc_hour,
        track_id,
        COUNT(*)::INT AS distinct_listeners,
        SUM(plays_90d)::BIGINT AS total_plays,
        ROUND(SUM(ln(1 + plays_90d))::NUMERIC, 4) AS hour_score
    FROM per_user_hour
    GROUP BY utc_hour, track_id
    HAVING COUNT(*) >= 3
),
top_per_hour AS (
    SELECT
        a.*,
        ROW_NUMBER() OVER (PARTITION BY a.utc_hour ORDER BY a.hour_score DESC, a.distinct_listeners DESC) AS rn
    FROM hourly_agg a
    JOIN public.track_index t ON t.id = a.track_id
    WHERE t.metadata_authority >= 50
      AND t.enrichment_status = 'enriched'
      AND t.duration_sec IS NOT NULL AND t.duration_sec > 0
      AND t.thumb_url IS NOT NULL AND t.thumb_url ~ '^https://'
      AND NOT public._plane_d_is_placeholder(t.title)
      AND NOT public._plane_d_is_placeholder(t.artist_name)
)
SELECT
    th.utc_hour,
    th.track_id,
    th.distinct_listeners,
    th.total_plays,
    th.hour_score,
    t.title,
    t.artist_name,
    t.album_name,
    t.duration_sec,
    t.thumb_url,
    t.metadata_authority,
    t.catalog_version,
    now() AS refreshed_at
FROM top_per_hour th
JOIN public.track_index t ON t.id = th.track_id
WHERE th.rn <= 60;

CREATE UNIQUE INDEX IF NOT EXISTS idx_mv_shelf_hourly_pk
    ON public.mv_shelf_hourly_community_tracks (utc_hour, track_id);

REVOKE ALL ON public.mv_shelf_community_hits FROM PUBLIC, anon, authenticated;
REVOKE ALL ON public.mv_shelf_hourly_community_tracks FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.get_hybrid_home_shelves(
    p_tz_offset_min     INT DEFAULT 0,
    p_daypart           TEXT DEFAULT NULL,
    p_limit_per_shelf   INT DEFAULT 20,
    p_if_modified_since TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id        UUID := auth.uid();
    v_tz_min         INT := LEAST(GREATEST(COALESCE(p_tz_offset_min, 0), -720), 840);
    v_limit          INT := LEAST(GREATEST(COALESCE(p_limit_per_shelf, 20), 5), 40);
    v_local_hour     INT;
    v_daypart        TEXT;
    v_local_hours    INT[];
    v_utc_hours      SMALLINT[];
    v_last_refreshed TIMESTAMPTZ;
    v_community      JSONB := '[]'::jsonb;
    v_circadian      JSONB := '[]'::jsonb;
BEGIN
    v_local_hour := EXTRACT(HOUR FROM (now() AT TIME ZONE 'UTC' + make_interval(mins => v_tz_min)))::INT;
    v_daypart := CASE
        WHEN p_daypart IN ('morning', 'afternoon', 'evening', 'late_night') THEN p_daypart
        WHEN v_local_hour BETWEEN 5 AND 10  THEN 'morning'
        WHEN v_local_hour BETWEEN 11 AND 16 THEN 'afternoon'
        WHEN v_local_hour BETWEEN 17 AND 21 THEN 'evening'
        ELSE 'late_night'
    END;

    v_local_hours := CASE v_daypart
        WHEN 'morning'    THEN ARRAY[5,6,7,8,9,10]
        WHEN 'afternoon'  THEN ARRAY[11,12,13,14,15,16]
        WHEN 'evening'    THEN ARRAY[17,18,19,20,21]
        ELSE                   ARRAY[22,23,0,1,2,3,4]
    END;

    SELECT array_agg(DISTINCT (((lh * 60 - v_tz_min) % 1440 + 1440) % 1440 / 60)::SMALLINT)
    INTO v_utc_hours
    FROM unnest(v_local_hours) AS lh;

    SELECT GREATEST(
        COALESCE((SELECT MAX(refreshed_at) FROM public.mv_shelf_community_hits), '-infinity'::timestamptz),
        COALESCE((SELECT MAX(refreshed_at) FROM public.mv_shelf_hourly_community_tracks), '-infinity'::timestamptz),
        CASE WHEN v_user_id IS NOT NULL THEN
            COALESCE((SELECT MAX(played_at) FROM public.user_listening_history WHERE user_id = v_user_id AND played_at >= now() - INTERVAL '90 days'), '-infinity'::timestamptz)
        ELSE '-infinity'::timestamptz END
    ) INTO v_last_refreshed;

    IF p_if_modified_since IS NOT NULL AND v_last_refreshed <= p_if_modified_since THEN
        RETURN jsonb_build_object(
            'status', 'NOT_MODIFIED',
            'daypart', v_daypart,
            'refreshed_at', v_last_refreshed
        );
    END IF;

    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'track_id', s.track_id,
                'title', s.title,
                'artist_name', s.artist_name,
                'album_name', s.album_name,
                'duration_sec', s.duration_sec,
                'thumb_url', s.thumb_url,
                'metadata_authority', s.metadata_authority,
                'catalog_version', s.catalog_version,
                'signal', 'community'
            )
            ORDER BY s.velocity_score DESC, s.distinct_listeners DESC
        ),
        '[]'::jsonb
    )
    INTO v_community
    FROM (
        SELECT * FROM public.mv_shelf_community_hits
        ORDER BY velocity_score DESC, distinct_listeners DESC
        LIMIT v_limit
    ) s;

    WITH personal_circadian AS (
        SELECT
            n.canonical_id AS track_id,
            ROUND((3.0 * ln(1 + COUNT(*)))::NUMERIC, 4) AS score,
            'personal_circadian'::TEXT AS signal
        FROM public.user_listening_history h
        CROSS JOIN LATERAL public._plane_d_normalize_track_id(h.track_id) n
        WHERE v_user_id IS NOT NULL
          AND h.user_id = v_user_id
          AND h.played_at >= now() - INTERVAL '90 days'
          AND h.play_counted
          AND EXTRACT(HOUR FROM (h.played_at AT TIME ZONE 'UTC' + make_interval(mins => v_tz_min)))::INT = ANY(v_local_hours)
        GROUP BY n.canonical_id
        ORDER BY score DESC
        LIMIT (v_limit / 2)
    ),
    global_circadian AS (
        SELECT
            m.track_id,
            SUM(m.hour_score) AS score,
            'community_circadian'::TEXT AS signal
        FROM public.mv_shelf_hourly_community_tracks m
        WHERE m.utc_hour = ANY(v_utc_hours)
        GROUP BY m.track_id
        ORDER BY score DESC
        LIMIT v_limit
    ),
    combined AS (
        SELECT DISTINCT ON (c.track_id)
            c.track_id, c.score, c.signal
        FROM (
            SELECT * FROM personal_circadian
            UNION ALL
            SELECT * FROM global_circadian
        ) c
        ORDER BY c.track_id, (c.signal = 'personal_circadian') DESC, c.score DESC
    )
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'track_id', t.id,
                'title', t.title,
                'artist_name', t.artist_name,
                'album_name', t.album_name,
                'duration_sec', t.duration_sec,
                'thumb_url', t.thumb_url,
                'metadata_authority', t.metadata_authority,
                'catalog_version', t.catalog_version,
                'signal', cb.signal
            )
            ORDER BY (cb.signal = 'personal_circadian') DESC, cb.score DESC
        ),
        '[]'::jsonb
    )
    INTO v_circadian
    FROM (
        SELECT * FROM combined
        ORDER BY (signal = 'personal_circadian') DESC, score DESC
        LIMIT v_limit
    ) cb
    JOIN public.track_index t ON t.id = cb.track_id
    WHERE t.metadata_authority >= 50
      AND t.enrichment_status = 'enriched'
      AND t.duration_sec IS NOT NULL AND t.duration_sec > 0
      AND t.thumb_url IS NOT NULL AND t.thumb_url ~ '^https://'
      AND NOT public._plane_d_is_placeholder(t.title)
      AND NOT public._plane_d_is_placeholder(t.artist_name);

    RETURN jsonb_build_object(
        'status', 'OK',
        'daypart', v_daypart,
        'refreshed_at', v_last_refreshed,
        'ttl_sec', 900,
        'shelves', jsonb_build_object(
            'community_hits', v_community,
            'circadian_daypart', v_circadian
        )
    );
END;
$$;

-- ============================================================================
-- 5. RPC Privilege Hardening
-- ============================================================================
REVOKE ALL ON FUNCTION public.update_active_session_v5(TEXT,TEXT,TEXT,BOOLEAN,TEXT,BIGINT,BIGINT,REAL,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT[],INTEGER,BOOLEAN,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.update_active_session_v5(TEXT,TEXT,TEXT,BOOLEAN,TEXT,BIGINT,BIGINT,REAL,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT[],INTEGER,BOOLEAN,TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.fetch_active_session_v5(TEXT,BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_active_session_v5(TEXT,BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.get_hybrid_home_shelves(INT,TEXT,INT,TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_hybrid_home_shelves(INT,TEXT,INT,TIMESTAMPTZ) TO anon, authenticated;

REVOKE ALL ON FUNCTION public.get_admin_dashboard_stats() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_admin_dashboard_stats() TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_admin_edge_compute_stats() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_admin_edge_compute_stats() TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_admin_top_tracks(INT,INT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_admin_top_tracks(INT,INT) TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.get_admin_fleet_overview() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_admin_fleet_overview() TO authenticated, service_role;

COMMIT;
