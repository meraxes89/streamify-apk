-- ============================================================================
-- Sync v5 Plane C: Real-Time Presence, Device Fleet & Active Users
-- Targets: PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil / S11, S19, S22)
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. Evolve user_devices in-place & backfill platform (no NULL platforms)
-- ============================================================================
ALTER TABLE public.user_devices
    ADD COLUMN IF NOT EXISTS device_name          TEXT NOT NULL DEFAULT 'Unknown Device',
    ADD COLUMN IF NOT EXISTS revoked_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_by_device_id TEXT,
    ADD COLUMN IF NOT EXISTS revoke_reason        TEXT;

-- Ensure consistent revocation state before adding check constraint
UPDATE public.user_devices
SET revoked_at = COALESCE(revoked_at, now())
WHERE is_revoked AND revoked_at IS NULL;

UPDATE public.user_devices
SET revoked_at = NULL
WHERE NOT is_revoked AND revoked_at IS NOT NULL;

UPDATE public.user_devices
SET platform = CASE
    WHEN lower(btrim(platform)) IN ('android') THEN 'android'
    WHEN lower(btrim(platform)) IN ('ios', 'iphone', 'ipad') THEN 'ios'
    WHEN lower(btrim(platform)) IN ('desktop', 'jvm', 'macos', 'windows', 'linux') THEN 'desktop'
    WHEN lower(btrim(platform)) IN ('web', 'wasm', 'wasmjs', 'browser') THEN 'web'
    WHEN lower(COALESCE(device_id, '') || ' ' || COALESCE(device_name, '') || ' ' || COALESCE(device_model, '') || ' ' || COALESCE(os_version, ''))
         ~ '(android|pixel|samsung|sm-|oneplus|xiaomi|redmi|poco|vivo|oppo|realme|motorola|nothing)'
        THEN 'android'
    WHEN lower(COALESCE(device_id, '') || ' ' || COALESCE(device_name, '') || ' ' || COALESCE(device_model, '') || ' ' || COALESCE(os_version, ''))
         ~ '(ios|iphone|ipad)'
        THEN 'ios'
    WHEN lower(COALESCE(device_id, '') || ' ' || COALESCE(device_name, '') || ' ' || COALESCE(device_model, '') || ' ' || COALESCE(os_version, ''))
         ~ '(desktop|jvm|macbook|imac|windows|linux|win32|darwin)'
        THEN 'desktop'
    WHEN lower(COALESCE(device_id, '') || ' ' || COALESCE(device_name, '') || ' ' || COALESCE(device_model, '') || ' ' || COALESCE(os_version, ''))
         ~ '(wasm|web|chrome|firefox|safari|edge|browser)'
        THEN 'web'
    ELSE 'legacy_unknown'
END
WHERE platform IS NULL
   OR platform NOT IN ('android', 'ios', 'desktop', 'web');

