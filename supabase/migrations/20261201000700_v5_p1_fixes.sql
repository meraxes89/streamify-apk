-- ============================================================================
-- ADR-011 / Sync v5 P1 Fixes
-- P1-1: Explicit COLLATE "C" in sync_bootstrap_v5 WHERE clauses
-- P1-3: _v5_try_parse_boolean + safe cursor parsing in sync_push_v5/bootstrap
-- P1-4: heartbeat_presence_v2 / stop_presence only bump handoff_epoch on change
-- P1-5: ingest_playback_events drops profiles FOR UPDATE lock
-- ============================================================================

BEGIN;

-- Ensure P0-4 column exists on partitioned user_listening_history even when 000200 ran prior to patch
ALTER TABLE public.user_listening_history
    ADD COLUMN IF NOT EXISTS modified_by TEXT;

CREATE OR REPLACE FUNCTION public._v5_try_parse_boolean(p_val TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_norm TEXT;
BEGIN
    IF p_val IS NULL THEN
        RETURN NULL;
    END IF;
    v_norm := lower(btrim(p_val));
    IF v_norm IN ('true', 't', '1') THEN
        RETURN TRUE;
    ELSIF v_norm IN ('false', 'f', '0') THEN
        RETURN FALSE;
    END IF;
    RETURN NULL;
END;
$$;

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

CREATE OR REPLACE FUNCTION public.sync_bootstrap_v5(
    p_page_token      JSONB DEFAULT NULL,
    p_page_size       INT DEFAULT 500,
    p_include_catalog BOOLEAN DEFAULT TRUE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_page_size     INT := LEAST(GREATEST(COALESCE(p_page_size, 500), 1), 1000);
    v_snapshot_rev  BIGINT;
    v_table_idx     INT := 0;
    v_after_key     TEXT := '';
    v_remaining     INT;
    v_rows          JSONB := '[]'::jsonb;
    v_chunk         JSONB;
    v_chunk_count   INT;
    v_last_key      TEXT;
    v_has_more      BOOLEAN := FALSE;
    v_next_token    JSONB := NULL;
    v_server_now_ms BIGINT := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'UNAUTHORIZED');
    END IF;

    IF p_page_token IS NULL THEN
        SELECT COALESCE(head_rev, 0) INTO v_snapshot_rev
        FROM public.user_sync_state WHERE user_id = v_user_id;
        v_snapshot_rev := COALESCE(v_snapshot_rev, 0);
    ELSE
        IF (p_page_token ? 'snapshot_rev' AND p_page_token->>'snapshot_rev' IS NOT NULL
            AND public._v5_try_parse_bigint(p_page_token->>'snapshot_rev') IS NULL)
           OR (p_page_token ? 'table_idx' AND p_page_token->>'table_idx' IS NOT NULL
            AND public._v5_try_parse_bigint(p_page_token->>'table_idx') IS NULL)
        THEN
            RETURN jsonb_build_object('protocol_version', 5, 'status', 'INVALID_CURSOR');
        END IF;
        v_snapshot_rev := COALESCE(public._v5_try_parse_bigint(p_page_token->>'snapshot_rev'), 0);
        v_table_idx    := LEAST(GREATEST(COALESCE(public._v5_try_parse_bigint(p_page_token->>'table_idx'), 0), 0), 7)::INT;
        v_after_key    := COALESCE(p_page_token->>'after_key', '');
    END IF;

    v_remaining := v_page_size;

    WHILE v_table_idx <= 6 AND v_remaining > 0 LOOP
        v_chunk := '[]'::jsonb;
        v_chunk_count := 0;
        v_last_key := NULL;

        IF v_table_idx = 0 THEN
            WITH q AS (
                SELECT id::TEXT AS k, name, description, cover_url, is_public, created_at, modified_hlc, modified_by
                FROM public.playlists
                WHERE user_id = v_user_id AND NOT deleted AND (id::TEXT COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY id::TEXT COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'playlist', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('id', p.k, 'name', p.name, 'description', p.description, 'cover_url', p.cover_url, 'is_public', p.is_public, 'created_at', p.created_at)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 1 THEN
            WITH q AS (
                SELECT (playlist_id::TEXT || ':' || track_id) AS k, playlist_id::TEXT AS pid, track_id, sort_key, added_at, modified_hlc, modified_by
                FROM public.playlist_tracks
                WHERE user_id = v_user_id AND NOT deleted AND ((playlist_id::TEXT || ':' || track_id) COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY (playlist_id::TEXT || ':' || track_id) COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'playlist_track', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('playlist_id', p.pid, 'track_id', p.track_id, 'sort_key', p.sort_key, 'added_at', p.added_at)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 2 THEN
            WITH q AS (
                SELECT track_id AS k, created_at, modified_hlc, modified_by
                FROM public.user_likes
                WHERE user_id = v_user_id AND NOT deleted AND (track_id COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY track_id COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'user_like', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('track_id', p.k, 'liked_at', p.created_at)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 3 THEN
            WITH q AS (
                SELECT track_id AS k, added_at, source, metadata, modified_hlc, modified_by
                FROM public.user_library_tracks
                WHERE user_id = v_user_id AND NOT deleted AND (track_id COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY track_id COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'user_library_track', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('track_id', p.k, 'added_at', p.added_at, 'source', p.source, 'metadata', p.metadata)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 4 THEN
            WITH q AS (
                SELECT (item_type || ':' || item_id) AS k, item_type, item_id, created_at, metadata, modified_hlc, modified_by
                FROM public.user_item_favorites
                WHERE user_id = v_user_id AND NOT deleted AND ((item_type || ':' || item_id) COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY (item_type || ':' || item_id) COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'user_item_favorite', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('item_type', p.item_type, 'item_id', p.item_id, 'favorited_at', p.created_at, 'metadata', p.metadata)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 5 THEN
            WITH q AS (
                SELECT kv.key AS k, kv.value AS val,
                       COALESCE((us.key_hlcs -> kv.key ->> 'hlc'), us.modified_hlc::TEXT) AS hlc,
                       COALESCE((us.key_hlcs -> kv.key ->> 'by'), us.modified_by) AS modified_by
                FROM public.user_settings us
                CROSS JOIN LATERAL jsonb_each(us.settings) AS kv(key, value)
                WHERE us.user_id = v_user_id
                  AND COALESCE((us.key_hlcs -> kv.key ->> 'deleted')::BOOLEAN, false) = false
                  AND (kv.key COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY kv.key COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'user_setting', 'entity_key', p.k,
                'hlc', p.hlc, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('key', p.k, 'value', p.val)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;

        ELSIF v_table_idx = 6 THEN
            WITH q AS (
                SELECT episode_id AS k, podcast_id, position_ms, duration_ms, completed, played_at, modified_hlc, modified_by
                FROM public.user_podcast_episode_state
                WHERE user_id = v_user_id AND NOT deleted AND (episode_id COLLATE "C") > (v_after_key COLLATE "C")
                ORDER BY episode_id COLLATE "C" ASC
                LIMIT v_remaining + 1
            ), p AS (SELECT * FROM q LIMIT v_remaining)
            SELECT COALESCE(jsonb_agg(jsonb_build_object(
                'entity_type', 'user_podcast_episode_state', 'entity_key', p.k,
                'hlc', p.modified_hlc::TEXT, 'modified_by', p.modified_by,
                'payload', jsonb_build_object('episode_id', p.k, 'podcast_id', p.podcast_id, 'position_ms', p.position_ms, 'duration_ms', p.duration_ms, 'completed', p.completed, 'played_at', p.played_at)
            ) ORDER BY p.k COLLATE "C" ASC), '[]'::jsonb), COUNT(*), MAX(p.k), (SELECT COUNT(*) > v_remaining FROM q)
            INTO v_chunk, v_chunk_count, v_last_key, v_has_more FROM p;
        END IF;

        v_rows      := v_rows || v_chunk;
        v_remaining := v_remaining - v_chunk_count;

        IF v_has_more THEN
            v_next_token := jsonb_build_object(
                'snapshot_rev', v_snapshot_rev::TEXT,
                'table_idx', v_table_idx,
                'after_key', v_last_key
            );
            EXIT;
        ELSE
            v_table_idx := v_table_idx + 1;
            v_after_key := '';
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'protocol_version', 5,
        'status', 'OK',
        'server_time_ms', v_server_now_ms::TEXT,
        'snapshot_rev', v_snapshot_rev::TEXT,
        'has_more', v_has_more,
        'next_page_token', v_next_token,
        'rows', v_rows,
        'catalog', jsonb_build_object('tracks', '{}'::jsonb)
    );
END;
$$;

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

COMMIT;
