-- ============================================================================
-- Sync v5 P0 Fixes:
--   P0-3: Ensure user_settings.key_hlcs exists idempotently
--   P0-1: Translate legacy v4 sync_push({entity, op, key, hlc, data}) -> v5 + v4 result
--   P0-2: Translate v5 sync_pull_v5 -> legacy v4 SyncPullResult({cursor, head_cursor, has_more, ops[]})
-- ============================================================================

BEGIN;

-- P0-3: Idempotent column guard on user_settings
ALTER TABLE public.user_settings
    ADD COLUMN IF NOT EXISTS key_hlcs JSONB NOT NULL DEFAULT '{}'::jsonb;

-- P0-1: Legacy v4 sync_push shim with full entity/key/payload translation & v4 response shape
CREATE OR REPLACE FUNCTION public.sync_push(
    p_ops       JSONB,
    p_device_id TEXT DEFAULT NULL
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
    v_raw_key     TEXT;
    v_entity_key  TEXT;
    v_v5_type     TEXT;
    v_v5_op       TEXT;
    v_hlc         TEXT;
    v_payload     JSONB;
    v_norm_pay    JSONB;
    v_pl_id       TEXT;
    v_trk_id      TEXT;
    v_item_type   TEXT;
    v_item_id     TEXT;
    v_mut_id      TEXT;
    v_kv          RECORD;
    v_res         JSONB;
    v_v4_results  JSONB := '[]'::jsonb;
    v_r           JSONB;
    v_applied     INT := 0;
    v_rejected    INT := 0;
    v_cursor      BIGINT := 0;
    v_dev         TEXT := COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4');
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED', 'applied', 0, 'rejected', 0, 'cursor', 0, 'results', '[]'::jsonb);
    END IF;

    FOR v_op IN SELECT value FROM jsonb_array_elements(COALESCE(p_ops, '[]'::jsonb)) LOOP
        v_kind    := COALESCE(NULLIF(v_op->>'kind', ''), NULLIF(v_op->>'entity_type', ''), NULLIF(v_op->>'entity', ''), '');
        v_raw_key := COALESCE(NULLIF(v_op->>'entity_key', ''), NULLIF(v_op->>'key', ''), '');
        v_v5_op   := CASE WHEN COALESCE(v_op->>'op', 'U') IN ('D', 'delete') THEN 'delete' ELSE 'upsert' END;
        v_hlc     := COALESCE(NULLIF(v_op->>'hlc', ''), ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT << 22)::TEXT);
        v_payload := COALESCE(v_op->'payload', v_op->'data', '{}'::jsonb);

        -- Plane B: history & search routing
        IF v_kind IN ('history', 'user_listening_history') THEN
            IF v_v5_op = 'upsert' THEN
                v_hist_events := v_hist_events || jsonb_build_object(
                    'client_event_id', COALESCE(NULLIF(v_payload->>'client_event_id', ''), NULLIF(v_raw_key, ''), gen_random_uuid()::TEXT),
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
            v_v4_results := v_v4_results || jsonb_build_object('key', v_raw_key, 'entity', v_kind, 'status', 'applied');
            v_applied := v_applied + 1;
            CONTINUE;
        ELSIF v_kind IN ('search', 'user_search_history') THEN
            IF v_v5_op = 'delete' THEN
                PERFORM public.delete_search_query(
                    COALESCE(NULLIF(v_payload->>'query', ''), v_raw_key, ''),
                    now()
                );
            ELSE
                v_search_up := v_search_up || jsonb_build_object(
                    'query', COALESCE(NULLIF(v_payload->>'query', ''), v_raw_key, ''),
                    'result_type', COALESCE(v_payload->>'result_type', 'all'),
                    'searched_at', COALESCE(v_payload->>'searched_at', now()::TEXT)
                );
            END IF;
            v_v4_results := v_v4_results || jsonb_build_object('key', v_raw_key, 'entity', v_kind, 'status', 'applied');
            v_applied := v_applied + 1;
            CONTINUE;
        END IF;

        -- Plane A settings aggregate splitting: {settings: {k1: v1, k2: v2}} -> 1 mutation per key
        IF v_kind IN ('setting', 'settings', 'user_setting') THEN
            IF v_payload ? 'settings' AND jsonb_typeof(v_payload->'settings') = 'object' THEN
                FOR v_kv IN SELECT key, value FROM jsonb_each(v_payload->'settings') LOOP
                    v_mut_id := md5(v_user_id::TEXT || ':user_setting:' || v_kv.key || ':' || v_hlc);
                    v_mut_id := substr(v_mut_id,1,8)||'-'||substr(v_mut_id,9,4)||'-'||substr(v_mut_id,13,4)||'-'||substr(v_mut_id,17,4)||'-'||substr(v_mut_id,21,12);
                    v_muts := v_muts || jsonb_build_object(
                        'mutation_id', v_mut_id,
                        'entity_type', 'user_setting',
                        'entity_key', v_kv.key,
                        'op', v_v5_op,
                        'hlc', v_hlc,
                        'payload', jsonb_build_object('key', v_kv.key, 'value', v_kv.value)
                    );
                END LOOP;
                CONTINUE;
            ELSE
                v_entity_key := COALESCE(NULLIF(v_raw_key, ''), v_payload->>'key', '');
                v_norm_pay := jsonb_build_object('key', v_entity_key) || v_payload;
                v_v5_type := 'user_setting';
            END IF;
        ELSE
            v_v5_type := CASE v_kind
                WHEN 'like'                       THEN 'user_like'
                WHEN 'user_like'                  THEN 'user_like'
                WHEN 'library_track'              THEN 'user_library_track'
                WHEN 'user_library_track'         THEN 'user_library_track'
                WHEN 'favorite'                   THEN 'user_item_favorite'
                WHEN 'user_item_favorite'         THEN 'user_item_favorite'
                WHEN 'playlist'                   THEN 'playlist'
                WHEN 'playlist_track'             THEN 'playlist_track'
                WHEN 'episode_state'              THEN 'user_podcast_episode_state'
                WHEN 'user_podcast_episode_state' THEN 'user_podcast_episode_state'
                ELSE NULL
            END;

            IF v_v5_type IS NULL THEN
                v_rejected := v_rejected + 1;
                v_v4_results := v_v4_results || jsonb_build_object(
                    'key', v_raw_key, 'entity', v_kind, 'status', 'rejected', 'error', 'UNKNOWN_ENTITY'
                );
                CONTINUE;
            END IF;

            IF v_v5_type IN ('user_like', 'user_library_track') THEN
                v_entity_key := COALESCE(NULLIF(v_raw_key, ''), v_payload->>'track_id', '');
                v_norm_pay   := jsonb_build_object('track_id', v_entity_key) || v_payload;
            ELSIF v_v5_type = 'user_item_favorite' THEN
                v_item_type := COALESCE(NULLIF(v_payload->>'item_type', ''),
                    CASE WHEN position(':' IN v_raw_key) > 0 THEN split_part(v_raw_key, ':', 1) ELSE '' END);
                v_item_id   := COALESCE(NULLIF(v_payload->>'item_id', ''),
                    CASE WHEN position(':' IN v_raw_key) > 0 THEN substr(v_raw_key, position(':' IN v_raw_key) + 1) ELSE v_raw_key END);
                v_entity_key := v_item_type || ':' || v_item_id;
                v_norm_pay   := jsonb_build_object('item_type', v_item_type, 'item_id', v_item_id) || v_payload;
            ELSIF v_v5_type = 'playlist' THEN
                v_entity_key := COALESCE(NULLIF(v_raw_key, ''), v_payload->>'id', '');
                v_norm_pay   := jsonb_build_object('id', v_entity_key) || v_payload;
            ELSIF v_v5_type = 'playlist_track' THEN
                v_pl_id  := COALESCE(NULLIF(v_payload->>'playlist_id', ''),
                    CASE WHEN position('|' IN v_raw_key) > 0 THEN split_part(v_raw_key, '|', 1)
                         WHEN position(':' IN v_raw_key) > 0 THEN split_part(v_raw_key, ':', 1) ELSE '' END);
                v_trk_id := COALESCE(NULLIF(v_payload->>'track_id', ''),
                    CASE WHEN position('|' IN v_raw_key) > 0 THEN split_part(v_raw_key, '|', 2)
                         WHEN position(':' IN v_raw_key) > 0 THEN split_part(v_raw_key, ':', 2) ELSE v_raw_key END);
                v_entity_key := v_pl_id || ':' || v_trk_id;
                v_norm_pay   := jsonb_build_object('playlist_id', v_pl_id, 'track_id', v_trk_id) || v_payload;
            ELSE -- user_podcast_episode_state
                v_entity_key := COALESCE(NULLIF(v_raw_key, ''), v_payload->>'episode_id', '');
                v_norm_pay   := jsonb_build_object('episode_id', v_entity_key) || v_payload;
            END IF;
        END IF;

        v_mut_id := md5(v_user_id::TEXT || ':' || v_v5_type || ':' || v_entity_key || ':' || v_hlc);
        v_mut_id := substr(v_mut_id,1,8)||'-'||substr(v_mut_id,9,4)||'-'||substr(v_mut_id,13,4)||'-'||substr(v_mut_id,17,4)||'-'||substr(v_mut_id,21,12);

        v_muts := v_muts || jsonb_build_object(
            'mutation_id', v_mut_id,
            'entity_type', v_v5_type,
            'entity_key', v_entity_key,
            'op', v_v5_op,
            'hlc', v_hlc,
            'payload', v_norm_pay
        );
    END LOOP;

    IF jsonb_array_length(v_hist_events) > 0 THEN
        PERFORM public.ingest_playback_events(v_dev, v_hist_events);
    END IF;
    IF jsonb_array_length(v_search_up) > 0 THEN
        PERFORM public.upsert_search_query(
            x->>'query',
            COALESCE(x->>'result_type', 'all'),
            COALESCE(public._v5_try_parse_timestamptz(x->>'searched_at'), now()),
            v_dev
        )
        FROM jsonb_array_elements(v_search_up) AS x;
    END IF;

    IF jsonb_array_length(v_muts) > 0 THEN
        v_res := public.sync_push_v5(v_dev, v_muts);
        v_cursor := COALESCE(public._v5_try_parse_bigint(v_res->>'own_rev'), 0);

        FOR v_r IN SELECT value FROM jsonb_array_elements(COALESCE(v_res->'results', '[]'::jsonb)) LOOP
            IF COALESCE(v_r->>'status', '') IN ('applied', 'stale') THEN
                v_applied := v_applied + 1;
            ELSE
                v_rejected := v_rejected + 1;
            END IF;
            v_v4_results := v_v4_results || jsonb_build_object(
                'key', COALESCE(v_r->>'entity_key', ''),
                'entity', CASE v_r->>'entity_type'
                    WHEN 'user_like' THEN 'like'
                    WHEN 'user_library_track' THEN 'library_track'
                    WHEN 'user_item_favorite' THEN 'favorite'
                    WHEN 'playlist' THEN 'playlist'
                    WHEN 'playlist_track' THEN 'playlist_track'
                    WHEN 'user_setting' THEN 'settings'
                    WHEN 'user_podcast_episode_state' THEN 'episode_state'
                    ELSE COALESCE(v_r->>'entity_type', '')
                END,
                'status', CASE WHEN COALESCE(v_r->>'status', '') IN ('applied', 'stale') THEN v_r->>'status' ELSE 'rejected' END,
                'error', v_r->>'error_code'
            );
        END LOOP;
    END IF;

    RETURN jsonb_build_object(
        'status', COALESCE(v_res->>'status', 'OK'),
        'applied', v_applied,
        'rejected', v_rejected,
        'cursor', v_cursor,
        'results', v_v4_results
    );
END;
$$;

-- P0-2: Legacy v4 sync_pull shim translating v5 response -> SyncPullResult
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
DECLARE
    v_res      JSONB;
    v_ops      JSONB := '[]'::jsonb;
    v_c        JSONB;
    v_v4_ent   TEXT;
    v_v4_data  JSONB;
BEGIN
    v_res := public.sync_pull_v5(
        COALESCE(NULLIF(btrim(p_device_id), ''), 'legacy_v4'),
        GREATEST(COALESCE(p_since, 0), 0)::TEXT,
        p_limit,
        TRUE,
        FALSE
    );

    FOR v_c IN SELECT value FROM jsonb_array_elements(COALESCE(v_res->'changes', '[]'::jsonb)) LOOP
        v_v4_ent := CASE v_c->>'entity_type'
            WHEN 'user_like'                  THEN 'like'
            WHEN 'user_library_track'         THEN 'library_track'
            WHEN 'user_item_favorite'         THEN 'favorite'
            WHEN 'playlist'                   THEN 'playlist'
            WHEN 'playlist_track'             THEN 'playlist_track'
            WHEN 'user_setting'               THEN 'settings'
            WHEN 'user_podcast_episode_state' THEN 'episode_state'
            ELSE COALESCE(v_c->>'entity_type', '')
        END;

        IF v_c->>'entity_type' = 'user_setting' THEN
            v_v4_data := jsonb_build_object(
                'settings',
                jsonb_build_object(COALESCE(v_c->>'entity_key', ''), v_c->'payload'->'value')
            );
        ELSE
            v_v4_data := v_c->'payload';
        END IF;

        v_ops := v_ops || jsonb_build_object(
            'seq', COALESCE(public._v5_try_parse_bigint(v_c->>'rev'), 0),
            'entity', v_v4_ent,
            'op', CASE WHEN v_c->>'op' = 'delete' THEN 'D' ELSE 'U' END,
            'key', COALESCE(v_c->>'entity_key', ''),
            'hlc', public._v5_try_parse_bigint(v_c->>'hlc'),
            'data', v_v4_data
        );
    END LOOP;

    RETURN jsonb_build_object(
        'status', COALESCE(v_res->>'status', 'OK'),
        'cursor', COALESCE(public._v5_try_parse_bigint(v_res->>'next_rev'), GREATEST(COALESCE(p_since, 0), 0)),
        'head_cursor', COALESCE(public._v5_try_parse_bigint(v_res->>'head_rev'), 0),
        'has_more', COALESCE((v_res->>'has_more')::BOOLEAN, FALSE),
        'ops', v_ops
    );
END;
$$;

COMMIT;
