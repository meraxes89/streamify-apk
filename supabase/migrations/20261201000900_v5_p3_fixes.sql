-- ============================================================================
-- ADR-011 / Sync v5 P3 Fixes
-- P3-1: compute_wrapped_snapshot honest 'md5:' checksum prefix
-- P3-2: _v5_try_parse_timestamptz STABLE volatility
-- ============================================================================

BEGIN;

CREATE OR REPLACE FUNCTION public._v5_try_parse_timestamptz(p_val TEXT)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    IF p_val IS NULL OR btrim(p_val) = '' THEN
        RETURN NULL;
    END IF;
    RETURN p_val::TIMESTAMPTZ;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.compute_wrapped_snapshot(
    p_period_key      TEXT,
    p_force_recompute BOOLEAN DEFAULT FALSE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id      UUID := auth.uid();
    v_now          TIMESTAMPTZ := clock_timestamp();
    v_start        TIMESTAMPTZ;
    v_end          TIMESTAMPTZ;
    v_year         INT;
    v_q            INT;
    v_m            INT;
    v_existing     RECORD;
    v_event_count  INT := 0;
    v_total_sec    BIGINT := 0;
    v_unique_tracks INT := 0;
    v_peak_hour    INT;
    v_top_tracks   JSONB := '[]'::jsonb;
    v_top_artists  JSONB := '[]'::jsonb;
    v_monthly_sec  JSONB := '{}'::jsonb;
    v_summary      JSONB;
    v_checksum     TEXT;
    v_is_final     BOOLEAN := FALSE;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF public._plane_c_is_device_revoked(
        v_user_id,
        NULLIF(btrim(COALESCE(current_setting('request.headers', true)::jsonb->>'x-device-id', '')), '')
    ) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
    END IF;

    IF p_period_key IS NULL OR p_period_key !~ '^([0-9]{4}(-(Q[1-4]|(0[1-9]|1[0-2])))?|all_time)$' THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    IF p_period_key = 'all_time' THEN
        v_start := '-infinity'::timestamptz;
        v_end   := 'infinity'::timestamptz;
    ELSIF p_period_key ~ '^[0-9]{4}$' THEN
        v_year  := p_period_key::INT;
        v_start := make_timestamptz(v_year, 1, 1, 0, 0, 0, 'UTC');
        v_end   := make_timestamptz(v_year + 1, 1, 1, 0, 0, 0, 'UTC');
    ELSIF p_period_key ~ '^[0-9]{4}-Q[1-4]$' THEN
        v_year  := split_part(p_period_key, '-Q', 1)::INT;
        v_q     := split_part(p_period_key, '-Q', 2)::INT;
        v_start := make_timestamptz(v_year, (v_q - 1) * 3 + 1, 1, 0, 0, 0, 'UTC');
        v_end   := v_start + INTERVAL '3 months';
    ELSE
        v_year  := split_part(p_period_key, '-', 1)::INT;
        v_m     := split_part(p_period_key, '-', 2)::INT;
        v_start := make_timestamptz(v_year, v_m, 1, 0, 0, 0, 'UTC');
        v_end   := v_start + INTERVAL '1 month';
    END IF;

    v_is_final := (v_end <= v_now);

    IF NOT COALESCE(p_force_recompute, FALSE) THEN
        SELECT * INTO v_existing
        FROM public.user_wrapped_snapshots
        WHERE user_id = v_user_id AND period_key = p_period_key;

        IF FOUND AND (v_existing.is_finalized OR v_existing.updated_at > v_now - INTERVAL '1 hour') THEN
            RETURN jsonb_build_object(
                'status', 'OK',
                'cached', TRUE,
                'snapshot', jsonb_build_object(
                    'period_key', v_existing.period_key,
                    'schema_version', v_existing.schema_version,
                    'source_event_count', v_existing.source_event_count,
                    'total_listening_sec', v_existing.total_listening_sec::TEXT,
                    'summary_json', v_existing.summary_json,
                    'checksum', v_existing.checksum,
                    'is_finalized', v_existing.is_finalized,
                    'computed_at', v_existing.computed_at,
                    'updated_at', v_existing.updated_at
                )
            );
        END IF;
    END IF;

    SELECT
        COUNT(*)::INT,
        COALESCE(SUM(listened_duration_sec), 0)::BIGINT,
        COUNT(DISTINCT track_id)::INT
    INTO v_event_count, v_total_sec, v_unique_tracks
    FROM public.user_listening_history
    WHERE user_id = v_user_id
      AND play_counted
      AND played_at >= v_start AND played_at < v_end;

    SELECT COALESCE(jsonb_agg(t), '[]'::jsonb)
    INTO v_top_tracks
    FROM (
        SELECT
            track_id,
            COALESCE(MAX(track_title), track_id) AS title,
            MAX(artist_name) AS artist,
            COUNT(*)::INT AS plays,
            COALESCE(SUM(listened_duration_sec), 0)::BIGINT AS seconds
        FROM public.user_listening_history
        WHERE user_id = v_user_id AND play_counted AND played_at >= v_start AND played_at < v_end
        GROUP BY track_id
        ORDER BY plays DESC, seconds DESC
        LIMIT 20
    ) t;

    SELECT COALESCE(jsonb_agg(a), '[]'::jsonb)
    INTO v_top_artists
    FROM (
        SELECT
            COALESCE(NULLIF(btrim(artist_name), ''), 'Unknown Artist') AS name,
            COUNT(*)::INT AS plays,
            COALESCE(SUM(listened_duration_sec), 0)::BIGINT AS seconds
        FROM public.user_listening_history
        WHERE user_id = v_user_id AND play_counted AND played_at >= v_start AND played_at < v_end
        GROUP BY 1
        ORDER BY plays DESC, seconds DESC
        LIMIT 20
    ) a;

    SELECT COALESCE(jsonb_object_agg(ym, sec), '{}'::jsonb)
    INTO v_monthly_sec
    FROM (
        SELECT to_char(listen_date, 'YYYY-MM') AS ym, SUM(listening_seconds)::BIGINT AS sec
        FROM public.user_listening_daily
        WHERE user_id = v_user_id
          AND listen_date >= CASE WHEN v_start = '-infinity'::timestamptz THEN '1970-01-01'::DATE ELSE (v_start AT TIME ZONE 'UTC')::DATE END
          AND listen_date < CASE WHEN v_end = 'infinity'::timestamptz THEN '2999-12-31'::DATE ELSE (v_end AT TIME ZONE 'UTC')::DATE END
        GROUP BY 1
    ) m;

    SELECT EXTRACT(HOUR FROM (played_at AT TIME ZONE 'UTC'))::INT
    INTO v_peak_hour
    FROM public.user_listening_history
    WHERE user_id = v_user_id AND play_counted AND played_at >= v_start AND played_at < v_end
    GROUP BY 1
    ORDER BY COUNT(*) DESC
    LIMIT 1;

    v_summary := jsonb_build_object(
        'top_tracks', v_top_tracks,
        'top_artists', v_top_artists,
        'monthly_seconds', v_monthly_sec,
        'peak_hour_utc', v_peak_hour,
        'unique_tracks', v_unique_tracks
    );
    v_checksum := 'md5:' || md5(v_summary::text);

    INSERT INTO public.user_wrapped_snapshots (
        user_id, period_key, schema_version, source_event_count, total_listening_sec,
        summary_json, checksum, is_finalized, computed_at, updated_at
    ) VALUES (
        v_user_id, p_period_key, 1, v_event_count, v_total_sec,
        v_summary, v_checksum, v_is_final, v_now, v_now
    )
    ON CONFLICT (user_id, period_key) DO UPDATE SET
        source_event_count  = EXCLUDED.source_event_count,
        total_listening_sec = EXCLUDED.total_listening_sec,
        summary_json        = EXCLUDED.summary_json,
        checksum            = EXCLUDED.checksum,
        is_finalized        = EXCLUDED.is_finalized,
        computed_at         = v_now,
        updated_at          = v_now;

    RETURN jsonb_build_object(
        'status', 'OK',
        'cached', FALSE,
        'snapshot', jsonb_build_object(
            'period_key', p_period_key,
            'schema_version', 1,
            'source_event_count', v_event_count,
            'total_listening_sec', v_total_sec::TEXT,
            'summary_json', v_summary,
            'checksum', v_checksum,
            'is_finalized', v_is_final,
            'computed_at', v_now,
            'updated_at', v_now
        )
    );
END;
$$;

COMMIT;