ALTER TABLE public.user_devices
    ALTER COLUMN platform SET NOT NULL,
    ALTER COLUMN app_version SET DEFAULT 'unknown',
    ALTER COLUMN version_code SET DEFAULT 0,
    ALTER COLUMN build_flavor SET DEFAULT 'prod',
    ALTER COLUMN first_seen_at SET DEFAULT now(),
    ALTER COLUMN last_active_at SET DEFAULT now(),
    ALTER COLUMN is_revoked SET DEFAULT false;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.user_devices'::regclass AND conname = 'chk_user_devices_platform'
    ) THEN
        ALTER TABLE public.user_devices
            ADD CONSTRAINT chk_user_devices_platform
            CHECK (platform IN ('android', 'ios', 'desktop', 'web', 'legacy_unknown'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.user_devices'::regclass AND conname = 'chk_user_devices_revocation_ts'
    ) THEN
        ALTER TABLE public.user_devices
            ADD CONSTRAINT chk_user_devices_revocation_ts
            CHECK ((NOT is_revoked AND revoked_at IS NULL) OR (is_revoked AND revoked_at IS NOT NULL));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_devices_revoked_guard
    ON public.user_devices (user_id, device_id)
    WHERE is_revoked;

CREATE INDEX IF NOT EXISTS idx_user_devices_active_fleet
    ON public.user_devices (last_active_at DESC, platform, app_version)
    WHERE NOT is_revoked;

-- ============================================================================
-- 2. Durable Cross-Device "Continue Listening" Handoff (1 Row Per User)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.user_playback_handoff (
    user_id            UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id          TEXT NOT NULL CHECK (char_length(device_id) BETWEEN 1 AND 128),
    device_name        TEXT NOT NULL DEFAULT 'Unknown Device',
    platform           TEXT NOT NULL CHECK (platform IN ('android', 'ios', 'desktop', 'web')),
    track_id           TEXT NOT NULL CHECK (char_length(track_id) BETWEEN 1 AND 256),
    position_ms        BIGINT NOT NULL DEFAULT 0 CHECK (position_ms >= 0 AND position_ms <= 86400000),
    duration_ms        BIGINT CHECK (duration_ms IS NULL OR (duration_ms >= 0 AND duration_ms <= 86400000)),
    source_context     TEXT NOT NULL DEFAULT 'unknown',
    source_id          TEXT,
    track_title        TEXT,
    artist_name        TEXT,
    artwork_url        TEXT,
    handoff_epoch      BIGINT NOT NULL DEFAULT 1 CHECK (handoff_epoch > 0),
    captured_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Salvage best-known handoff from existing user_presence rows before expiring stale leases
INSERT INTO public.user_playback_handoff (
    user_id, device_id, device_name, platform, track_id,
    position_ms, captured_at, updated_at
)
SELECT DISTINCT ON (p.user_id)
    p.user_id,
    COALESCE(NULLIF(btrim(p.device_id), ''), 'legacy_device'),
    COALESCE(NULLIF(btrim(d.device_name), ''), 'Unknown Device'),
    CASE
        WHEN d.platform IN ('android', 'ios', 'desktop', 'web') THEN d.platform
        ELSE 'android'
    END,
    p.track_id,
    GREATEST(0, LEAST(COALESCE(p.position_ms, 0), 86400000)),
    COALESCE(p.updated_at, now()),
    COALESCE(p.updated_at, now())
FROM public.user_presence p
LEFT JOIN public.user_devices d
  ON d.user_id = p.user_id AND d.device_id = p.device_id
WHERE p.track_id IS NOT NULL AND char_length(btrim(p.track_id)) > 0
ORDER BY p.user_id, p.updated_at DESC
ON CONFLICT (user_id) DO NOTHING;

-- ============================================================================
-- 3. Evolve user_presence with TTL Leases & Immediately Clear Stale Rows
-- ============================================================================
ALTER TABLE public.user_presence
    ADD COLUMN IF NOT EXISTS device_name       TEXT NOT NULL DEFAULT 'Unknown Device',
    ADD COLUMN IF NOT EXISTS platform          TEXT NOT NULL DEFAULT 'android',
    ADD COLUMN IF NOT EXISTS duration_ms       BIGINT,
    ADD COLUMN IF NOT EXISTS playback_rate     REAL NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS volume_pct        SMALLINT,
    ADD COLUMN IF NOT EXISTS track_title       TEXT,
    ADD COLUMN IF NOT EXISTS artist_name       TEXT,
    ADD COLUMN IF NOT EXISTS artwork_url       TEXT,
    ADD COLUMN IF NOT EXISTS state_seq         BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS state_captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS expires_at        TIMESTAMPTZ;

UPDATE public.user_presence
SET
    is_playing        = CASE WHEN updated_at >= now() - INTERVAL '45 seconds' THEN is_playing ELSE false END,
    state_captured_at = COALESCE(updated_at, now()),
    expires_at        = COALESCE(updated_at, now() - INTERVAL '1 hour') + INTERVAL '45 seconds';

ALTER TABLE public.user_presence
    ALTER COLUMN expires_at SET NOT NULL;

DELETE FROM public.user_presence
WHERE expires_at < now() - INTERVAL '1 hour';

CREATE INDEX IF NOT EXISTS idx_user_presence_expires_at
    ON public.user_presence (expires_at);

CREATE INDEX IF NOT EXISTS idx_user_presence_live_playing
    ON public.user_presence (expires_at DESC)
    WHERE is_playing;

-- ============================================================================
-- 4. Durable Daily Active User Ledger (DAU / WAU / MAU)
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.user_daily_activity (
    user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    activity_date  DATE NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    platforms      TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
    PRIMARY KEY (activity_date, user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_activity_user_date
    ON public.user_daily_activity (user_id, activity_date DESC);

INSERT INTO public.user_daily_activity (user_id, activity_date, first_seen_at, last_seen_at, platforms)
SELECT
    p.id,
    (p.last_active_at AT TIME ZONE 'UTC')::DATE,
    p.last_active_at,
    p.last_active_at,
    COALESCE(
        (SELECT array_agg(DISTINCT d.platform)
         FROM public.user_devices d
         WHERE d.user_id = p.id AND d.platform <> 'legacy_unknown'),
        '{}'::TEXT[]
    )
FROM public.profiles p
WHERE p.last_active_at IS NOT NULL
  AND p.last_active_at >= now() - INTERVAL '30 days'
ON CONFLICT (activity_date, user_id) DO NOTHING;

-- ============================================================================
-- 5. Live Presence & Windowed Admin Fleet Views
-- ============================================================================
CREATE OR REPLACE VIEW public.v_live_presence
WITH (security_invoker = true)
AS
SELECT
    p.user_id,
    p.device_id,
    p.device_name,
    p.platform,
    p.is_playing,
    p.track_id,
    p.position_ms AS anchor_position_ms,
    CASE
        WHEN p.is_playing THEN LEAST(
            COALESCE(p.duration_ms, 86400000),
            p.position_ms + GREATEST(
                0,
                (EXTRACT(EPOCH FROM (clock_timestamp() - p.state_captured_at)) * 1000 * p.playback_rate)::BIGINT
            )
        )
        ELSE p.position_ms
    END AS effective_position_ms,
    p.duration_ms,
    p.playback_rate,
    p.volume_pct,
    p.track_title,
    p.artist_name,
    p.artwork_url,
    p.state_seq,
    p.state_captured_at,
    p.expires_at,
    p.updated_at
FROM public.user_presence p
JOIN public.user_devices d
  ON d.user_id = p.user_id AND d.device_id = p.device_id
WHERE p.expires_at > clock_timestamp()
  AND NOT d.is_revoked;

DROP VIEW IF EXISTS public.admin_fleet_overview;
CREATE VIEW public.admin_fleet_overview AS
WITH live_playing AS (
    SELECT user_id, device_id
    FROM public.user_presence
    WHERE is_playing AND expires_at > now()
)
SELECT
    d.app_version,
    d.version_code,
    d.platform,
    d.build_flavor,
    COUNT(*) FILTER (WHERE d.last_active_at >= now() - INTERVAL '5 minutes')  AS online_devices_5m,
    COUNT(lp.device_id)                                                       AS playing_now_devices,
    COUNT(*) FILTER (WHERE d.last_active_at >= now() - INTERVAL '24 hours')   AS active_devices_24h,
    COUNT(*) FILTER (WHERE d.last_active_at >= now() - INTERVAL '7 days')     AS active_devices_7d,
    COUNT(*) FILTER (WHERE d.last_active_at >= now() - INTERVAL '30 days')    AS active_devices_30d,
    COUNT(*)                                                                  AS total_registered_devices,
    COUNT(DISTINCT d.user_id) FILTER (WHERE d.last_active_at >= now() - INTERVAL '5 minutes') AS online_users_5m,
    COUNT(DISTINCT d.user_id) FILTER (WHERE d.last_active_at >= now() - INTERVAL '24 hours')  AS dau_users_24h,
    COUNT(DISTINCT d.user_id) FILTER (WHERE d.last_active_at >= now() - INTERVAL '7 days')    AS wau_users_7d,
    COUNT(DISTINCT d.user_id) FILTER (WHERE d.last_active_at >= now() - INTERVAL '30 days')   AS mau_users_30d,
    COUNT(DISTINCT d.user_id)                                                                 AS distinct_registered_users,
    MAX(d.last_active_at)                                                     AS latest_activity
FROM public.user_devices d
LEFT JOIN live_playing lp
  ON lp.user_id = d.user_id AND lp.device_id = d.device_id
WHERE NOT d.is_revoked
GROUP BY d.app_version, d.version_code, d.platform, d.build_flavor;

CREATE OR REPLACE VIEW public.admin_active_users_overview AS
WITH live_playing_users AS (
    SELECT COUNT(DISTINCT p.user_id) AS playing_now_users,
           COUNT(*)                  AS playing_now_devices
    FROM public.user_presence p
    JOIN public.user_devices d
      ON d.user_id = p.user_id AND d.device_id = p.device_id
    WHERE p.is_playing
      AND p.expires_at > now()
      AND NOT d.is_revoked
),
user_windows AS (
    SELECT
        COUNT(*)                                                                AS total_profiles,
        COUNT(*) FILTER (WHERE last_active_at >= now() - INTERVAL '5 minutes')  AS online_users_5m,
        COUNT(*) FILTER (WHERE last_active_at >= now() - INTERVAL '24 hours')   AS dau_24h,
        COUNT(*) FILTER (WHERE last_active_at >= now() - INTERVAL '7 days')     AS wau_7d,
        COUNT(*) FILTER (WHERE last_active_at >= now() - INTERVAL '30 days')    AS mau_30d
    FROM public.profiles
),
device_windows AS (
    SELECT
        COUNT(*) FILTER (WHERE NOT is_revoked)                                                    AS total_registered_devices,
        COUNT(*) FILTER (WHERE NOT is_revoked AND last_active_at >= now() - INTERVAL '5 minutes') AS online_devices_5m,
        COUNT(*) FILTER (WHERE NOT is_revoked AND last_active_at >= now() - INTERVAL '24 hours')  AS active_devices_24h,
        COUNT(*) FILTER (WHERE is_revoked)                                                        AS revoked_devices
    FROM public.user_devices
)
SELECT
    now() AS computed_at,
    u.online_users_5m,
    lp.playing_now_users,
    lp.playing_now_devices,
    u.dau_24h,
    u.wau_7d,
    u.mau_30d,
    CASE WHEN u.mau_30d > 0 THEN ROUND((u.dau_24h::NUMERIC / u.mau_30d::NUMERIC) * 100, 1) ELSE 0 END AS stickiness_dau_mau_pct,
    u.total_profiles,
    dw.online_devices_5m,
    dw.active_devices_24h,
    dw.total_registered_devices,
    dw.revoked_devices
FROM user_windows u
CROSS JOIN live_playing_users lp
CROSS JOIN device_windows dw;

-- ============================================================================
-- 6. RLS Lockdown on Plane C Tables & Admin Views
-- ============================================================================
REVOKE ALL ON public.admin_fleet_overview FROM PUBLIC, anon, authenticated;
REVOKE ALL ON public.admin_active_users_overview FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.admin_fleet_overview TO service_role;
GRANT SELECT ON public.admin_active_users_overview TO service_role;

DO $$
DECLARE
    v_tbl TEXT;
    v_pol RECORD;
    v_tables TEXT[] := ARRAY[
        'user_presence',
        'user_playback_handoff',
        'user_devices',
        'user_device_version_history',
        'user_daily_activity'
    ];
BEGIN
    FOREACH v_tbl IN ARRAY v_tables LOOP
        FOR v_pol IN
            SELECT policyname FROM pg_policies
            WHERE schemaname = 'public' AND tablename = v_tbl
        LOOP
            EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', v_pol.policyname, v_tbl);
        END LOOP;

        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', v_tbl);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', v_tbl);
        EXECUTE format('REVOKE ALL ON public.%I FROM anon, authenticated', v_tbl);

        IF v_tbl <> 'user_daily_activity' THEN
            EXECUTE format('GRANT SELECT ON public.%I TO authenticated', v_tbl);
            EXECUTE format(
                'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING ((SELECT auth.uid()) = user_id)',
                v_tbl || '_select_own',
                v_tbl
            );
        END IF;
    END LOOP;
END $$;

GRANT SELECT ON public.v_live_presence TO authenticated;

-- ============================================================================
-- 7. Update guard_profiles_last_active_at & Replace Plane C Helpers
-- ============================================================================
CREATE OR REPLACE FUNCTION public.guard_profiles_last_active_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path TO 'public', 'pg_temp'
AS $$
BEGIN
    IF NEW.last_active_at IS DISTINCT FROM OLD.last_active_at THEN
        IF auth.role() = 'authenticated'
           AND COALESCE(current_setting('streamify.trusted_activity_touch', true), '') <> '1' THEN
            NEW.last_active_at := OLD.last_active_at;
        ELSE
            NEW.last_active_at := GREATEST(
                COALESCE(OLD.last_active_at, '-infinity'::timestamptz),
                LEAST(COALESCE(NEW.last_active_at, now()), now())
            );
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public._plane_c_is_device_revoked(
    p_user_id   UUID,
    p_device_id TEXT
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.user_devices
        WHERE user_id = p_user_id
          AND device_id = p_device_id
          AND is_revoked
    );
$$;

CREATE OR REPLACE FUNCTION public._plane_c_touch_activity(
    p_user_id   UUID,
    p_device_id TEXT,
    p_platform  TEXT DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_now       TIMESTAMPTZ := clock_timestamp();
    v_throttle  CONSTANT INTERVAL := INTERVAL '2 minutes';
    v_today_utc DATE := (v_now AT TIME ZONE 'UTC')::DATE;
    v_norm_plat TEXT := CASE
        WHEN p_platform IN ('android', 'ios', 'desktop', 'web') THEN p_platform
        ELSE NULL
    END;
BEGIN
    UPDATE public.user_devices
    SET last_active_at = GREATEST(COALESCE(last_active_at, '-infinity'::timestamptz), v_now),
        last_seen      = GREATEST(COALESCE(last_seen, '-infinity'::timestamptz), v_now)
    WHERE user_id = p_user_id
      AND device_id = p_device_id
      AND NOT is_revoked
      AND (last_active_at IS NULL OR last_active_at < v_now - v_throttle);

    PERFORM set_config('streamify.trusted_activity_touch', '1', true);
    UPDATE public.profiles
    SET last_active_at = GREATEST(COALESCE(last_active_at, '-infinity'::timestamptz), v_now)
    WHERE id = p_user_id
      AND (last_active_at IS NULL OR last_active_at < v_now - v_throttle);

    INSERT INTO public.user_daily_activity (user_id, activity_date, first_seen_at, last_seen_at, platforms)
    VALUES (
        p_user_id,
        v_today_utc,
        v_now,
        v_now,
        CASE WHEN v_norm_plat IS NOT NULL THEN ARRAY[v_norm_plat] ELSE '{}'::TEXT[] END
    )
    ON CONFLICT (activity_date, user_id) DO UPDATE SET
        last_seen_at = EXCLUDED.last_seen_at,
        platforms    = CASE
            WHEN v_norm_plat IS NOT NULL AND NOT (v_norm_plat = ANY(user_daily_activity.platforms))
                THEN array_append(user_daily_activity.platforms, v_norm_plat)
            ELSE user_daily_activity.platforms
        END
    WHERE user_daily_activity.last_seen_at < v_now - INTERVAL '15 minutes'
       OR (v_norm_plat IS NOT NULL AND NOT (v_norm_plat = ANY(user_daily_activity.platforms)));
END;
$$;

-- ============================================================================
-- 8. Plane C RPCs
-- ============================================================================

-- 8.1 heartbeat_presence_v2
CREATE OR REPLACE FUNCTION public.heartbeat_presence_v2(
    p_device_id      TEXT,
    p_device_name    TEXT,
    p_platform       TEXT,
    p_is_playing     BOOLEAN,
    p_track_id       TEXT DEFAULT NULL,
    p_position_ms    BIGINT DEFAULT 0,
    p_duration_ms    BIGINT DEFAULT NULL,
    p_playback_rate  REAL DEFAULT 1.0,
    p_volume_pct     SMALLINT DEFAULT NULL,
    p_lease_ttl_sec  INT DEFAULT 45,
    p_source_context TEXT DEFAULT 'unknown',
    p_source_id      TEXT DEFAULT NULL,
    p_track_title    TEXT DEFAULT NULL,
    p_artist_name    TEXT DEFAULT NULL,
    p_artwork_url    TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id      UUID := auth.uid();
    v_now          TIMESTAMPTZ := clock_timestamp();
    v_ttl          INTERVAL := make_interval(secs => LEAST(GREATEST(COALESCE(p_lease_ttl_sec, 45), 20), 75));
    v_expires_at   TIMESTAMPTZ := v_now + v_ttl;
    v_pos          BIGINT := GREATEST(0, LEAST(COALESCE(p_position_ms, 0), 86400000));
    v_dur          BIGINT := CASE WHEN p_duration_ms IS NOT NULL THEN GREATEST(0, LEAST(p_duration_ms, 86400000)) ELSE NULL END;
    v_rate         REAL := GREATEST(0.0, LEAST(COALESCE(p_playback_rate, 1.0), 4.0));
    v_vol          SMALLINT := CASE WHEN p_volume_pct IS NOT NULL THEN GREATEST(0, LEAST(p_volume_pct, 100)) ELSE NULL END;
    v_state_seq    BIGINT;
    v_others       JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_device_id IS NULL OR char_length(btrim(p_device_id)) NOT BETWEEN 1 AND 128 THEN
        RETURN jsonb_build_object('status', 'INVALID_DEVICE_ID');
    END IF;

    IF p_platform NOT IN ('android', 'ios', 'desktop', 'web') THEN
        RETURN jsonb_build_object('status', 'INVALID_PLATFORM');
    END IF;

    IF public._plane_c_is_device_revoked(v_user_id, p_device_id) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
    END IF;

    INSERT INTO public.user_presence (
        user_id, device_id, device_name, platform, is_playing,
        track_id, position_ms, duration_ms, playback_rate, volume_pct,
        track_title, artist_name, artwork_url,
        state_seq, state_captured_at, expires_at, updated_at
    ) VALUES (
        v_user_id, btrim(p_device_id), left(COALESCE(NULLIF(btrim(p_device_name), ''), 'Unknown Device'), 128),
        p_platform, COALESCE(p_is_playing, false),
        left(p_track_id, 256), v_pos, v_dur, v_rate, v_vol,
        p_track_title, p_artist_name, p_artwork_url,
        1, v_now, v_expires_at, v_now
    )
    ON CONFLICT (user_id, device_id) DO UPDATE SET
        device_name       = EXCLUDED.device_name,
        platform          = EXCLUDED.platform,
        is_playing        = EXCLUDED.is_playing,
        track_id          = EXCLUDED.track_id,
        position_ms       = EXCLUDED.position_ms,
        duration_ms       = EXCLUDED.duration_ms,
        playback_rate     = EXCLUDED.playback_rate,
        volume_pct        = EXCLUDED.volume_pct,
        track_title       = EXCLUDED.track_title,
        artist_name       = EXCLUDED.artist_name,
        artwork_url       = EXCLUDED.artwork_url,
        state_seq         = user_presence.state_seq + 1,
        state_captured_at = v_now,
        expires_at        = v_expires_at,
        updated_at        = v_now
    RETURNING state_seq INTO v_state_seq;

    IF p_track_id IS NOT NULL AND char_length(btrim(p_track_id)) BETWEEN 1 AND 256 THEN
        INSERT INTO public.user_playback_handoff (
            user_id, device_id, device_name, platform, track_id,
            position_ms, duration_ms, source_context, source_id,
            track_title, artist_name, artwork_url, handoff_epoch, captured_at, updated_at
        ) VALUES (
            v_user_id, btrim(p_device_id), left(COALESCE(NULLIF(btrim(p_device_name), ''), 'Unknown Device'), 128),
            p_platform, btrim(p_track_id), v_pos, v_dur,
            COALESCE(NULLIF(btrim(p_source_context), ''), 'unknown'), p_source_id,
            p_track_title, p_artist_name, p_artwork_url, 1, v_now, v_now
        )
        ON CONFLICT (user_id) DO UPDATE SET
            device_id      = EXCLUDED.device_id,
            device_name    = EXCLUDED.device_name,
            platform       = EXCLUDED.platform,
            track_id       = EXCLUDED.track_id,
            position_ms    = EXCLUDED.position_ms,
            duration_ms    = EXCLUDED.duration_ms,
            source_context = EXCLUDED.source_context,
            source_id      = EXCLUDED.source_id,
            track_title    = EXCLUDED.track_title,
            artist_name    = EXCLUDED.artist_name,
            artwork_url    = EXCLUDED.artwork_url,
            handoff_epoch  = CASE
                WHEN user_playback_handoff.device_id IS DISTINCT FROM EXCLUDED.device_id
                  OR user_playback_handoff.track_id IS DISTINCT FROM EXCLUDED.track_id
                  OR user_playback_handoff.source_context IS DISTINCT FROM EXCLUDED.source_context
                  OR user_playback_handoff.source_id IS DISTINCT FROM EXCLUDED.source_id
                THEN user_playback_handoff.handoff_epoch + 1
                ELSE user_playback_handoff.handoff_epoch
            END,
            captured_at    = v_now,
            updated_at     = v_now;
    END IF;

    PERFORM public._plane_c_touch_activity(v_user_id, btrim(p_device_id), p_platform);

    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'device_id', device_id,
                'device_name', device_name,
                'platform', platform,
                'is_playing', is_playing,
                'expires_at', expires_at
            )
            ORDER BY is_playing DESC, updated_at DESC
        ),
        '[]'::jsonb
    )
    INTO v_others
    FROM public.user_presence
    WHERE user_id = v_user_id
      AND device_id <> btrim(p_device_id)
      AND expires_at > v_now;

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'state_seq', v_state_seq::TEXT,
        'expires_at', v_expires_at,
        'other_active_devices', v_others
    );
END;
$$;

-- 8.2 stop_presence
CREATE OR REPLACE FUNCTION public.stop_presence(
    p_device_id      TEXT,
    p_reason         TEXT DEFAULT 'stopped',
    p_save_handoff   BOOLEAN DEFAULT TRUE,
    p_track_id       TEXT DEFAULT NULL,
    p_position_ms    BIGINT DEFAULT 0,
    p_duration_ms    BIGINT DEFAULT NULL,
    p_source_context TEXT DEFAULT 'unknown',
    p_source_id      TEXT DEFAULT NULL,
    p_track_title    TEXT DEFAULT NULL,
    p_artist_name    TEXT DEFAULT NULL,
    p_artwork_url    TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_now           TIMESTAMPTZ := clock_timestamp();
    v_pos           BIGINT := GREATEST(0, LEAST(COALESCE(p_position_ms, 0), 86400000));
    v_dur           BIGINT := CASE WHEN p_duration_ms IS NOT NULL THEN GREATEST(0, LEAST(p_duration_ms, 86400000)) ELSE NULL END;
    v_handoff_saved BOOLEAN := FALSE;
    v_dev           RECORD;
    v_plat          TEXT := 'android';
    v_dname         TEXT := 'Unknown Device';
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_device_id IS NULL OR char_length(btrim(p_device_id)) NOT BETWEEN 1 AND 128 THEN
        RETURN jsonb_build_object('status', 'INVALID_DEVICE_ID');
    END IF;

    UPDATE public.user_presence
    SET is_playing = false,
        expires_at = v_now,
        updated_at = v_now
    WHERE user_id = v_user_id AND device_id = btrim(p_device_id);

    IF COALESCE(p_save_handoff, TRUE) AND p_track_id IS NOT NULL AND char_length(btrim(p_track_id)) BETWEEN 1 AND 256 THEN
        SELECT device_name, platform INTO v_dev
        FROM public.user_devices
        WHERE user_id = v_user_id AND device_id = btrim(p_device_id);

        IF FOUND THEN
            v_dname := COALESCE(NULLIF(btrim(v_dev.device_name), ''), 'Unknown Device');
            IF v_dev.platform IN ('android', 'ios', 'desktop', 'web') THEN
                v_plat := v_dev.platform;
            END IF;
        END IF;

        INSERT INTO public.user_playback_handoff (
            user_id, device_id, device_name, platform, track_id,
            position_ms, duration_ms, source_context, source_id,
            track_title, artist_name, artwork_url, handoff_epoch, captured_at, updated_at
        ) VALUES (
            v_user_id, btrim(p_device_id), v_dname, v_plat, btrim(p_track_id),
            v_pos, v_dur, COALESCE(NULLIF(btrim(p_source_context), ''), 'unknown'), p_source_id,
            p_track_title, p_artist_name, p_artwork_url, 1, v_now, v_now
        )
        ON CONFLICT (user_id) DO UPDATE SET
            device_id      = EXCLUDED.device_id,
            device_name    = EXCLUDED.device_name,
            platform       = EXCLUDED.platform,
            track_id       = EXCLUDED.track_id,
            position_ms    = EXCLUDED.position_ms,
            duration_ms    = EXCLUDED.duration_ms,
            source_context = EXCLUDED.source_context,
            source_id      = EXCLUDED.source_id,
            track_title    = EXCLUDED.track_title,
            artist_name    = EXCLUDED.artist_name,
            artwork_url    = EXCLUDED.artwork_url,
            handoff_epoch  = CASE
                WHEN user_playback_handoff.device_id IS DISTINCT FROM EXCLUDED.device_id
                  OR user_playback_handoff.track_id IS DISTINCT FROM EXCLUDED.track_id
                  OR user_playback_handoff.source_context IS DISTINCT FROM EXCLUDED.source_context
                  OR user_playback_handoff.source_id IS DISTINCT FROM EXCLUDED.source_id
                THEN user_playback_handoff.handoff_epoch + 1
                ELSE user_playback_handoff.handoff_epoch
            END,
            captured_at    = v_now,
            updated_at     = v_now;

        v_handoff_saved := TRUE;
    END IF;

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'handoff_saved', v_handoff_saved
    );
END;
$$;

-- 8.3 get_live_presence
CREATE OR REPLACE FUNCTION public.get_live_presence(
    p_exclude_device_id TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_now     TIMESTAMPTZ := clock_timestamp();
    v_live    JSONB := '[]'::jsonb;
    v_handoff JSONB := NULL;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'device_id', device_id,
            'device_name', device_name,
            'platform', platform,
            'is_playing', is_playing,
            'track_id', track_id,
            'anchor_position_ms', anchor_position_ms,
            'effective_position_ms', effective_position_ms,
            'duration_ms', duration_ms,
            'playback_rate', playback_rate,
            'track_title', track_title,
            'artist_name', artist_name,
            'artwork_url', artwork_url,
            'state_captured_at', state_captured_at,
            'expires_at', expires_at
        ) ORDER BY is_playing DESC, updated_at DESC
    ), '[]'::jsonb)
    INTO v_live
    FROM public.v_live_presence
    WHERE user_id = v_user_id
      AND (p_exclude_device_id IS NULL OR device_id <> p_exclude_device_id);

    SELECT jsonb_build_object(
        'device_id', device_id,
        'device_name', device_name,
        'platform', platform,
        'track_id', track_id,
        'position_ms', position_ms,
        'duration_ms', duration_ms,
        'source_context', source_context,
        'source_id', source_id,
        'track_title', track_title,
        'artist_name', artist_name,
        'artwork_url', artwork_url,
        'handoff_epoch', handoff_epoch::TEXT,
        'captured_at', captured_at
    )
    INTO v_handoff
    FROM public.user_playback_handoff
    WHERE user_id = v_user_id;

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'live_devices', v_live,
        'handoff', v_handoff
    );
