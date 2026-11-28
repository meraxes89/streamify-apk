-- ============================================================================
-- Sync v5 Plane B: Append-Only Event Ingestion (History, Search & Wrapped)
-- Targets: PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil / S11, S19, S22)
-- ============================================================================

BEGIN;

-- 1. Drop legacy Plane A changelog triggers on Plane B tables immediately
DROP TRIGGER IF EXISTS tg_history_changelog ON public.user_listening_history;
DROP TRIGGER IF EXISTS tg_search_changelog  ON public.user_search_history;
DROP TRIGGER IF EXISTS tg_bump_changelog    ON public.user_listening_history;
DROP TRIGGER IF EXISTS tg_bump_changelog    ON public.user_search_history;

-- 2. Partitioned Listening History (Monthly Range Partitions on played_at)
CREATE TABLE public.user_listening_history_v2 (
    user_id                UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    client_event_id        UUID NOT NULL,
    track_id               TEXT NOT NULL CHECK (char_length(track_id) BETWEEN 1 AND 256),
    played_at              TIMESTAMPTZ NOT NULL,
    listened_duration_sec  INTEGER NOT NULL CHECK (listened_duration_sec >= 0 AND listened_duration_sec <= 14400),
    track_duration_sec     INTEGER CHECK (track_duration_sec IS NULL OR (track_duration_sec > 0 AND track_duration_sec <= 14400)),
    play_counted           BOOLEAN NOT NULL DEFAULT false,
    source_context         TEXT NOT NULL DEFAULT 'unknown'
                               CHECK (char_length(source_context) BETWEEN 1 AND 64),
    source_id              TEXT CHECK (source_id IS NULL OR char_length(source_id) <= 256),
    device_id              TEXT NOT NULL DEFAULT 'unknown'
                               CHECK (char_length(device_id) BETWEEN 1 AND 128),
    track_title            TEXT,
    artist_name            TEXT,
    album_name             TEXT,
    artwork_url            TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, played_at, client_event_id)
) PARTITION BY RANGE (played_at);

CREATE INDEX idx_ulh_v2_user_recent
    ON public.user_listening_history_v2 (user_id, played_at DESC, client_event_id DESC);

CREATE INDEX idx_ulh_v2_user_track_time
    ON public.user_listening_history_v2 (user_id, track_id, played_at DESC)
    WHERE play_counted;

CREATE TABLE public.user_listening_history_default
    PARTITION OF public.user_listening_history_v2 DEFAULT;

-- Pre-create monthly partitions covering [MIN(created_at) .. now() + 3 months]
DO $$
DECLARE
    v_min_month DATE;
    v_max_month DATE := (date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '3 months')::DATE;
    v_cur       DATE;
    v_next      DATE;
    v_name      TEXT;
BEGIN
    SELECT COALESCE(
        date_trunc('month', MIN(created_at) AT TIME ZONE 'UTC')::DATE,
        date_trunc('month', now() AT TIME ZONE 'UTC')::DATE
    )
    INTO v_min_month
    FROM public.user_listening_history;

    v_cur := v_min_month;
    WHILE v_cur <= v_max_month LOOP
        v_next := (v_cur + INTERVAL '1 month')::DATE;
        v_name := format('user_listening_history_y%sm%s', to_char(v_cur, 'YYYY'), to_char(v_cur, 'MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.user_listening_history_v2 FOR VALUES FROM (%L) TO (%L)',
            v_name,
            (v_cur::TIMESTAMP AT TIME ZONE 'UTC'),
            (v_next::TIMESTAMP AT TIME ZONE 'UTC')
        );
        v_cur := v_next;
    END LOOP;
END $$;

-- 3. Hot Active-Session Checkpoint Ledger (30-Day TTL, Unpartitioned O(1) PK)
CREATE TABLE IF NOT EXISTS public.playback_session_checkpoints (
    user_id                UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    client_event_id        UUID NOT NULL,
    played_at              TIMESTAMPTZ NOT NULL,
    listened_duration_sec  INTEGER NOT NULL DEFAULT 0 CHECK (listened_duration_sec >= 0 AND listened_duration_sec <= 14400),
    play_counted           BOOLEAN NOT NULL DEFAULT false,
    last_checkpoint_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, client_event_id)
);

CREATE INDEX IF NOT EXISTS idx_psc_last_checkpoint
    ON public.playback_session_checkpoints (last_checkpoint_at);

-- 4. Durable Daily Rollup Table (Survives 24-Month Raw Partition Retention)
CREATE TABLE IF NOT EXISTS public.user_listening_daily (
    user_id            UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    listen_date        DATE NOT NULL,
    play_count         INTEGER NOT NULL DEFAULT 0 CHECK (play_count >= 0),
    listening_seconds  BIGINT NOT NULL DEFAULT 0 CHECK (listening_seconds >= 0),
    distinct_tracks    INTEGER NOT NULL DEFAULT 0 CHECK (distinct_tracks >= 0),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, listen_date)
);

-- 5. Copy all active live listening history rows (mapping created_at -> played_at, title -> track_title)
ALTER TABLE public.user_listening_history
    ADD COLUMN IF NOT EXISTS modified_by TEXT;

INSERT INTO public.user_listening_history_v2 (
    user_id, client_event_id, track_id, played_at,
    listened_duration_sec, track_duration_sec, play_counted,
    source_context, device_id,
    track_title, artist_name, album_name,
    created_at, updated_at
)
SELECT
    user_id,
    COALESCE(client_event_id, gen_random_uuid()),
    track_id,
    COALESCE(created_at, now()),
    LEAST(GREATEST(COALESCE(listened_duration_sec, 0), 0), 14400),
    NULL,
    TRUE, -- Existing live rows are already counted in legacy profiles
    'legacy',
    COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill'),
    title,
    artist_name,
    album_name,
    COALESCE(created_at, now()),
    COALESCE(created_at, now())
FROM public.user_listening_history
WHERE deleted_at IS NULL
  AND user_id IS NOT NULL
  AND track_id IS NOT NULL
  AND char_length(track_id) BETWEEN 1 AND 256
ON CONFLICT (user_id, played_at, client_event_id) DO NOTHING;

-- Seed 30-day checkpoint ledger
INSERT INTO public.playback_session_checkpoints (
    user_id, client_event_id, played_at, listened_duration_sec, play_counted, last_checkpoint_at
)
SELECT
    user_id, client_event_id, played_at, listened_duration_sec, play_counted, updated_at
