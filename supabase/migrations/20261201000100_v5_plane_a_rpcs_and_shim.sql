-- ============================================================================
-- ADR-011 / Plane A: Delta Sync v5 — RPCs, RLS Lockdown, GC & v4 Shims
-- Target: Supabase PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil)
-- ============================================================================

BEGIN;

-- 1. Safe Parsing Helpers (prevent uncaught cast exceptions from aborting batches)
CREATE OR REPLACE FUNCTION public._v5_try_parse_bigint(p_val TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF p_val IS NULL OR p_val !~ '^[0-9]{1,19}$' THEN
        RETURN NULL;
    END IF;
    RETURN p_val::BIGINT;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;

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

-- Forward-compatible Plane C hooks (Plane C migration upgrades _plane_c_touch_activity)
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
BEGIN
    UPDATE public.user_devices
    SET last_active_at = GREATEST(last_active_at, clock_timestamp())
    WHERE user_id = p_user_id
      AND device_id = p_device_id
      AND NOT is_revoked
      AND (last_active_at IS NULL OR last_active_at < clock_timestamp() - INTERVAL '2 minutes');
END;
$$;

-- 2. sync_push_v5 (Single Write Authority for all 7 Plane A Entities)
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

-- 3. sync_pull_v5 (5-arg signature matching sync-contract-v5.json)
CREATE OR REPLACE FUNCTION public.sync_pull_v5(
    p_device_id       TEXT,
    p_since_rev       TEXT,
    p_limit           INT DEFAULT 200,
    p_exclude_own     BOOLEAN DEFAULT TRUE,
    p_include_catalog BOOLEAN DEFAULT TRUE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_device_id     TEXT := btrim(COALESCE(p_device_id, ''));
    v_since_rev     BIGINT := public._v5_try_parse_bigint(p_since_rev);
    v_limit         INT := LEAST(GREATEST(COALESCE(p_limit, 200), 1), 500);
    v_head_rev      BIGINT := 0;
    v_min_rev       BIGINT := 0;
    v_next_rev      BIGINT;
    v_has_more      BOOLEAN := FALSE;
    v_changes       JSONB := '[]'::jsonb;
    v_server_now_ms BIGINT := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'UNAUTHORIZED');
    END IF;

    IF char_length(v_device_id) > 0 AND public._plane_c_is_device_revoked(v_user_id, v_device_id) THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'DEVICE_REVOKED');
    END IF;

    IF v_since_rev IS NULL THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'INVALID_CURSOR');
    END IF;

    SELECT head_rev, min_available_rev INTO v_head_rev, v_min_rev
    FROM public.user_sync_state
    WHERE user_id = v_user_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'protocol_version', 5, 'status', 'OK',
            'server_time_ms', v_server_now_ms::TEXT,
            'head_rev', '0', 'min_available_rev', '0',
            'next_rev', '0', 'has_more', FALSE,
            'changes', '[]'::jsonb,
            'catalog', jsonb_build_object('tracks', '{}'::jsonb)
        );
    END IF;

    IF v_since_rev < v_min_rev OR (v_since_rev = 0 AND v_head_rev > 0 AND v_min_rev > 0) OR v_since_rev > v_head_rev THEN
        RETURN jsonb_build_object(
            'protocol_version', 5, 'status', 'CURSOR_EXPIRED',
            'server_time_ms', v_server_now_ms::TEXT,
            'head_rev', v_head_rev::TEXT,
            'min_available_rev', v_min_rev::TEXT,
            'next_rev', NULL, 'has_more', FALSE,
            'changes', '[]'::jsonb,
            'catalog', jsonb_build_object('tracks', '{}'::jsonb)
        );
    END IF;

    WITH scanned AS (
        SELECT rev, mutation_id, entity_type, entity_key, op, occurred_hlc, origin_device_id, payload
        FROM public.sync_changelog_v5
        WHERE user_id = v_user_id
          AND rev > v_since_rev
          AND rev <= v_head_rev
        ORDER BY rev ASC
        LIMIT v_limit + 1
    ),
    page AS (
        SELECT * FROM scanned LIMIT v_limit
    )
    SELECT
        COALESCE(MAX(p.rev), v_since_rev),
        (SELECT COUNT(*) > v_limit FROM scanned),
        COALESCE(
            jsonb_agg(
                jsonb_build_object(
                    'rev', p.rev::TEXT,
                    'mutation_id', p.mutation_id::TEXT,
                    'entity_type', p.entity_type,
                    'entity_key', p.entity_key,
                    'op', p.op,
                    'hlc', p.occurred_hlc::TEXT,
                    'origin_device_id', p.origin_device_id,
                    'payload', p.payload
                )
                ORDER BY p.rev ASC
            ) FILTER (WHERE NOT COALESCE(p_exclude_own, TRUE) OR p.origin_device_id <> v_device_id),
            '[]'::jsonb
        )
    INTO v_next_rev, v_has_more, v_changes
    FROM page p;

    IF char_length(v_device_id) > 0 THEN
        PERFORM public._plane_c_touch_activity(v_user_id, v_device_id);
    END IF;

    RETURN jsonb_build_object(
        'protocol_version', 5,
        'status', 'OK',
        'server_time_ms', v_server_now_ms::TEXT,
        'head_rev', v_head_rev::TEXT,
        'min_available_rev', v_min_rev::TEXT,
        'next_rev', v_next_rev::TEXT,
        'has_more', v_has_more,
        'changes', v_changes,
        'catalog', jsonb_build_object('tracks', '{}'::jsonb)
    );
END;
$$;

-- 4. sync_bootstrap_v5 (Snapshot-Anchored Paginated Hydration)
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