END;
$$;

-- 8.4 record_device_session (drop old 9-arg signature, replace with 8-arg v5 contract)
DROP FUNCTION IF EXISTS public.record_device_session(TEXT, TEXT, TEXT, INT, TEXT, TEXT, TEXT, TEXT, TEXT);
CREATE OR REPLACE FUNCTION public.record_device_session(
    p_device_id    TEXT,
    p_device_name  TEXT,
    p_platform     TEXT,
    p_os_version   TEXT DEFAULT NULL,
    p_device_model TEXT DEFAULT NULL,
    p_app_version  TEXT DEFAULT 'unknown',
    p_version_code INT DEFAULT 0,
    p_build_flavor TEXT DEFAULT 'prod'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id  UUID := auth.uid();
    v_now      TIMESTAMPTZ := clock_timestamp();
    v_dev_id   TEXT := btrim(COALESCE(p_device_id, ''));
    v_dname    TEXT := left(COALESCE(NULLIF(btrim(p_device_name), ''), 'Unknown Device'), 128);
    v_app_ver  TEXT := COALESCE(NULLIF(btrim(p_app_version), ''), 'unknown');
    v_ver_code INT := COALESCE(p_version_code, 0);
    v_flavor   TEXT := COALESCE(NULLIF(btrim(p_build_flavor), ''), 'prod');
    v_existing RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF char_length(v_dev_id) NOT BETWEEN 1 AND 128 THEN
        RETURN jsonb_build_object('status', 'INVALID_DEVICE_ID');
    END IF;

    IF p_platform IS NULL OR p_platform NOT IN ('android', 'ios', 'desktop', 'web') THEN
        RETURN jsonb_build_object('status', 'INVALID_PLATFORM');
    END IF;

    SELECT is_revoked, revoked_at, app_version, version_code, build_flavor
    INTO v_existing
    FROM public.user_devices
    WHERE user_id = v_user_id AND device_id = v_dev_id
    FOR UPDATE;

    IF FOUND AND v_existing.is_revoked THEN
        RETURN jsonb_build_object(
            'status', 'DEVICE_REVOKED',
            'revoked_at', v_existing.revoked_at
        );
    END IF;

    IF FOUND AND (
        v_existing.app_version IS DISTINCT FROM v_app_ver
        OR v_existing.version_code IS DISTINCT FROM v_ver_code
    ) THEN
        INSERT INTO public.user_device_version_history (
            user_id, device_id, from_version, from_version_code, to_version, to_version_code, upgraded_at
        ) VALUES (
            v_user_id, v_dev_id, v_existing.app_version, v_existing.version_code, v_app_ver, v_ver_code, v_now
        );
    END IF;

    INSERT INTO public.user_devices (
        user_id, device_id, device_name, platform, os_version, device_model,
        app_version, version_code, build_flavor, first_seen_at, last_active_at, last_seen, is_revoked
    ) VALUES (
        v_user_id, v_dev_id, v_dname, p_platform, p_os_version, p_device_model,
        v_app_ver, v_ver_code, v_flavor, v_now, v_now, v_now, FALSE
    )
    ON CONFLICT (user_id, device_id) DO UPDATE SET
        device_name    = EXCLUDED.device_name,
        platform       = EXCLUDED.platform,
        os_version     = COALESCE(EXCLUDED.os_version, user_devices.os_version),
        device_model   = COALESCE(EXCLUDED.device_model, user_devices.device_model),
        app_version    = EXCLUDED.app_version,
        version_code   = EXCLUDED.version_code,
        build_flavor   = EXCLUDED.build_flavor,
        last_active_at = v_now,
        last_seen      = v_now;

    PERFORM public._plane_c_touch_activity(v_user_id, v_dev_id, p_platform);

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'device_id', v_dev_id,
        'platform', p_platform
    );