FROM public.user_listening_history_v2
WHERE played_at >= now() - INTERVAL '30 days'
ON CONFLICT (user_id, client_event_id) DO NOTHING;

-- Backfill user_listening_daily from migrated rows
INSERT INTO public.user_listening_daily (user_id, listen_date, play_count, listening_seconds, distinct_tracks, updated_at)
SELECT
    user_id,
    (played_at AT TIME ZONE 'UTC')::DATE AS listen_date,
    COUNT(*) FILTER (WHERE play_counted)::INT AS play_count,
    COALESCE(SUM(listened_duration_sec), 0)::BIGINT AS listening_seconds,
    COUNT(DISTINCT track_id)::INT AS distinct_tracks,
    now()
FROM public.user_listening_history_v2
GROUP BY user_id, (played_at AT TIME ZONE 'UTC')::DATE
ON CONFLICT (user_id, listen_date) DO NOTHING;

-- Atomic table swap (retain legacy backup temporarily for safety)
DROP VIEW IF EXISTS public.v_user_listening_daily;
DROP VIEW IF EXISTS public.v_user_graph_summary;
ALTER TABLE public.user_listening_history RENAME TO user_listening_history_legacy_backup;
ALTER TABLE public.user_listening_history_v2 RENAME TO user_listening_history;