-- 5. 90-Day Watermark-Safe Garbage Collection
CREATE OR REPLACE FUNCTION public.sync_gc_v5(p_retention INTERVAL DEFAULT INTERVAL '90 days')
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_cutoff           TIMESTAMPTZ := now() - p_retention;
    v_changelog_purged BIGINT := 0;
    v_ledger_purged    BIGINT := 0;
    v_tombstones       BIGINT := 0;
    v_tmp              BIGINT := 0;
BEGIN
    WITH doomed AS (
        SELECT user_id, MAX(rev) AS max_purged_rev
        FROM public.sync_changelog_v5
        WHERE created_at < v_cutoff
        GROUP BY user_id
    )
    UPDATE public.user_sync_state s
    SET
        min_available_rev = GREATEST(s.min_available_rev, d.max_purged_rev),
        updated_at        = now()
    FROM doomed d
    WHERE s.user_id = d.user_id;

    DELETE FROM public.sync_changelog_v5 c
    USING public.user_sync_state s
    WHERE c.user_id = s.user_id
      AND c.rev <= s.min_available_rev
      AND c.created_at < v_cutoff;
    GET DIAGNOSTICS v_changelog_purged = ROW_COUNT;

    DELETE FROM public.sync_mutations_applied WHERE applied_at < v_cutoff;
    GET DIAGNOSTICS v_ledger_purged = ROW_COUNT;

    DELETE FROM public.user_likes WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;
    DELETE FROM public.user_library_tracks WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;
    DELETE FROM public.user_item_favorites WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;
    DELETE FROM public.playlist_tracks WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;
    DELETE FROM public.playlists WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;
    DELETE FROM public.user_podcast_episode_state WHERE deleted AND updated_at < (v_cutoff - INTERVAL '1 hour');
    GET DIAGNOSTICS v_tmp = ROW_COUNT; v_tombstones := v_tombstones + v_tmp;

    RETURN jsonb_build_object(
        'cutoff', v_cutoff,
        'changelog_purged', v_changelog_purged,
        'ledger_purged', v_ledger_purged,
        'tombstones_purged', v_tombstones
    );
END;
$$;

-- 6. Drop ALL legacy changelog triggers on Plane A tables
DROP TRIGGER IF EXISTS tg_likes_changelog ON public.user_likes;
DROP TRIGGER IF EXISTS tg_library_tracks_changelog ON public.user_library_tracks;
DROP TRIGGER IF EXISTS tg_favorites_changelog ON public.user_item_favorites;
DROP TRIGGER IF EXISTS tg_playlists_changelog ON public.playlists;
DROP TRIGGER IF EXISTS tg_playlist_tracks_changelog ON public.playlist_tracks;
DROP TRIGGER IF EXISTS tg_settings_changelog ON public.user_settings;
DROP TRIGGER IF EXISTS tg_episode_state_changelog ON public.user_podcast_episode_state;

-- 7. Single-Policy RLS & Write Revocation on Plane A Tables
DO $$
DECLARE
    v_tbl TEXT;
    v_pol RECORD;
    v_tables TEXT[] := ARRAY[
        'user_likes',
        'user_library_tracks',
        'user_item_favorites',
        'playlists',
        'playlist_tracks',
        'user_settings',
        'user_podcast_episode_state',
        'user_sync_state',
        'sync_changelog_v5',
        'sync_mutations_applied'
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

        IF v_tbl <> 'sync_mutations_applied' THEN
            EXECUTE format('GRANT SELECT ON public.%I TO authenticated', v_tbl);
            EXECUTE format(
                'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING ((SELECT auth.uid()) = user_id)',
                v_tbl || '_select_own',
                v_tbl
            );
        END IF;
    END LOOP;
END $$;

-- 8. Lock down v5 RPCs to authenticated
REVOKE ALL ON FUNCTION public.sync_push_v5(TEXT, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_push_v5(TEXT, JSONB) TO authenticated;

REVOKE ALL ON FUNCTION public.sync_pull_v5(TEXT, TEXT, INT, BOOLEAN, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_pull_v5(TEXT, TEXT, INT, BOOLEAN, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.sync_bootstrap_v5(JSONB, INT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.sync_bootstrap_v5(JSONB, INT, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.sync_gc_v5(INTERVAL) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.sync_gc_v5(INTERVAL) TO service_role;

-- 9. Legacy v4 Compatibility Shims (zero-downtime staggered rollout)
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
    v_user_id   UUID := auth.uid();
    v_muts      JSONB := '[]'::jsonb;
    v_op        JSONB;
    v_kind      TEXT;
    v_v5_type   TEXT;
    v_v5_op     TEXT;
    v_hlc       TEXT;
    v_payload   JSONB;
    v_mut_id    TEXT;
    v_res       JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED', 'results', '[]'::jsonb);
    END IF;

    FOR v_op IN SELECT value FROM jsonb_array_elements(COALESCE(p_ops, '[]'::jsonb)) LOOP
        v_kind    := COALESCE(v_op->>'kind', v_op->>'entity_type', '');
        v_v5_op   := CASE WHEN COALESCE(v_op->>'op', 'U') IN ('D', 'delete') THEN 'delete' ELSE 'upsert' END;
        v_hlc     := COALESCE(v_op->>'hlc', ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT << 22)::TEXT);
        v_payload := COALESCE(v_op->'payload', '{}'::jsonb);

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

    v_res := public.sync_push_v5(COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4'), v_muts);
    RETURN v_res;
END;
$$;

CREATE OR REPLACE FUNCTION public.sync_pull(
    p_since     BIGINT DEFAULT 0,
    p_limit     INT DEFAULT 200,
    p_device_id TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN public.sync_pull_v5(
        COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4'),
        GREATEST(COALESCE(p_since, 0), 0)::TEXT,
        p_limit,
        TRUE,
        FALSE
    );
END;
$$;

COMMIT;