END;
$$;

-- 8.5 revoke_device_session (drop old 1-arg signature, replace with 4-arg v5 contract)
DROP FUNCTION IF EXISTS public.revoke_device_session(TEXT);
CREATE OR REPLACE FUNCTION public.revoke_device_session(
    p_target_device_id TEXT,
    p_actor_device_id  TEXT DEFAULT NULL,
    p_reason           TEXT DEFAULT 'user_revoked_from_settings',
    p_allow_self       BOOLEAN DEFAULT FALSE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_now     TIMESTAMPTZ := clock_timestamp();
    v_target  TEXT := btrim(COALESCE(p_target_device_id, ''));
    v_actor   TEXT := NULLIF(btrim(COALESCE(p_actor_device_id, '')), '');
    v_pres_ct INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF v_actor IS NOT NULL AND public._plane_c_is_device_revoked(v_user_id, v_actor) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
    END IF;

    IF v_actor IS NOT NULL AND v_target = v_actor AND NOT COALESCE(p_allow_self, FALSE) THEN
        RETURN jsonb_build_object('status', 'SELF_REVOKE_DENIED');
    END IF;

    UPDATE public.user_devices
    SET is_revoked           = TRUE,
        revoked_at           = COALESCE(revoked_at, v_now),
        revoked_by_device_id = COALESCE(v_actor, revoked_by_device_id),
        revoke_reason        = COALESCE(NULLIF(btrim(p_reason), ''), 'user_revoked_from_settings')
    WHERE user_id = v_user_id
      AND device_id = v_target;

    UPDATE public.user_presence
    SET is_playing = FALSE,
        expires_at = v_now,
        updated_at = v_now
    WHERE user_id = v_user_id
      AND device_id = v_target;
    GET DIAGNOSTICS v_pres_ct = ROW_COUNT;

    RETURN jsonb_build_object(
        'status', 'OK',
        'revoked_device_id', v_target,
        'revoked_at', v_now,
        'presence_terminated', (v_pres_ct > 0)
    );
END;
$$;

-- 8.6 get_my_devices (replaces old signature cleanly)
DROP FUNCTION IF EXISTS public.get_my_devices();
CREATE OR REPLACE FUNCTION public.get_my_devices()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_now     TIMESTAMPTZ := clock_timestamp();
    v_devices JSONB := '[]'::jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'device_id', d.device_id,
            'device_name', d.device_name,
            'platform', d.platform,
            'os_version', d.os_version,
            'device_model', d.device_model,
            'app_version', d.app_version,
            'version_code', d.version_code,
            'build_flavor', d.build_flavor,
            'first_seen_at', d.first_seen_at,
            'last_active_at', d.last_active_at,
            'status', CASE
                WHEN lp.is_playing IS TRUE THEN 'playing'
                WHEN lp.device_id IS NOT NULL OR d.last_active_at >= v_now - INTERVAL '5 minutes' THEN 'online'
                ELSE 'offline'
            END
        ) ORDER BY d.last_active_at DESC
    ), '[]'::jsonb)
    INTO v_devices
    FROM public.user_devices d
    LEFT JOIN public.v_live_presence lp
      ON lp.user_id = d.user_id AND lp.device_id = d.device_id
    WHERE d.user_id = v_user_id
      AND NOT d.is_revoked;

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'devices', v_devices
    );
