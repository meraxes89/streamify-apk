-- ============================================================================
-- ADR-011 / Sync v5 P2 Fixes
-- P2-1: Guarded pg_cron jobs + transaction-safe maintain_plane_b_partitions
-- P2-3: track_hint_rate_limits for ingest_track_hints
-- P2-8: Device-revocation checks in sync_push_v5 (post-lock), stop_presence, Plane B
-- P2-9: fetch_search_history separate tombstone limit
-- P2-10: get_hybrid_home_shelves NOT_MODIFIED for authenticated callers
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS public.track_hint_rate_limits (
    user_id      UUID NOT NULL,
    window_hour  TIMESTAMPTZ NOT NULL,
    call_count   INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, window_hour)
);
ALTER TABLE public.track_hint_rate_limits ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION public.plane_c_sweep_expired_presence()
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_deleted BIGINT := 0;
BEGIN
    DELETE FROM public.user_presence
    WHERE expires_at < now() - INTERVAL '1 hour';
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

CREATE OR REPLACE FUNCTION public.refresh_v5_shelf_mvs()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW public.mv_shelf_community_hits;
    REFRESH MATERIALIZED VIEW public.mv_shelf_hourly_community_tracks;
END;
$$;

REVOKE ALL ON FUNCTION public.plane_c_sweep_expired_presence() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.plane_c_sweep_expired_presence() TO service_role;
REVOKE ALL ON FUNCTION public.refresh_v5_shelf_mvs() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.refresh_v5_shelf_mvs() TO service_role;