-- 6. Search Clear Watermark & Evolve user_search_history in place
CREATE TABLE IF NOT EXISTS public.user_search_state (
    user_id            UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    search_cleared_at  TIMESTAMPTZ NOT NULL DEFAULT '-infinity'::timestamptz,
    clear_epoch        BIGINT NOT NULL DEFAULT 0 CHECK (clear_epoch >= 0),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.user_search_history
    ADD COLUMN IF NOT EXISTS query_norm  TEXT,
    ADD COLUMN IF NOT EXISTS result_type TEXT NOT NULL DEFAULT 'all',
    ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE public.user_search_history
SET
    query_norm = left(lower(btrim(regexp_replace(query, '\s+', ' ', 'g'))), 256),
    updated_at = COALESCE(updated_at, searched_at, now())
WHERE query_norm IS NULL;

DELETE FROM public.user_search_history a
USING public.user_search_history b
WHERE a.user_id = b.user_id
  AND a.query_norm = b.query_norm
  AND (
      COALESCE(a.searched_at, '-infinity'::timestamptz) < COALESCE(b.searched_at, '-infinity'::timestamptz)
      OR (COALESCE(a.searched_at, '-infinity'::timestamptz) = COALESCE(b.searched_at, '-infinity'::timestamptz) AND a.ctid < b.ctid)
  );

ALTER TABLE public.user_search_history
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN query_norm SET NOT NULL,
    ALTER COLUMN searched_at SET NOT NULL;

ALTER TABLE public.user_search_history
    DROP CONSTRAINT IF EXISTS user_search_history_pkey,
    DROP CONSTRAINT IF EXISTS uq_user_query;

ALTER TABLE public.user_search_history
    ADD PRIMARY KEY (user_id, query_norm);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.user_search_history'::regclass
          AND conname = 'chk_ush_query_norm_len'
    ) THEN
        ALTER TABLE public.user_search_history
            ADD CONSTRAINT chk_ush_query_norm_len CHECK (char_length(query_norm) BETWEEN 1 AND 256);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.user_search_history'::regclass
          AND conname = 'chk_ush_result_type'
    ) THEN
        ALTER TABLE public.user_search_history
            ADD CONSTRAINT chk_ush_result_type CHECK (result_type IN ('all', 'track', 'artist', 'album', 'playlist', 'podcast'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ush_user_active_recent
    ON public.user_search_history (user_id, searched_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ush_user_updated
    ON public.user_search_history (user_id, updated_at DESC);

INSERT INTO public.user_search_state (user_id, search_cleared_at, clear_epoch)
SELECT DISTINCT user_id, '-infinity'::timestamptz, 0
FROM public.user_search_history
ON CONFLICT (user_id) DO NOTHING;

-- 7. Cloud Wrapped Snapshots Table
CREATE TABLE IF NOT EXISTS public.user_wrapped_snapshots (
    user_id              UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    period_key           TEXT NOT NULL
                             CHECK (period_key ~ '^([0-9]{4}(-(Q[1-4]|(0[1-9]|1[0-2])))?|all_time)$'),
    schema_version       SMALLINT NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
    source_event_count   INTEGER NOT NULL DEFAULT 0 CHECK (source_event_count >= 0),
    total_listening_sec  BIGINT NOT NULL DEFAULT 0 CHECK (total_listening_sec >= 0),
    summary_json         JSONB NOT NULL
                             CHECK (jsonb_typeof(summary_json) = 'object'
                                    AND octet_length(summary_json::text) <= 131072),
    checksum             TEXT NOT NULL CHECK (char_length(checksum) BETWEEN 8 AND 128),
    is_finalized         BOOLEAN NOT NULL DEFAULT false,
    computed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, period_key)
);

CREATE INDEX IF NOT EXISTS idx_uws_user_updated
    ON public.user_wrapped_snapshots (user_id, updated_at DESC);

-- 8. Compatibility Views (preserving exact live column names + security_invoker)
CREATE OR REPLACE VIEW public.v_user_listening_daily
WITH (security_invoker = true)
AS
SELECT
    user_id,
    listen_date AS day,
    play_count AS plays,
    listening_seconds AS seconds,
    distinct_tracks
FROM public.user_listening_daily;

CREATE OR REPLACE VIEW public.v_user_graph_summary
WITH (security_invoker = true)
AS
SELECT
    p.id AS user_id,
    (SELECT COUNT(*) FROM public.user_likes l WHERE l.user_id = p.id AND NOT l.deleted) AS liked_tracks,
    (SELECT COUNT(*) FROM public.user_item_favorites f WHERE f.user_id = p.id AND NOT f.deleted) AS favorites,
    COALESCE(p.total_plays, 0)::BIGINT AS play_events,
    COALESCE(p.listening_seconds, 0)::BIGINT AS listened_seconds
FROM public.profiles p;

GRANT SELECT ON public.v_user_listening_daily TO authenticated;
GRANT SELECT ON public.v_user_graph_summary TO authenticated;

-- 9. Single-Policy RLS & Write Revocation on Plane B Tables
DO $$
DECLARE
    v_tbl TEXT;
    v_pol RECORD;
    v_tables TEXT[] := ARRAY[
        'user_listening_history',
        'playback_session_checkpoints',
        'user_listening_daily',
        'user_search_history',
        'user_search_state',
        'user_wrapped_snapshots'
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

        IF v_tbl <> 'playback_session_checkpoints' THEN
            EXECUTE format('GRANT SELECT ON public.%I TO authenticated', v_tbl);
            EXECUTE format(
                'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING ((SELECT auth.uid()) = user_id)',
                v_tbl || '_select_own',
                v_tbl
            );
        END IF;
    END LOOP;
END $$;

-- Lock down legacy backup table completely
REVOKE ALL ON public.user_listening_history_legacy_backup FROM PUBLIC, anon, authenticated;

-- ============================================================================
-- 10. Plane B RPCs (with _v5_try_parse_* safe casts & _plane_c_* hooks)
-- ============================================================================

-- 10.1 ingest_playback_events(p_device_id, p_events)
CREATE OR REPLACE FUNCTION public.ingest_playback_events(
    p_device_id TEXT,
    p_events    JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id          UUID := auth.uid();
    v_device_id        TEXT := COALESCE(NULLIF(btrim(p_device_id), ''), 'unknown');
    v_now              TIMESTAMPTZ := clock_timestamp();
    v_ev               JSONB;
    v_event_id_txt     TEXT;
    v_event_id         UUID;
    v_track_id         TEXT;
    v_played_at        TIMESTAMPTZ;
    v_raw_sec_big      BIGINT;
    v_raw_sec          INT;
    v_track_dur_big    BIGINT;
    v_track_dur        INT;
    v_track_cap        INT;
    v_elapsed_cap      INT;
    v_clamped_sec      INT;
    v_qualified        BOOLEAN;
    v_ckpt             RECORD;
    v_found_existing   BOOLEAN;
    v_auth_played_at   TIMESTAMPTZ;
    v_old_sec          INT;
    v_old_counted      BOOLEAN;
    v_new_sec          INT;
    v_new_counted      BOOLEAN;
    v_delta_sec        INT;
    v_delta_plays      INT;
    v_status           TEXT;
    v_listen_date      DATE;
    v_batch_plays      BIGINT := 0;
    v_batch_sec        BIGINT := 0;
    v_final_plays      BIGINT;
    v_final_sec        BIGINT;
    v_results          JSONB := '[]'::jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF public._plane_c_is_device_revoked(v_user_id, v_device_id) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
    END IF;

    IF p_events IS NULL OR jsonb_typeof(p_events) <> 'array' THEN
        RETURN jsonb_build_object('status', 'INVALID_BATCH');
    END IF;

    IF jsonb_array_length(p_events) > 100 THEN
        RETURN jsonb_build_object('status', 'BATCH_TOO_LARGE', 'max_batch_size', 100);
    END IF;

    -- Non-locking profile check (idempotent checkpoint PK serializes per-event updates)
    IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_user_id) THEN
        RETURN jsonb_build_object('status', 'PROFILE_NOT_FOUND');
    END IF;

    CREATE TEMP TABLE IF NOT EXISTS _tmp_daily_deltas (
        listen_date DATE PRIMARY KEY,
        delta_plays INT NOT NULL DEFAULT 0,
        delta_sec   BIGINT NOT NULL DEFAULT 0
    ) ON COMMIT DROP;
    TRUNCATE _tmp_daily_deltas;

    FOR v_ev IN SELECT value FROM jsonb_array_elements(p_events) LOOP
        v_event_id_txt := v_ev->>'client_event_id';
        v_track_id     := v_ev->>'track_id';
        v_played_at    := public._v5_try_parse_timestamptz(v_ev->>'played_at');
        v_raw_sec_big  := public._v5_try_parse_bigint(v_ev->>'listened_duration_sec');

        IF v_event_id_txt IS NULL
           OR v_event_id_txt !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR v_track_id IS NULL OR char_length(v_track_id) NOT BETWEEN 1 AND 256
           OR v_played_at IS NULL
           OR v_raw_sec_big IS NULL OR v_raw_sec_big < 0
        THEN
            v_results := v_results || jsonb_build_object(
                'client_event_id', COALESCE(v_event_id_txt, ''),
                'status', 'rejected',
                'error_code', 'INVALID_PAYLOAD'
            );
            CONTINUE;
        END IF;

        v_event_id      := v_event_id_txt::UUID;
        v_raw_sec       := LEAST(v_raw_sec_big, 14400)::INT;
        v_track_dur_big := public._v5_try_parse_bigint(v_ev->>'track_duration_sec');
        v_track_dur     := CASE
            WHEN v_track_dur_big IS NOT NULL AND v_track_dur_big BETWEEN 1 AND 14400
            THEN v_track_dur_big::INT
            ELSE NULL
        END;

        IF v_played_at > v_now + INTERVAL '5 minutes' OR v_played_at < v_now - INTERVAL '90 days' THEN
            v_results := v_results || jsonb_build_object(
                'client_event_id', v_event_id::TEXT,
                'status', 'rejected',
                'error_code', 'TIMESTAMP_OUT_OF_RANGE'
            );
            CONTINUE;
        END IF;

        -- Lookup existing session in O(1) hot checkpoint ledger
        v_found_existing := FALSE;
        SELECT played_at, listened_duration_sec, play_counted
        INTO v_ckpt
        FROM public.playback_session_checkpoints
        WHERE user_id = v_user_id AND client_event_id = v_event_id;

        IF FOUND THEN
            v_found_existing := TRUE;
            v_auth_played_at := v_ckpt.played_at;
            v_old_sec        := v_ckpt.listened_duration_sec;
            v_old_counted    := v_ckpt.play_counted;
        ELSE
            -- Fallback check in partitioned history for 31..90 day late offline retries
            SELECT played_at, listened_duration_sec, play_counted
            INTO v_ckpt
            FROM public.user_listening_history
            WHERE user_id = v_user_id
              AND client_event_id = v_event_id
              AND played_at BETWEEN v_played_at - INTERVAL '1 day' AND v_played_at + INTERVAL '1 day'
            LIMIT 1;

            IF FOUND THEN
                v_found_existing := TRUE;
                v_auth_played_at := v_ckpt.played_at;
                v_old_sec        := v_ckpt.listened_duration_sec;
                v_old_counted    := v_ckpt.play_counted;
            ELSE
                v_auth_played_at := v_played_at;
                v_old_sec        := 0;
                v_old_counted    := FALSE;
            END IF;
        END IF;

        -- Tamper-resistant duration clamping
        v_track_cap   := CASE WHEN v_track_dur IS NOT NULL THEN LEAST(CEIL(v_track_dur * 1.25)::INT + 15, 14400) ELSE 7200 END;
        v_elapsed_cap := GREATEST(30, LEAST(14400, EXTRACT(EPOCH FROM (v_now - v_auth_played_at))::INT + 300));
        v_clamped_sec := GREATEST(0, LEAST(v_raw_sec, v_track_cap, v_elapsed_cap));

        -- Qualification check (mirroring PlaybackSessionTracker.kt)
        v_new_sec     := GREATEST(v_old_sec, v_clamped_sec);
        v_qualified   := (v_new_sec >= 30) OR (v_track_dur IS NOT NULL AND v_track_dur < 60 AND v_new_sec * 2 >= v_track_dur);
        v_new_counted := v_old_counted OR v_qualified;

        v_delta_sec   := v_new_sec - v_old_sec;
        v_delta_plays := CASE WHEN (NOT v_old_counted) AND v_new_counted THEN 1 ELSE 0 END;

        IF NOT v_found_existing THEN
            INSERT INTO public.user_listening_history (
                user_id, client_event_id, track_id, played_at,
                listened_duration_sec, track_duration_sec, play_counted,
                source_context, source_id, device_id,
                track_title, artist_name, album_name, artwork_url
            ) VALUES (
                v_user_id, v_event_id, v_track_id, v_auth_played_at,
                v_new_sec, v_track_dur, v_new_counted,
                left(COALESCE(NULLIF(btrim(v_ev->>'source_context'), ''), 'unknown'), 64),
                left(v_ev->>'source_id', 256),
                left(v_device_id, 128),
                v_ev->>'track_title', v_ev->>'artist_name', v_ev->>'album_name', v_ev->>'artwork_url'
            )
            ON CONFLICT (user_id, played_at, client_event_id) DO UPDATE SET
                listened_duration_sec = GREATEST(user_listening_history.listened_duration_sec, EXCLUDED.listened_duration_sec),
                play_counted          = user_listening_history.play_counted OR EXCLUDED.play_counted,
                updated_at            = v_now;

            v_status := 'inserted';
        ELSIF v_delta_sec > 0 OR v_delta_plays > 0 THEN
            UPDATE public.user_listening_history
            SET
                listened_duration_sec = v_new_sec,
                play_counted          = v_new_counted,
                updated_at            = v_now
            WHERE user_id = v_user_id
              AND played_at = v_auth_played_at
              AND client_event_id = v_event_id;

            v_status := 'updated';
        ELSE
            v_status := 'noop';
        END IF;

        -- Keep O(1) checkpoint ledger synchronized
        INSERT INTO public.playback_session_checkpoints (
            user_id, client_event_id, played_at, listened_duration_sec, play_counted, last_checkpoint_at
        ) VALUES (
            v_user_id, v_event_id, v_auth_played_at, v_new_sec, v_new_counted, v_now
        )
        ON CONFLICT (user_id, client_event_id) DO UPDATE SET
            listened_duration_sec = GREATEST(playback_session_checkpoints.listened_duration_sec, EXCLUDED.listened_duration_sec),
            play_counted          = playback_session_checkpoints.play_counted OR EXCLUDED.play_counted,
            last_checkpoint_at    = v_now;

        IF v_delta_sec > 0 OR v_delta_plays > 0 THEN
            v_listen_date := (v_auth_played_at AT TIME ZONE 'UTC')::DATE;
            INSERT INTO _tmp_daily_deltas (listen_date, delta_plays, delta_sec)
            VALUES (v_listen_date, v_delta_plays, v_delta_sec)
            ON CONFLICT (listen_date) DO UPDATE SET
                delta_plays = _tmp_daily_deltas.delta_plays + EXCLUDED.delta_plays,
                delta_sec   = _tmp_daily_deltas.delta_sec + EXCLUDED.delta_sec;

            v_batch_plays := v_batch_plays + v_delta_plays;
            v_batch_sec   := v_batch_sec + v_delta_sec;
        END IF;

        v_results := v_results || jsonb_build_object(
            'client_event_id', v_event_id::TEXT,
            'status', v_status,
            'accepted_duration_sec', v_new_sec,
            'play_counted', v_new_counted,
            'delta_sec', v_delta_sec,
            'delta_plays', v_delta_plays
        );
    END LOOP;

    -- Apply collapsed daily rollup updates
    INSERT INTO public.user_listening_daily (user_id, listen_date, play_count, listening_seconds, updated_at)
    SELECT v_user_id, listen_date, delta_plays, delta_sec, v_now
    FROM _tmp_daily_deltas
    ON CONFLICT (user_id, listen_date) DO UPDATE SET
        play_count        = user_listening_daily.play_count + EXCLUDED.play_count,
        listening_seconds = user_listening_daily.listening_seconds + EXCLUDED.listening_seconds,
        updated_at        = v_now;

    -- Apply collapsed lifetime profile counter increment once per batch (skip lock on zero-delta batches)
    IF v_batch_plays > 0 OR v_batch_sec > 0 THEN
        UPDATE public.profiles
        SET
            total_plays       = COALESCE(total_plays, 0) + v_batch_plays,
            listening_seconds = COALESCE(listening_seconds, 0) + v_batch_sec
        WHERE id = v_user_id
        RETURNING total_plays, listening_seconds INTO v_final_plays, v_final_sec;
    ELSE
        SELECT COALESCE(total_plays, 0), COALESCE(listening_seconds, 0)
        INTO v_final_plays, v_final_sec
        FROM public.profiles
        WHERE id = v_user_id;
    END IF;

    PERFORM public._plane_c_touch_activity(v_user_id, v_device_id);

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time_ms', (EXTRACT(EPOCH FROM v_now) * 1000)::BIGINT::TEXT,
        'profile_totals', jsonb_build_object(
            'total_plays', COALESCE(v_final_plays, 0),
            'listening_seconds', COALESCE(v_final_sec, 0)
        ),
        'results', v_results
    );
END;
$$;

-- 10.2 fetch_listening_history(p_cursor_played_at, p_cursor_event_id, p_limit, p_since)
CREATE OR REPLACE FUNCTION public.fetch_listening_history(
    p_cursor_played_at TIMESTAMPTZ DEFAULT NULL,
    p_cursor_event_id  UUID DEFAULT NULL,
    p_limit            INT DEFAULT 50,
    p_since            TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id  UUID := auth.uid();
    v_limit    INT := LEAST(GREATEST(COALESCE(p_limit, 50), 1), 100);
    v_items    JSONB := '[]'::jsonb;
    v_has_more BOOLEAN := FALSE;
    v_next_ts  TIMESTAMPTZ;
    v_next_id  UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    WITH page AS (
        SELECT
            client_event_id,
            track_id,
            played_at,
            listened_duration_sec,
            track_duration_sec,
            play_counted,
            source_context,
            source_id,
            track_title,
            artist_name,
            album_name,
            artwork_url
        FROM public.user_listening_history
        WHERE user_id = v_user_id
          AND (p_since IS NULL OR updated_at > p_since)
          AND (
              p_cursor_played_at IS NULL
              OR (played_at, client_event_id) < (p_cursor_played_at, COALESCE(p_cursor_event_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::UUID))
          )
        ORDER BY played_at DESC, client_event_id DESC
        LIMIT v_limit + 1
    ),
    trimmed AS (
        SELECT * FROM page LIMIT v_limit
    )
    SELECT
        COALESCE(jsonb_agg(
            jsonb_build_object(
                'client_event_id', client_event_id::TEXT,
                'track_id', track_id,
                'played_at', played_at,
                'listened_duration_sec', listened_duration_sec,
                'track_duration_sec', track_duration_sec,
                'play_counted', play_counted,
                'source_context', source_context,
                'source_id', source_id,
                'track_title', track_title,
                'artist_name', artist_name,
                'album_name', album_name,
                'artwork_url', artwork_url
            )
            ORDER BY played_at DESC, client_event_id DESC
        ), '[]'::jsonb),
        (SELECT COUNT(*) > v_limit FROM page),
        (SELECT played_at FROM trimmed ORDER BY played_at ASC, client_event_id ASC LIMIT 1),
        (SELECT client_event_id FROM trimmed ORDER BY played_at ASC, client_event_id ASC LIMIT 1)
    INTO v_items, v_has_more, v_next_ts, v_next_id
    FROM trimmed;

    RETURN jsonb_build_object(
        'status', 'OK',
        'has_more', COALESCE(v_has_more, FALSE),
        'next_cursor', CASE
            WHEN v_has_more AND v_next_ts IS NOT NULL THEN
                jsonb_build_object('played_at', v_next_ts, 'client_event_id', v_next_id::TEXT)
            ELSE NULL
        END,
        'items', v_items
    );
END;
$$;

-- 10.3 Search History RPCs
CREATE OR REPLACE FUNCTION public.upsert_search_query(
    p_queries JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id      UUID := auth.uid();
    v_now          TIMESTAMPTZ := clock_timestamp();
    v_state        RECORD;
    v_cleared_at   TIMESTAMPTZ := '-infinity'::timestamptz;
    v_clear_epoch  BIGINT := 0;
    v_item         JSONB;
    v_raw_query    TEXT;
    v_query_norm   TEXT;
    v_result_type  TEXT;
    v_searched_at  TIMESTAMPTZ;
    v_applied      INT := 0;
    v_ignored      INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_queries IS NULL OR jsonb_typeof(p_queries) <> 'array' THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    INSERT INTO public.user_search_state (user_id, search_cleared_at, clear_epoch, updated_at)
    VALUES (v_user_id, '-infinity'::timestamptz, 0, v_now)
    ON CONFLICT (user_id) DO NOTHING;

    SELECT search_cleared_at, clear_epoch
    INTO v_state
    FROM public.user_search_state
    WHERE user_id = v_user_id
    FOR UPDATE;

    v_cleared_at  := COALESCE(v_state.search_cleared_at, '-infinity'::timestamptz);
    v_clear_epoch := COALESCE(v_state.clear_epoch, 0);

    FOR v_item IN SELECT value FROM jsonb_array_elements(p_queries) LOOP
        v_raw_query   := btrim(COALESCE(v_item->>'query', ''));
        v_query_norm  := left(lower(btrim(regexp_replace(v_raw_query, '\s+', ' ', 'g'))), 256);
        v_result_type := COALESCE(NULLIF(btrim(v_item->>'result_type'), ''), 'all');
        IF v_result_type NOT IN ('all', 'track', 'artist', 'album', 'playlist', 'podcast') THEN
            v_result_type := 'all';
        END IF;
        v_searched_at := LEAST(
            COALESCE(public._v5_try_parse_timestamptz(v_item->>'searched_at'), v_now),
            v_now
        );

        IF char_length(v_query_norm) < 1 OR char_length(v_raw_query) > 256 THEN
            CONTINUE;
        END IF;

        IF v_searched_at <= v_cleared_at THEN
            v_ignored := v_ignored + 1;
            CONTINUE;
        END IF;

        INSERT INTO public.user_search_history (
            user_id, query_norm, query, result_type, searched_at, deleted_at, updated_at
        ) VALUES (
            v_user_id, v_query_norm, left(v_raw_query, 256), v_result_type, v_searched_at, NULL, v_now
        )
        ON CONFLICT (user_id, query_norm) DO UPDATE SET
            query       = EXCLUDED.query,
            result_type = EXCLUDED.result_type,
            searched_at = GREATEST(user_search_history.searched_at, EXCLUDED.searched_at),
            deleted_at  = CASE
                WHEN user_search_history.deleted_at IS NOT NULL
                     AND user_search_history.deleted_at >= EXCLUDED.searched_at
                THEN user_search_history.deleted_at
                ELSE NULL
            END,
            updated_at  = v_now
        WHERE EXCLUDED.searched_at >= COALESCE(user_search_history.deleted_at, '-infinity'::timestamptz);

        IF FOUND THEN
            v_applied := v_applied + 1;
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'OK',
        'search_cleared_at', v_cleared_at,
        'clear_epoch', v_clear_epoch::TEXT,
        'applied_count', v_applied,
        'ignored_pre_clear_count', v_ignored
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.delete_search_query(
    p_query      TEXT,
    p_deleted_at TIMESTAMPTZ DEFAULT now()
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id    UUID := auth.uid();
    v_now        TIMESTAMPTZ := clock_timestamp();
    v_query_norm TEXT := left(lower(btrim(regexp_replace(COALESCE(p_query, ''), '\s+', ' ', 'g'))), 256);
    v_deleted_at TIMESTAMPTZ := LEAST(COALESCE(p_deleted_at, v_now), v_now);
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF char_length(v_query_norm) < 1 THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    INSERT INTO public.user_search_history (
        user_id, query_norm, query, result_type, searched_at, deleted_at, updated_at
    ) VALUES (
        v_user_id, v_query_norm, left(btrim(p_query), 256), 'all', v_deleted_at, v_deleted_at, v_now
    )
    ON CONFLICT (user_id, query_norm) DO UPDATE SET
        deleted_at = GREATEST(COALESCE(user_search_history.deleted_at, '-infinity'::timestamptz), EXCLUDED.deleted_at),
        updated_at = v_now
    WHERE user_search_history.searched_at <= EXCLUDED.deleted_at;

    RETURN jsonb_build_object(
        'status', 'OK',
        'query_norm', v_query_norm,
        'deleted_at', v_deleted_at
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.clear_search_history(
    p_cleared_at TIMESTAMPTZ DEFAULT now()
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id    UUID := auth.uid();
    v_now        TIMESTAMPTZ := clock_timestamp();
    v_effective  TIMESTAMPTZ := LEAST(COALESCE(p_cleared_at, v_now), v_now);
    v_cleared_at TIMESTAMPTZ;
    v_epoch      BIGINT;
    v_purged     BIGINT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    INSERT INTO public.user_search_state (user_id, search_cleared_at, clear_epoch, updated_at)
    VALUES (v_user_id, v_effective, 1, v_now)
    ON CONFLICT (user_id) DO UPDATE SET
        search_cleared_at = GREATEST(user_search_state.search_cleared_at, EXCLUDED.search_cleared_at),
        clear_epoch       = user_search_state.clear_epoch + 1,
        updated_at        = v_now
    RETURNING search_cleared_at, clear_epoch INTO v_cleared_at, v_epoch;

    DELETE FROM public.user_search_history
    WHERE user_id = v_user_id
      AND searched_at <= v_cleared_at;
    GET DIAGNOSTICS v_purged = ROW_COUNT;

    RETURN jsonb_build_object(
        'status', 'OK',
        'search_cleared_at', v_cleared_at,
        'clear_epoch', v_epoch::TEXT,
        'purged_rows', v_purged
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.fetch_search_history(
    p_since_updated_at  TIMESTAMPTZ DEFAULT NULL,
    p_known_clear_epoch TEXT DEFAULT '0',
    p_limit             INT DEFAULT 100
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id          UUID := auth.uid();
    v_now              TIMESTAMPTZ := clock_timestamp();
    v_limit            INT := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 200);
    v_known_epoch      BIGINT := GREATEST(COALESCE(public._v5_try_parse_bigint(p_known_clear_epoch), 0), 0);
    v_cleared_at       TIMESTAMPTZ := '-infinity'::timestamptz;
    v_clear_epoch      BIGINT := 0;
    v_full_reset       BOOLEAN := FALSE;
    v_active_items     JSONB := '[]'::jsonb;
    v_tombstones       JSONB := '[]'::jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    SELECT search_cleared_at, clear_epoch
    INTO v_cleared_at, v_clear_epoch
    FROM public.user_search_state
    WHERE user_id = v_user_id;

    v_cleared_at  := COALESCE(v_cleared_at, '-infinity'::timestamptz);
    v_clear_epoch := COALESCE(v_clear_epoch, 0);
    v_full_reset  := (p_since_updated_at IS NULL) OR (v_clear_epoch > v_known_epoch);

    IF v_full_reset THEN
        SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
                'query_norm', query_norm,
                'query', query,
                'result_type', result_type,
                'searched_at', searched_at,
                'updated_at', updated_at
            ) ORDER BY searched_at DESC
        ), '[]'::jsonb)
        INTO v_active_items
        FROM (
            SELECT query_norm, query, result_type, searched_at, updated_at
            FROM public.user_search_history
            WHERE user_id = v_user_id
              AND deleted_at IS NULL
              AND searched_at > v_cleared_at
            ORDER BY searched_at DESC
            LIMIT v_limit
        ) sub;
    ELSE
        SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
                'query_norm', query_norm,
                'query', query,
                'result_type', result_type,
                'searched_at', searched_at,
                'updated_at', updated_at
            ) ORDER BY searched_at DESC
        ), '[]'::jsonb)
        INTO v_active_items
        FROM (
            SELECT query_norm, query, result_type, searched_at, updated_at
            FROM public.user_search_history
            WHERE user_id = v_user_id
              AND deleted_at IS NULL
              AND updated_at > p_since_updated_at
            ORDER BY searched_at DESC
            LIMIT v_limit
        ) sub;

        SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
                'query_norm', query_norm,
                'deleted_at', deleted_at
            )
        ), '[]'::jsonb)
        INTO v_tombstones
        FROM (
            SELECT query_norm, deleted_at
            FROM public.user_search_history
            WHERE user_id = v_user_id
              AND deleted_at IS NOT NULL
              AND updated_at > p_since_updated_at
            ORDER BY updated_at DESC
            LIMIT v_limit
        ) sub;
    END IF;

    RETURN jsonb_build_object(
        'status', 'OK',
        'server_time', v_now,
        'search_cleared_at', v_cleared_at,
        'clear_epoch', v_clear_epoch::TEXT,
        'full_reset_required', v_full_reset,
        'active_items', v_active_items,
        'tombstones', v_tombstones
    );