END;
$$;

-- 8.7 Legacy v4 heartbeat_presence bridge -> delegates to heartbeat_presence_v2 (45s TTL)
DROP FUNCTION IF EXISTS public.heartbeat_presence(TEXT, TEXT, BIGINT, BOOLEAN, JSONB, BIGINT);
CREATE OR REPLACE FUNCTION public.heartbeat_presence(
    p_device_id   TEXT,
    p_track_id    TEXT,
    p_position_ms BIGINT,
    p_is_playing  BOOLEAN,
    p_queue_ids   JSONB DEFAULT NULL,
    p_hlc         BIGINT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_plat    TEXT := 'android';
    v_dname   TEXT := 'Legacy Device';
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    SELECT
        CASE WHEN platform IN ('android', 'ios', 'desktop', 'web') THEN platform ELSE 'android' END,
        COALESCE(NULLIF(btrim(device_name), ''), 'Legacy Device')
    INTO v_plat, v_dname
    FROM public.user_devices
    WHERE user_id = v_user_id AND device_id = p_device_id;

    RETURN public.heartbeat_presence_v2(
        p_device_id   => p_device_id,
        p_device_name => COALESCE(v_dname, 'Legacy Device'),
        p_platform    => COALESCE(v_plat, 'android'),
        p_is_playing  => COALESCE(p_is_playing, false),
        p_track_id    => p_track_id,
        p_position_ms => COALESCE(p_position_ms, 0),
        p_lease_ttl_sec => 45
    );
END;
$$;

-- ============================================================================
-- 9. Lock Down Plane C RPCs
-- ============================================================================
REVOKE ALL ON FUNCTION public.heartbeat_presence_v2(TEXT, TEXT, TEXT, BOOLEAN, TEXT, BIGINT, BIGINT, REAL, SMALLINT, INT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.heartbeat_presence_v2(TEXT, TEXT, TEXT, BOOLEAN, TEXT, BIGINT, BIGINT, REAL, SMALLINT, INT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.stop_presence(TEXT, TEXT, BOOLEAN, TEXT, BIGINT, BIGINT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.stop_presence(TEXT, TEXT, BOOLEAN, TEXT, BIGINT, BIGINT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.get_live_presence(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_live_presence(TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.record_device_session(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_device_session(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT, TEXT) TO authenticated;

REVOKE ALL ON FUNCTION public.revoke_device_session(TEXT, TEXT, TEXT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.revoke_device_session(TEXT, TEXT, TEXT, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.get_my_devices() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_devices() TO authenticated;

COMMIT;