CREATE OR REPLACE FUNCTION public.sync_push_v5(
    p_device_id TEXT,
    p_mutations JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id        UUID := auth.uid();
    v_device_id      TEXT := btrim(COALESCE(p_device_id, ''));
    v_server_now_ms  BIGINT := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
    v_max_future_ms  CONSTANT BIGINT := 300000; -- +5 minutes max forward drift
    v_head_rev       BIGINT;
    v_own_rev        BIGINT := NULL;
    v_mut            JSONB;
    v_mut_id         UUID;
    v_entity_type    TEXT;
    v_entity_key     TEXT;
    v_op             TEXT;
    v_hlc            BIGINT;
    v_hlc_ms         BIGINT;
    v_payload        JSONB;
    v_existing_mut   RECORD;
    v_applied        BOOLEAN;
    v_cur_hlc        BIGINT;
    v_cur_by         TEXT;
    v_results        JSONB := '[]'::jsonb;
    -- Entity scratch vars
    v_track_id       TEXT;
    v_item_type      TEXT;
    v_item_id        TEXT;
    v_playlist_id    UUID;
    v_playlist_name  TEXT;
    v_sort_key       TEXT;
    v_setting_key    TEXT;
    v_setting_meta   JSONB;
    v_episode_id     TEXT;
    v_podcast_id     TEXT;
    v_pos_ms         BIGINT;
    v_dur_ms         BIGINT;
    v_ts             TIMESTAMPTZ;
    v_ts2            TIMESTAMPTZ;
    v_parent_deleted BOOLEAN;
    v_bool           BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'UNAUTHORIZED');
    END IF;

    IF char_length(v_device_id) NOT BETWEEN 1 AND 128 THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'INVALID_DEVICE_ID');
    END IF;

    IF public._plane_c_is_device_revoked(v_user_id, v_device_id) THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'DEVICE_REVOKED');
    END IF;

    IF p_mutations IS NULL OR jsonb_typeof(p_mutations) <> 'array' THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'INVALID_BATCH');
    END IF;

    IF jsonb_array_length(p_mutations) > 200 THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'BATCH_TOO_LARGE', 'max_batch_size', 200);
    END IF;

    -- 1. Acquire strict per-user serialization lock (zero commit-order rev holes)
    INSERT INTO public.user_sync_state (user_id, head_rev, min_available_rev)
    VALUES (v_user_id, 0, 0)
    ON CONFLICT (user_id) DO NOTHING;

    SELECT head_rev INTO v_head_rev
    FROM public.user_sync_state
    WHERE user_id = v_user_id
    FOR UPDATE;

    -- Re-check device revocation after acquiring the per-user lock to close the TOCTOU window
    IF public._plane_c_is_device_revoked(v_user_id, v_device_id) THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'DEVICE_REVOKED');
    END IF;

    -- 2. Process each mutation deterministically in batch order
    FOR v_mut IN SELECT value FROM jsonb_array_elements(p_mutations) LOOP
        v_hlc := public._v5_try_parse_bigint(v_mut->>'hlc');

        IF (v_mut->>'mutation_id') IS NULL
           OR (v_mut->>'mutation_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR v_hlc IS NULL
           OR (v_mut->>'op') NOT IN ('upsert', 'delete')
           OR (v_mut->>'entity_type') NOT IN (
                'user_like', 'user_library_track', 'user_item_favorite',
                'playlist', 'playlist_track', 'user_setting', 'user_podcast_episode_state'
              )
           OR (v_mut->'payload') IS NULL
           OR jsonb_typeof(v_mut->'payload') <> 'object'
        THEN
            v_results := v_results || jsonb_build_object(
                'mutation_id', COALESCE(v_mut->>'mutation_id', ''),
                'status', 'rejected',
                'error_code', 'INVALID_PAYLOAD'
            );
            CONTINUE;
        END IF;

        v_mut_id      := (v_mut->>'mutation_id')::UUID;
        v_entity_type := v_mut->>'entity_type';
        v_entity_key  := COALESCE(v_mut->>'entity_key', '');
        v_op          := v_mut->>'op';
        v_hlc_ms      := (v_hlc >> 22);
        v_payload     := v_mut->'payload';

        -- 2a. Idempotency check
        SELECT status, assigned_rev INTO v_existing_mut
        FROM public.sync_mutations_applied
        WHERE user_id = v_user_id AND mutation_id = v_mut_id;

        IF FOUND THEN
            v_results := v_results || jsonb_build_object(
                'mutation_id', v_mut_id::TEXT,
                'status', 'duplicate',
                'rev', CASE WHEN v_existing_mut.assigned_rev IS NOT NULL
                            THEN v_existing_mut.assigned_rev::TEXT ELSE NULL END
            );
            CONTINUE;
        END IF;

        -- 2b. Forward clock-skew guard
        IF v_hlc <= 0 OR v_hlc_ms > (v_server_now_ms + v_max_future_ms) THEN
            v_results := v_results || jsonb_build_object(
                'mutation_id', v_mut_id::TEXT,
                'status', 'rejected',
                'error_code', 'CLOCK_SKEW_EXCESSIVE',
                'server_time_ms', v_server_now_ms::TEXT
            );
            CONTINUE;
        END IF;

        v_applied := FALSE;
        v_cur_hlc := NULL;
        v_cur_by  := NULL;

        -- 2c. Per-entity LWW dispatch
        IF v_entity_type = 'user_like' THEN
            v_track_id := v_payload->>'track_id';
            IF v_track_id IS NULL OR char_length(v_track_id) NOT BETWEEN 1 AND 256 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_ts := public._v5_try_parse_timestamptz(COALESCE(v_payload->>'liked_at', v_payload->>'created_at'));
            IF (v_payload ? 'liked_at' OR v_payload ? 'created_at') AND v_ts IS NULL
               AND COALESCE(v_payload->>'liked_at', v_payload->>'created_at') IS NOT NULL THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_ts := COALESCE(v_ts, now());
            v_entity_key := v_track_id;

            INSERT INTO public.user_likes (
                user_id, track_id, created_at, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_user_id, v_track_id, v_ts, (v_op = 'delete'),
                CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (user_id, track_id) DO UPDATE SET
                created_at   = CASE WHEN v_op = 'upsert' THEN COALESCE(public._v5_try_parse_timestamptz(COALESCE(v_payload->>'liked_at', v_payload->>'created_at')), user_likes.created_at) ELSE user_likes.created_at END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (user_likes.modified_hlc, user_likes.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.user_likes WHERE user_id = v_user_id AND track_id = v_track_id;
            END IF;

            v_payload := jsonb_build_object('track_id', v_track_id) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object('liked_at', v_ts) ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'user_library_track' THEN
            v_track_id := v_payload->>'track_id';
            IF v_track_id IS NULL OR char_length(v_track_id) NOT BETWEEN 1 AND 256 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_ts := COALESCE(public._v5_try_parse_timestamptz(v_payload->>'added_at'), now());
            v_entity_key := v_track_id;

            INSERT INTO public.user_library_tracks (
                user_id, track_id, added_at, source, metadata, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_user_id, v_track_id, v_ts,
                COALESCE(NULLIF(btrim(v_payload->>'source'), ''), 'library'),
                COALESCE(CASE WHEN jsonb_typeof(v_payload->'metadata') = 'object' THEN v_payload->'metadata' END, '{}'::jsonb),
                (v_op = 'delete'), CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (user_id, track_id) DO UPDATE SET
                added_at     = CASE WHEN v_op = 'upsert' THEN COALESCE(public._v5_try_parse_timestamptz(v_payload->>'added_at'), user_library_tracks.added_at) ELSE user_library_tracks.added_at END,
                source       = CASE WHEN v_op = 'upsert' THEN COALESCE(NULLIF(btrim(v_payload->>'source'), ''), user_library_tracks.source) ELSE user_library_tracks.source END,
                metadata     = CASE WHEN v_op = 'upsert' AND jsonb_typeof(v_payload->'metadata') = 'object' THEN v_payload->'metadata' ELSE user_library_tracks.metadata END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (user_library_tracks.modified_hlc, user_library_tracks.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.user_library_tracks WHERE user_id = v_user_id AND track_id = v_track_id;
            END IF;

            v_payload := jsonb_build_object('track_id', v_track_id) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object(
                             'added_at', v_ts,
                             'source', COALESCE(NULLIF(btrim(v_payload->>'source'), ''), 'library')
                         ) ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'user_item_favorite' THEN
            v_item_type := v_payload->>'item_type';
            v_item_id   := v_payload->>'item_id';
            IF v_item_type NOT IN ('artist', 'album', 'playlist', 'podcast')
               OR v_item_id IS NULL OR char_length(v_item_id) NOT BETWEEN 1 AND 256 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_ts := COALESCE(public._v5_try_parse_timestamptz(COALESCE(v_payload->>'favorited_at', v_payload->>'created_at')), now());
            v_entity_key := v_item_type || ':' || v_item_id;

            INSERT INTO public.user_item_favorites (
                user_id, item_type, item_id, created_at, metadata, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_user_id, v_item_type, v_item_id, v_ts,
                COALESCE(CASE WHEN jsonb_typeof(v_payload->'metadata') = 'object' THEN v_payload->'metadata' END, '{}'::jsonb),
                (v_op = 'delete'), CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
                created_at   = CASE WHEN v_op = 'upsert' THEN COALESCE(public._v5_try_parse_timestamptz(COALESCE(v_payload->>'favorited_at', v_payload->>'created_at')), user_item_favorites.created_at) ELSE user_item_favorites.created_at END,
                metadata     = CASE WHEN v_op = 'upsert' AND jsonb_typeof(v_payload->'metadata') = 'object' THEN v_payload->'metadata' ELSE user_item_favorites.metadata END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (user_item_favorites.modified_hlc, user_item_favorites.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.user_item_favorites WHERE user_id = v_user_id AND item_type = v_item_type AND item_id = v_item_id;
            END IF;

            v_payload := jsonb_build_object('item_type', v_item_type, 'item_id', v_item_id) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object(
                             'favorited_at', v_ts,
                             'metadata', COALESCE(CASE WHEN jsonb_typeof(v_payload->'metadata') = 'object' THEN v_payload->'metadata' END, '{}'::jsonb)
                         ) ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'playlist' THEN
            IF (v_payload->>'id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_playlist_id   := (v_payload->>'id')::UUID;
            v_playlist_name := btrim(COALESCE(v_payload->>'name', ''));
            IF v_op = 'upsert' AND char_length(v_playlist_name) NOT BETWEEN 1 AND 256 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            IF v_op = 'delete' AND v_playlist_name = '' THEN
                v_playlist_name := 'Deleted Playlist';
            END IF;
            v_ts := COALESCE(public._v5_try_parse_timestamptz(v_payload->>'created_at'), now());
            IF v_payload ? 'is_public' AND v_payload->>'is_public' IS NOT NULL THEN
                v_bool := public._v5_try_parse_boolean(v_payload->>'is_public');
                IF v_bool IS NULL THEN
                    v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                    CONTINUE;
                END IF;
            ELSE
                v_bool := FALSE;
            END IF;
            v_entity_key := v_playlist_id::TEXT;

            INSERT INTO public.playlists (
                id, user_id, name, description, cover_url, is_public, created_at, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_playlist_id, v_user_id, v_playlist_name,
                COALESCE(v_payload->>'description', ''),
                v_payload->>'cover_url',
                v_bool,
                v_ts, (v_op = 'delete'), CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (id) DO UPDATE SET
                name         = CASE WHEN v_op = 'upsert' THEN EXCLUDED.name ELSE playlists.name END,
                description  = CASE WHEN v_op = 'upsert' THEN EXCLUDED.description ELSE playlists.description END,
                cover_url    = CASE WHEN v_op = 'upsert' THEN EXCLUDED.cover_url ELSE playlists.cover_url END,
                is_public    = CASE WHEN v_op = 'upsert' THEN EXCLUDED.is_public ELSE playlists.is_public END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE playlists.user_id = v_user_id
              AND (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (playlists.modified_hlc, playlists.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.playlists WHERE id = v_playlist_id AND user_id = v_user_id;
                IF NOT FOUND THEN
                    v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'PARENT_NOT_FOUND');
                    CONTINUE;
                END IF;
            END IF;

            v_payload := jsonb_build_object('id', v_playlist_id::TEXT) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object(
                             'name', v_playlist_name,
                             'description', COALESCE(v_payload->>'description', ''),
                             'cover_url', v_payload->>'cover_url',
                             'is_public', v_bool
                         ) ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'playlist_track' THEN
            IF (v_payload->>'playlist_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
               OR COALESCE(char_length(v_payload->>'track_id'), 0) NOT BETWEEN 1 AND 256 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;

            v_playlist_id := (v_payload->>'playlist_id')::UUID;
            v_track_id    := v_payload->>'track_id';
            v_sort_key    := COALESCE(v_payload->>'sort_key', 'V');
            v_entity_key  := v_playlist_id::TEXT || ':' || v_track_id;

            IF v_op = 'upsert' AND (char_length(v_sort_key) NOT BETWEEN 1 AND 128 OR v_sort_key !~ '^[0-9A-Za-z]+$') THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;

            SELECT deleted INTO v_parent_deleted
            FROM public.playlists
            WHERE id = v_playlist_id AND user_id = v_user_id;

            IF NOT FOUND OR (v_op = 'upsert' AND v_parent_deleted) THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'PARENT_NOT_FOUND');
                CONTINUE;
            END IF;

            v_ts := COALESCE(public._v5_try_parse_timestamptz(v_payload->>'added_at'), now());

            INSERT INTO public.playlist_tracks (
                playlist_id, track_id, user_id, added_by, sort_key, added_at, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_playlist_id, v_track_id, v_user_id, v_user_id, v_sort_key, v_ts,
                (v_op = 'delete'), CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (playlist_id, track_id) DO UPDATE SET
                sort_key     = CASE WHEN v_op = 'upsert' THEN EXCLUDED.sort_key ELSE playlist_tracks.sort_key END,
                added_at     = CASE WHEN v_op = 'upsert' THEN COALESCE(public._v5_try_parse_timestamptz(v_payload->>'added_at'), playlist_tracks.added_at) ELSE playlist_tracks.added_at END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (playlist_tracks.modified_hlc, playlist_tracks.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.playlist_tracks WHERE playlist_id = v_playlist_id AND track_id = v_track_id;
            END IF;

            v_payload := jsonb_build_object('playlist_id', v_playlist_id::TEXT, 'track_id', v_track_id) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object('sort_key', v_sort_key, 'added_at', v_ts) ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'user_setting' THEN
            v_setting_key := v_payload->>'key';
            IF v_setting_key IS NULL OR char_length(v_setting_key) NOT BETWEEN 1 AND 128 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_entity_key := v_setting_key;

            INSERT INTO public.user_settings (user_id, settings, key_hlcs, modified_hlc, modified_by, updated_at)
            VALUES (v_user_id, '{}'::jsonb, '{}'::jsonb, 1, 'init', now())
            ON CONFLICT (user_id) DO NOTHING;

            SELECT key_hlcs -> v_setting_key INTO v_setting_meta
            FROM public.user_settings
            WHERE user_id = v_user_id
            FOR UPDATE;

            v_cur_hlc := COALESCE(public._v5_try_parse_bigint(v_setting_meta->>'hlc'), 0);
            v_cur_by  := COALESCE(v_setting_meta->>'by', '');

            IF (v_hlc, v_device_id) > (v_cur_hlc, v_cur_by) THEN
                UPDATE public.user_settings
                SET
                    settings = CASE
                        WHEN v_op = 'delete' THEN settings - v_setting_key
                        ELSE jsonb_set(settings, ARRAY[v_setting_key], COALESCE(v_payload->'value', 'null'::jsonb), true)
                    END,
                    key_hlcs = jsonb_set(
                        key_hlcs,
                        ARRAY[v_setting_key],
                        jsonb_build_object('hlc', v_hlc::TEXT, 'by', v_device_id, 'deleted', (v_op = 'delete')),
                        true
                    ),
                    modified_hlc = GREATEST(modified_hlc, v_hlc),
                    modified_by  = CASE WHEN (v_hlc, v_device_id) >= (modified_hlc, modified_by) THEN v_device_id ELSE modified_by END,
                    updated_at   = now()
                WHERE user_id = v_user_id;
                v_applied := TRUE;
            END IF;

            v_payload := jsonb_build_object('key', v_setting_key) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object('value', v_payload->'value') ELSE '{}'::jsonb END;

        ELSIF v_entity_type = 'user_podcast_episode_state' THEN
            v_episode_id := v_payload->>'episode_id';
            v_podcast_id := v_payload->>'podcast_id';
            v_pos_ms     := COALESCE(public._v5_try_parse_bigint(v_payload->>'position_ms'), 0);
            v_dur_ms     := public._v5_try_parse_bigint(v_payload->>'duration_ms');
            IF v_episode_id IS NULL OR char_length(v_episode_id) NOT BETWEEN 1 AND 256
               OR (v_op = 'upsert' AND (v_podcast_id IS NULL OR char_length(v_podcast_id) NOT BETWEEN 1 AND 256))
               OR v_pos_ms < 0 THEN
                v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                CONTINUE;
            END IF;
            v_podcast_id := COALESCE(NULLIF(btrim(v_podcast_id), ''), 'unknown');
            v_ts := COALESCE(public._v5_try_parse_timestamptz(v_payload->>'played_at'), now());
            IF v_payload ? 'completed' AND v_payload->>'completed' IS NOT NULL THEN
                v_bool := public._v5_try_parse_boolean(v_payload->>'completed');
                IF v_bool IS NULL THEN
                    v_results := v_results || jsonb_build_object('mutation_id', v_mut_id::TEXT, 'status', 'rejected', 'error_code', 'INVALID_PAYLOAD');
                    CONTINUE;
                END IF;
            ELSE
                v_bool := FALSE;
            END IF;
            v_entity_key := v_episode_id;

            INSERT INTO public.user_podcast_episode_state (
                user_id, episode_id, podcast_id, position_sec, position_ms, duration_ms,
                completed, played_at, deleted, deleted_at, modified_hlc, modified_by, updated_at
            ) VALUES (
                v_user_id, v_episode_id, v_podcast_id,
                LEAST(2147483647, (v_pos_ms / 1000))::INT, v_pos_ms, v_dur_ms,
                v_bool, v_ts,
                (v_op = 'delete'), CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                v_hlc, v_device_id, now()
            )
            ON CONFLICT (user_id, episode_id) DO UPDATE SET
                podcast_id   = CASE WHEN v_op = 'upsert' THEN EXCLUDED.podcast_id ELSE user_podcast_episode_state.podcast_id END,
                position_sec = CASE WHEN v_op = 'upsert' THEN EXCLUDED.position_sec ELSE user_podcast_episode_state.position_sec END,
                position_ms  = CASE WHEN v_op = 'upsert' THEN EXCLUDED.position_ms ELSE user_podcast_episode_state.position_ms END,
                duration_ms  = CASE WHEN v_op = 'upsert' THEN COALESCE(EXCLUDED.duration_ms, user_podcast_episode_state.duration_ms) ELSE user_podcast_episode_state.duration_ms END,
                completed    = CASE WHEN v_op = 'upsert' THEN EXCLUDED.completed ELSE user_podcast_episode_state.completed END,
                played_at    = CASE WHEN v_op = 'upsert' THEN EXCLUDED.played_at ELSE user_podcast_episode_state.played_at END,
                deleted      = (v_op = 'delete'),
                deleted_at   = CASE WHEN v_op = 'delete' THEN now() ELSE NULL END,
                modified_hlc = EXCLUDED.modified_hlc,
                modified_by  = EXCLUDED.modified_by,
                updated_at   = now()
            WHERE (EXCLUDED.modified_hlc, EXCLUDED.modified_by) > (user_podcast_episode_state.modified_hlc, user_podcast_episode_state.modified_by)
            RETURNING TRUE INTO v_applied;

            IF NOT COALESCE(v_applied, FALSE) THEN
                SELECT modified_hlc, modified_by INTO v_cur_hlc, v_cur_by
                FROM public.user_podcast_episode_state WHERE user_id = v_user_id AND episode_id = v_episode_id;
            END IF;

            v_payload := jsonb_build_object('episode_id', v_episode_id) ||
                         CASE WHEN v_op = 'upsert' THEN jsonb_build_object(
                             'podcast_id', v_podcast_id,
                             'position_ms', v_pos_ms,
                             'duration_ms', v_dur_ms,
                             'completed', v_bool,
                             'played_at', v_ts
                         ) ELSE '{}'::jsonb END;
        END IF;

        -- 2d. Record idempotency ledger & explicit changelog entry
        IF COALESCE(v_applied, FALSE) THEN
            v_head_rev := v_head_rev + 1;
            v_own_rev  := v_head_rev;

            INSERT INTO public.sync_changelog_v5 (
                user_id, rev, mutation_id, entity_type, entity_key, op, occurred_hlc, origin_device_id, payload
            ) VALUES (
                v_user_id, v_head_rev, v_mut_id, v_entity_type, v_entity_key, v_op, v_hlc, v_device_id, v_payload
            );

            INSERT INTO public.sync_mutations_applied (
                user_id, mutation_id, entity_type, entity_key, status, assigned_rev
            ) VALUES (
                v_user_id, v_mut_id, v_entity_type, v_entity_key, 'applied', v_head_rev
            );

            v_results := v_results || jsonb_build_object(
                'mutation_id', v_mut_id::TEXT,
                'status', 'applied',
                'rev', v_head_rev::TEXT
            );
        ELSE
            INSERT INTO public.sync_mutations_applied (
                user_id, mutation_id, entity_type, entity_key, status, assigned_rev
            ) VALUES (
                v_user_id, v_mut_id, v_entity_type, v_entity_key, 'stale', NULL
            );

            v_results := v_results || jsonb_build_object(
                'mutation_id', v_mut_id::TEXT,
                'status', 'stale',
                'server_hlc', COALESCE(v_cur_hlc::TEXT, '0'),
                'server_by', COALESCE(v_cur_by, '')
            );
        END IF;
    END LOOP;

    -- 3. Commit updated head_rev once for the entire batch
    UPDATE public.user_sync_state
    SET head_rev = v_head_rev, updated_at = now()
    WHERE user_id = v_user_id;

    PERFORM public._plane_c_touch_activity(v_user_id, v_device_id);

    RETURN jsonb_build_object(
        'protocol_version', 5,
        'status', 'OK',
        'server_time_ms', v_server_now_ms::TEXT,
        'own_rev', CASE WHEN v_own_rev IS NOT NULL THEN v_own_rev::TEXT ELSE NULL END,
        'head_rev', v_head_rev::TEXT,
        'results', v_results
    );
END;
$$;

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

    IF public._plane_c_is_device_revoked(
        v_user_id,
        NULLIF(btrim(COALESCE(current_setting('request.headers', true)::jsonb->>'x-device-id', '')), '')
    ) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
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

    IF public._plane_c_is_device_revoked(
        v_user_id,
        NULLIF(btrim(COALESCE(current_setting('request.headers', true)::jsonb->>'x-device-id', '')), '')
    ) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
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
            LIMIT 1000
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
            EXECUTE format('ALTER TABLE public.user_listening_history DETACH PARTITION public.%I', v_rec.relname);
            EXECUTE format('DROP TABLE IF EXISTS public.%I', v_rec.relname);
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

    IF public._plane_c_is_device_revoked(v_user_id, btrim(p_device_id)) THEN
        RETURN jsonb_build_object('status', 'DEVICE_REVOKED');
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

CREATE OR REPLACE FUNCTION public.ingest_track_hints(p_hints JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id     UUID := auth.uid();
    v_item        JSONB;
    v_norm        RECORD;
    v_title       TEXT;
    v_artist      TEXT;
    v_album       TEXT;
    v_dur_big     BIGINT;
    v_dur         INT;
    v_thumb       TEXT;
    v_existing    RECORD;
    v_accepted    INT := 0;
    v_ignored     INT := 0;
    v_enqueued    INT := 0;
    v_quality     SMALLINT;
    v_need_enrich BOOLEAN;
    v_window_hr   TIMESTAMPTZ := date_trunc('hour', clock_timestamp());
    v_hint_calls  INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_hints IS NULL OR jsonb_typeof(p_hints) <> 'array' OR jsonb_array_length(p_hints) > 100 THEN
        RETURN jsonb_build_object('status', 'INVALID_BATCH', 'max_batch_size', 100);
    END IF;

    INSERT INTO public.track_hint_rate_limits (user_id, window_hour, call_count)
    VALUES (v_user_id, v_window_hr, 1)
    ON CONFLICT (user_id, window_hour) DO UPDATE SET
        call_count = track_hint_rate_limits.call_count + 1
    RETURNING call_count INTO v_hint_calls;

    IF v_hint_calls > 60 THEN
        RETURN jsonb_build_object('status', 'RATE_LIMITED', 'rate_limited', true);
    END IF;

    FOR v_item IN SELECT value FROM jsonb_array_elements(p_hints) LOOP
        SELECT * INTO v_norm FROM public._plane_d_normalize_track_id(v_item->>'track_id');
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        v_title   := NULLIF(btrim(v_item->>'title'), '');
        v_artist  := NULLIF(btrim(v_item->>'artist_name'), '');
        v_album   := NULLIF(btrim(v_item->>'album_name'), '');
        v_dur_big := public._v5_try_parse_bigint(v_item->>'duration_sec');
        v_dur     := CASE
            WHEN v_dur_big IS NOT NULL AND v_dur_big BETWEEN 1 AND 86400 THEN v_dur_big::INT
            ELSE NULL
        END;
        v_thumb   := CASE
            WHEN (v_item->>'thumb_url') ~ '^https://' THEN left(btrim(v_item->>'thumb_url'), 1024)
            WHEN (v_item->>'thumb_url') ~ '^http://'  THEN 'https://' || substring(left(btrim(v_item->>'thumb_url'), 1024) FROM 8)
            ELSE NULL
        END;

        IF public._plane_d_is_placeholder(v_title) THEN v_title := NULL; END IF;
        IF public._plane_d_is_placeholder(v_artist) THEN v_artist := NULL; END IF;

        SELECT id, title, artist_name, album_name, duration_sec, thumb_url, metadata_authority, enrichment_status
        INTO v_existing
        FROM public.track_index
        WHERE id = v_norm.canonical_id;

        IF FOUND AND v_existing.metadata_authority >= 50 THEN
            v_ignored := v_ignored + 1;
            CONTINUE;
        END IF;

        IF NOT FOUND THEN
            v_quality := (CASE WHEN v_dur IS NOT NULL THEN 35 ELSE 0 END)
                       + (CASE WHEN v_title IS NOT NULL THEN 20 ELSE 0 END)
                       + (CASE WHEN v_artist IS NOT NULL THEN 20 ELSE 0 END)
                       + (CASE WHEN v_thumb IS NOT NULL THEN 15 ELSE 0 END)
                       + (CASE WHEN v_album IS NOT NULL THEN 10 ELSE 0 END);

            INSERT INTO public.track_index (
                id, source, external_id, title, artist_name, album_name,
                duration_sec, thumb_url, metadata_authority, metadata_quality,
                enrichment_status, catalog_version
            ) VALUES (
                v_norm.canonical_id, v_norm.source, v_norm.external_id,
                COALESCE(left(v_title, 512), 'Unknown Track'),
                COALESCE(left(v_artist, 512), 'Unknown Artist'),
                v_album,
                v_dur,
                v_thumb,
                CASE WHEN v_title IS NOT NULL AND v_artist IS NOT NULL THEN 10 ELSE 0 END,
                v_quality,
                'pending',
                1
            )
            ON CONFLICT (id) DO NOTHING;

            v_accepted    := v_accepted + 1;
            v_need_enrich := TRUE;
        ELSE
            UPDATE public.track_index
            SET
                title              = CASE WHEN public._plane_d_is_placeholder(title) AND v_title IS NOT NULL THEN left(v_title, 512) ELSE title END,
                artist_name        = CASE WHEN public._plane_d_is_placeholder(artist_name) AND v_artist IS NOT NULL THEN left(v_artist, 512) ELSE artist_name END,
                album_name         = COALESCE(NULLIF(btrim(album_name), ''), v_album),
                duration_sec       = CASE WHEN COALESCE(duration_sec, 0) = 0 AND v_dur IS NOT NULL THEN v_dur ELSE duration_sec END,
                thumb_url          = COALESCE(thumb_url, v_thumb),
                metadata_authority = GREATEST(metadata_authority, CASE WHEN v_title IS NOT NULL THEN 10 ELSE 0 END),
                catalog_version    = catalog_version + 1,
                updated_at         = now()
            WHERE id = v_norm.canonical_id
              AND metadata_authority < 50
              AND (
                  (public._plane_d_is_placeholder(title) AND v_title IS NOT NULL)
                  OR (public._plane_d_is_placeholder(artist_name) AND v_artist IS NOT NULL)
                  OR (COALESCE(duration_sec, 0) = 0 AND v_dur IS NOT NULL)
                  OR (thumb_url IS NULL AND v_thumb IS NOT NULL)
                  OR (album_name IS NULL AND v_album IS NOT NULL)
              );

            IF FOUND THEN
                v_accepted := v_accepted + 1;
            ELSE
                v_ignored := v_ignored + 1;
            END IF;

            v_need_enrich := (v_existing.enrichment_status <> 'enriched');
        END IF;

        IF v_need_enrich THEN
            INSERT INTO public.catalog_enrichment_queue (track_id, source, external_id, priority, reason)
            VALUES (
                v_norm.canonical_id,
                v_norm.source,
                v_norm.external_id,
                CASE WHEN COALESCE(v_dur, v_existing.duration_sec, 0) = 0 THEN 100 ELSE 50 END,
                CASE WHEN COALESCE(v_dur, v_existing.duration_sec, 0) = 0 THEN 'missing_duration' ELSE 'verify_hint' END
            )
            ON CONFLICT (track_id) DO UPDATE SET
                priority   = GREATEST(catalog_enrichment_queue.priority, EXCLUDED.priority),
                updated_at = now()
            WHERE catalog_enrichment_queue.status = 'queued';

            v_enqueued := v_enqueued + 1;
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'OK',
        'accepted', v_accepted,
        'ignored_higher_authority', v_ignored,
        'enqueued_for_enrichment', v_enqueued,
        'rate_limited', false
    );
END;
$$;

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

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_cron') THEN
        BEGIN
            PERFORM cron.unschedule('streamify_gc_daily');
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
        PERFORM cron.schedule('sync_gc_v5_daily', '17 3 * * *', $job$SELECT public.sync_gc_v5(INTERVAL '90 days');$job$);
        PERFORM cron.schedule('maintain_plane_b_partitions_daily', '23 3 * * *', $job$SELECT public.maintain_plane_b_partitions(3, 24);$job$);
        PERFORM cron.schedule('plane_c_sweep_expired_presence_15m', '*/15 * * * *', $job$SELECT public.plane_c_sweep_expired_presence();$job$);
        PERFORM cron.schedule('refresh_v5_shelf_mvs_hourly', '7 * * * *', $job$SELECT public.refresh_v5_shelf_mvs();$job$);
    END IF;
END $$;

COMMIT;