END;
$$;

-- 10.4 Wrapped Snapshot RPCs
CREATE OR REPLACE FUNCTION public.fetch_wrapped_snapshots(
    p_period_keys TEXT[] DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_items   JSONB := '[]'::jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'period_key', period_key,
            'schema_version', schema_version,
            'source_event_count', source_event_count,
            'total_listening_sec', total_listening_sec::TEXT,
            'summary_json', summary_json,
            'checksum', checksum,
            'is_finalized', is_finalized,
            'computed_at', computed_at,
            'updated_at', updated_at
        ) ORDER BY period_key DESC
    ), '[]'::jsonb)
    INTO v_items
    FROM public.user_wrapped_snapshots
    WHERE user_id = v_user_id
      AND (p_period_keys IS NULL OR period_key = ANY(p_period_keys));

    RETURN jsonb_build_object(
        'status', 'OK',
        'snapshots', v_items
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.upsert_wrapped_snapshot(
    p_period_key          TEXT,
    p_schema_version      INT,
    p_source_event_count  INT,
    p_total_listening_sec BIGINT,
    p_checksum            TEXT,
    p_is_finalized        BOOLEAN,
    p_summary_json        JSONB,
    p_force               BOOLEAN DEFAULT FALSE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id    UUID := auth.uid();
    v_now        TIMESTAMPTZ := clock_timestamp();
    v_existing   RECORD;
    v_applied    BOOLEAN := FALSE;
    v_updated_at TIMESTAMPTZ := v_now;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_period_key IS NULL OR p_period_key !~ '^([0-9]{4}(-(Q[1-4]|(0[1-9]|1[0-2])))?|all_time)$'
       OR p_schema_version IS NULL OR p_schema_version < 1
       OR p_source_event_count IS NULL OR p_source_event_count < 0
       OR p_total_listening_sec IS NULL OR p_total_listening_sec < 0
       OR p_checksum IS NULL OR char_length(p_checksum) NOT BETWEEN 8 AND 128
       OR p_summary_json IS NULL OR jsonb_typeof(p_summary_json) <> 'object'
       OR octet_length(p_summary_json::text) > 131072
    THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    SELECT is_finalized, source_event_count, updated_at
    INTO v_existing
    FROM public.user_wrapped_snapshots
    WHERE user_id = v_user_id AND period_key = p_period_key
    FOR UPDATE;

    IF FOUND AND NOT COALESCE(p_force, FALSE) THEN
        IF v_existing.is_finalized OR p_source_event_count < v_existing.source_event_count THEN
            RETURN jsonb_build_object(
                'status', 'OK',
                'applied', FALSE,
                'period_key', p_period_key,
                'updated_at', v_existing.updated_at
            );
        END IF;
    END IF;

    INSERT INTO public.user_wrapped_snapshots (
        user_id, period_key, schema_version, source_event_count, total_listening_sec,
        summary_json, checksum, is_finalized, computed_at, updated_at
    ) VALUES (
        v_user_id, p_period_key, p_schema_version::SMALLINT, p_source_event_count, p_total_listening_sec,
        p_summary_json, p_checksum, COALESCE(p_is_finalized, FALSE), v_now, v_now
    )
    ON CONFLICT (user_id, period_key) DO UPDATE SET
        schema_version      = EXCLUDED.schema_version,
        source_event_count  = EXCLUDED.source_event_count,
        total_listening_sec = EXCLUDED.total_listening_sec,
        summary_json        = EXCLUDED.summary_json,
        checksum            = EXCLUDED.checksum,
        is_finalized        = EXCLUDED.is_finalized,
        computed_at         = v_now,
        updated_at          = v_now
    RETURNING TRUE, updated_at INTO v_applied, v_updated_at;

    RETURN jsonb_build_object(
        'status', 'OK',
        'applied', COALESCE(v_applied, FALSE),
        'period_key', p_period_key,
        'updated_at', v_updated_at
    );
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
    v_checksum := 'sha256:' || md5(v_summary::text);

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

-- 10.5 Partition Maintenance & Retention (service_role / pg_cron)
CREATE OR REPLACE FUNCTION public.maintain_plane_b_partitions(
    p_months_ahead     INT DEFAULT 3,
    p_retention_months INT DEFAULT 24
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_month_start  DATE;
    v_month_end    DATE;
    v_part_name    TEXT;
    v_created      TEXT[] := '{}';
    v_dropped      TEXT[] := '{}';
    v_cutoff_month DATE := (date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => p_retention_months))::DATE;
    v_rec          RECORD;
    v_ckpt_purged  BIGINT := 0;
BEGIN
    FOR i IN 0..GREATEST(p_months_ahead, 0) LOOP
        v_month_start := (date_trunc('month', now() AT TIME ZONE 'UTC') + make_interval(months => i))::DATE;
        v_month_end   := (v_month_start + INTERVAL '1 month')::DATE;
        v_part_name   := format('user_listening_history_y%sm%s', to_char(v_month_start, 'YYYY'), to_char(v_month_start, 'MM'));

        IF to_regclass('public.' || v_part_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE public.%I PARTITION OF public.user_listening_history FOR VALUES FROM (%L) TO (%L)',
                v_part_name,
                (v_month_start::TIMESTAMP AT TIME ZONE 'UTC'),
                (v_month_end::TIMESTAMP AT TIME ZONE 'UTC')
            );
            v_created := array_append(v_created, v_part_name);
        END IF;
    END LOOP;

    DELETE FROM public.playback_session_checkpoints
    WHERE last_checkpoint_at < now() - INTERVAL '30 days';
    GET DIAGNOSTICS v_ckpt_purged = ROW_COUNT;

    DELETE FROM public.user_search_history
    WHERE deleted_at IS NOT NULL AND deleted_at < now() - INTERVAL '90 days';

    FOR v_rec IN
        SELECT c.relname
        FROM pg_inherits ih
        JOIN pg_class c ON c.oid = ih.inhrelid
        JOIN pg_class p ON p.oid = ih.inhparent
        WHERE p.relname = 'user_listening_history'
          AND c.relname ~ '^user_listening_history_y[0-9]{4}m[0-9]{2}$'
    LOOP
        v_month_start := to_date(substring(v_rec.relname FROM 'y([0-9]{4}m[0-9]{2})$'), 'YYYY"m"MM');
        IF v_month_start < v_cutoff_month THEN
            EXECUTE format('ALTER TABLE public.user_listening_history DETACH PARTITION public.%I CONCURRENTLY', v_rec.relname);
            EXECUTE format('DROP TABLE public.%I', v_rec.relname);
            v_dropped := array_append(v_dropped, v_rec.relname);
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'created_partitions', v_created,
        'dropped_partitions', v_dropped,
        'checkpoints_purged', v_ckpt_purged
    );
END;
$$;

-- 11. Lock down Plane B RPCs
REVOKE ALL ON FUNCTION public.ingest_playback_events(TEXT, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ingest_playback_events(TEXT, JSONB) TO authenticated;

REVOKE ALL ON FUNCTION public.fetch_listening_history(TIMESTAMPTZ, UUID, INT, TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_listening_history(TIMESTAMPTZ, UUID, INT, TIMESTAMPTZ) TO authenticated;

REVOKE ALL ON FUNCTION public.upsert_search_query(JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.upsert_search_query(JSONB) TO authenticated;

REVOKE ALL ON FUNCTION public.delete_search_query(TEXT, TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_search_query(TEXT, TIMESTAMPTZ) TO authenticated;

REVOKE ALL ON FUNCTION public.clear_search_history(TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.clear_search_history(TIMESTAMPTZ) TO authenticated;

REVOKE ALL ON FUNCTION public.fetch_search_history(TIMESTAMPTZ, TEXT, INT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_search_history(TIMESTAMPTZ, TEXT, INT) TO authenticated;

REVOKE ALL ON FUNCTION public.fetch_wrapped_snapshots(TEXT[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fetch_wrapped_snapshots(TEXT[]) TO authenticated;

REVOKE ALL ON FUNCTION public.upsert_wrapped_snapshot(TEXT, INT, INT, BIGINT, TEXT, BOOLEAN, JSONB, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.upsert_wrapped_snapshot(TEXT, INT, INT, BIGINT, TEXT, BOOLEAN, JSONB, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.compute_wrapped_snapshot(TEXT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.compute_wrapped_snapshot(TEXT, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.maintain_plane_b_partitions(INT, INT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.maintain_plane_b_partitions(INT, INT) TO service_role;

-- 12. Update Legacy v4 sync_push Shim to route history & search directly to Plane B (zero changelog write)
CREATE OR REPLACE FUNCTION public.sync_push(
    p_ops       JSONB,
    p_device_id TEXT DEFAULT 'legacy_v4'
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id     UUID := auth.uid();
    v_muts        JSONB := '[]'::jsonb;
    v_hist_events JSONB := '[]'::jsonb;
    v_search_up   JSONB := '[]'::jsonb;
    v_op          JSONB;
    v_kind        TEXT;
    v_v5_type     TEXT;
    v_v5_op       TEXT;
    v_hlc         TEXT;
    v_payload     JSONB;
    v_mut_id      TEXT;
    v_res         JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED', 'results', '[]'::jsonb);
    END IF;

    FOR v_op IN SELECT value FROM jsonb_array_elements(COALESCE(p_ops, '[]'::jsonb)) LOOP
        v_kind    := COALESCE(v_op->>'kind', v_op->>'entity_type', '');
        v_v5_op   := CASE WHEN COALESCE(v_op->>'op', 'U') IN ('D', 'delete') THEN 'delete' ELSE 'upsert' END;
        v_hlc     := COALESCE(v_op->>'hlc', ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT << 22)::TEXT);
        v_payload := COALESCE(v_op->'payload', '{}'::jsonb);

        IF v_kind IN ('history', 'user_listening_history') THEN
            IF v_v5_op = 'upsert' THEN
                v_hist_events := v_hist_events || jsonb_build_object(
                    'client_event_id', COALESCE(v_payload->>'client_event_id', gen_random_uuid()::TEXT),
                    'track_id', v_payload->>'track_id',
                    'played_at', COALESCE(v_payload->>'played_at', v_payload->>'created_at', now()::TEXT),
                    'listened_duration_sec', COALESCE(v_payload->>'listened_duration_sec', '0'),
                    'track_duration_sec', v_payload->>'track_duration_sec',
                    'source_context', COALESCE(v_payload->>'source_context', 'legacy_v4'),
                    'track_title', COALESCE(v_payload->>'track_title', v_payload->>'title'),
                    'artist_name', v_payload->>'artist_name',
                    'album_name', v_payload->>'album_name'
                );
            END IF;
            CONTINUE;
        ELSIF v_kind IN ('search', 'user_search_history') THEN
            IF v_v5_op = 'delete' THEN
                PERFORM public.delete_search_query(
                    COALESCE(v_payload->>'query', v_op->>'entity_key', ''),
                    now()
                );
            ELSE
                v_search_up := v_search_up || jsonb_build_object(
                    'query', COALESCE(v_payload->>'query', v_op->>'entity_key', ''),
                    'result_type', COALESCE(v_payload->>'result_type', 'all'),
                    'searched_at', COALESCE(v_payload->>'searched_at', now()::TEXT)
                );
            END IF;
            CONTINUE;
        END IF;

        v_v5_type := CASE v_kind
            WHEN 'like'          THEN 'user_like'
            WHEN 'library_track' THEN 'user_library_track'
            WHEN 'favorite'      THEN 'user_item_favorite'
            WHEN 'playlist'      THEN 'playlist'
            WHEN 'playlist_track' THEN 'playlist_track'
            WHEN 'setting'       THEN 'user_setting'
            WHEN 'episode_state' THEN 'user_podcast_episode_state'
            ELSE NULL
        END;

        IF v_v5_type IS NULL THEN
            CONTINUE;
        END IF;

        v_mut_id := md5(v_user_id::TEXT || ':' || v_v5_type || ':' || COALESCE(v_op->>'entity_key', v_payload::TEXT) || ':' || v_hlc);
        v_mut_id := substr(v_mut_id,1,8)||'-'||substr(v_mut_id,9,4)||'-'||substr(v_mut_id,13,4)||'-'||substr(v_mut_id,17,4)||'-'||substr(v_mut_id,21,12);

        v_muts := v_muts || jsonb_build_object(
            'mutation_id', v_mut_id,
            'entity_type', v_v5_type,
            'entity_key', COALESCE(v_op->>'entity_key', ''),
            'op', v_v5_op,
            'hlc', v_hlc,
            'payload', v_payload
        );
    END LOOP;

    IF jsonb_array_length(v_hist_events) > 0 THEN
        PERFORM public.ingest_playback_events(COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4'), v_hist_events);
    END IF;

    IF jsonb_array_length(v_search_up) > 0 THEN
        PERFORM public.upsert_search_query(v_search_up);
    END IF;

    v_res := public.sync_push_v5(COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4'), v_muts);
    RETURN v_res;
END;
$$;

COMMIT;
