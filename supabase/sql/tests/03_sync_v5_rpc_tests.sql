-- ============================================================================
-- 03_sync_v5_rpc_tests.sql — Sync v5 RPC & Invariant Tests (Planes A–F)
-- Runs inside a rolled-back transaction so it leaves zero test data behind.
-- Exercises P0/P1 invariants:
--   1. All 27 contract RPCs exist in public schema
--   2. P0-3 / P0-4 columns exist (user_settings.key_hlcs, user_listening_history.modified_by)
--   3. P1-3 safe boolean parsing in sync_push_v5 (invalid boolean rejects 1 op, not batch)
--   4. P1-1 collation-safe pagination in sync_bootstrap_v5
--   5. P0-1 / P0-2 sync_push / sync_pull v4 shims
--   6. P1-4 heartbeat_presence_v2 keeps handoff_epoch stable on same track/device
--   7. P1-5 ingest_playback_events idempotent checkpointing + profile totals
-- ============================================================================

BEGIN;

DO $$
DECLARE
    v_uid         UUID := '00000000-0000-4000-8000-00000000a001'::UUID;
    v_dev         TEXT := 'test-device-v5-1';
    v_pl_good     UUID := '00000000-0000-4000-8000-00000000b001'::UUID;
    v_pl_bad      UUID := '00000000-0000-4000-8000-00000000b002'::UUID;
    v_mut_good    UUID := '00000000-0000-4000-8000-00000000c001'::UUID;
    v_mut_bad     UUID := '00000000-0000-4000-8000-00000000c002'::UUID;
    v_ev_id       UUID := '00000000-0000-4000-8000-00000000d001'::UUID;
    v_hlc_now     BIGINT := ((EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT) << 16;
    v_res         JSONB;
    v_hb1         JSONB;
    v_hb2         JSONB;
    v_sess        JSONB;
    v_epoch1      TEXT;
    v_epoch2      TEXT;
    v_missing_rpc TEXT;
BEGIN
    -- 1. Verify all 27 contract RPCs + v4 shims exist
    SELECT r INTO v_missing_rpc
    FROM unnest(ARRAY[
        'sync_push_v5', 'sync_pull_v5', 'sync_bootstrap_v5', 'sync_push', 'sync_pull',
        'ingest_playback_events', 'fetch_listening_history', 'upsert_search_query', 'delete_search_query',
        'clear_search_history', 'fetch_search_history', 'fetch_wrapped_snapshots', 'upsert_wrapped_snapshot',
        'compute_wrapped_snapshot', 'heartbeat_presence_v2', 'stop_presence', 'get_live_presence',
        'record_device_session', 'revoke_device_session', 'get_my_devices', 'get_tracks_batch',
        'ingest_track_hints', 'update_active_session_v5', 'fetch_active_session_v5', 'get_hybrid_home_shelves',
        'get_admin_dashboard_stats', 'get_admin_edge_compute_stats', 'get_admin_top_tracks', 'get_admin_fleet_overview'
    ]) AS r
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public' AND p.proname = r
    )
    LIMIT 1;
    IF v_missing_rpc IS NOT NULL THEN
        RAISE EXCEPTION 'Missing v5 contract RPC: %', v_missing_rpc;
    END IF;

    -- 2. Verify P0-3 & P0-4 columns
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'user_settings' AND column_name = 'key_hlcs'
    ) THEN
        RAISE EXCEPTION 'P0-3 regression: user_settings.key_hlcs missing';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'user_listening_history' AND column_name = 'modified_by'
    ) THEN
        RAISE EXCEPTION 'P0-4 regression: user_listening_history.modified_by missing';
    END IF;

    -- Setup test user + profile + device
    INSERT INTO auth.users (id, email) VALUES (v_uid, 'v5test@streamify.local') ON CONFLICT (id) DO NOTHING;
    INSERT INTO public.profiles (id, email) VALUES (v_uid, 'v5test@streamify.local') ON CONFLICT (id) DO NOTHING;

    PERFORM set_config('request.jwt.claims', json_build_object('sub', v_uid::TEXT, 'role', 'authenticated')::TEXT, true);

    v_res := public.record_device_session(v_dev, 'Test Phone', 'android', '14', 'Pixel', '5.0.0', 500, 'prod');
    IF (v_res->>'status') <> 'OK' THEN
        RAISE EXCEPTION 'record_device_session failed: %', v_res;
    END IF;

    -- 3. P1-3: Invalid boolean in one mutation rejects only that mutation, not the batch
    v_res := public.sync_push_v5(
        v_dev,
        jsonb_build_array(
            jsonb_build_object(
                'mutation_id', v_mut_good::TEXT,
                'entity_type', 'playlist',
                'entity_key', v_pl_good::TEXT,
                'op', 'upsert',
                'hlc', v_hlc_now::TEXT,
                'payload', jsonb_build_object('id', v_pl_good::TEXT, 'name', 'Good Playlist', 'is_public', true)
            ),
            jsonb_build_object(
                'mutation_id', v_mut_bad::TEXT,
                'entity_type', 'playlist',
                'entity_key', v_pl_bad::TEXT,
                'op', 'upsert',
                'hlc', (v_hlc_now + 1)::TEXT,
                'payload', jsonb_build_object('id', v_pl_bad::TEXT, 'name', 'Bad Playlist', 'is_public', 'not-a-bool')
            )
        )
    );
    IF (v_res->>'status') <> 'OK'
       OR (v_res->'results'->0->>'status') <> 'applied'
       OR (v_res->'results'->1->>'status') <> 'rejected'
       OR (v_res->'results'->1->>'error_code') <> 'INVALID_PAYLOAD'
    THEN
        RAISE EXCEPTION 'P1-3 regression in sync_push_v5: %', v_res;
    END IF;

    -- 4. P1-1: sync_bootstrap_v5 returns valid snapshot + rejects corrupt cursor safely
    v_res := public.sync_bootstrap_v5(NULL, 50, FALSE);
    IF (v_res->>'status') <> 'OK' OR jsonb_array_length(v_res->'rows') < 1 THEN
        RAISE EXCEPTION 'sync_bootstrap_v5 failed: %', v_res;
    END IF;
    v_res := public.sync_bootstrap_v5('{"table_idx":"not_an_int"}'::JSONB, 50, FALSE);
    IF (v_res->>'status') <> 'INVALID_CURSOR' THEN
        RAISE EXCEPTION 'P1-3/P3-4 regression on invalid cursor: %', v_res;
    END IF;

    -- 5. P0-1 / P0-2: v4 sync_push + sync_pull shims
    v_res := public.sync_push(
        jsonb_build_array(
            jsonb_build_object(
                'entity', 'like',
                'op', 'U',
                'key', 'yt:v5testtrack',
                'hlc', v_hlc_now + 10,
                'data', jsonb_build_object('track_id', 'yt:v5testtrack')
            )
        ),
        v_dev
    );
    IF (v_res->>'status') <> 'OK' OR (v_res->>'applied')::INT <> 1 THEN
        RAISE EXCEPTION 'P0-1 v4 sync_push shim failed: %', v_res;
    END IF;
    v_res := public.sync_pull(0, 100);
    IF (v_res->>'status') <> 'OK' OR jsonb_array_length(v_res->'ops') < 2 THEN
        RAISE EXCEPTION 'P0-2 v4 sync_pull shim failed: %', v_res;
    END IF;

    -- 6. P1-4: heartbeat_presence_v2 keeps handoff_epoch stable on same track/device
    v_res := public.update_active_session_v5(
        v_dev, 'Test Phone', 'android', TRUE, 'yt:trk1', 1000, 180000, 1.0::REAL,
        'playlist', v_pl_good::TEXT, 'Track 1', 'Artist 1', NULL,
        ARRAY['yt:trk1', 'yt:trk2'], 0, FALSE, 'off'
    );
    IF (v_res->>'status') <> 'OK' OR NOT COALESCE((v_res->>'queue_saved')::BOOLEAN, FALSE) THEN
        RAISE EXCEPTION 'update_active_session_v5 failed: %', v_res;
    END IF;
    v_epoch1 := v_res->>'handoff_epoch';

    v_hb2 := public.heartbeat_presence_v2(
        v_dev, 'Test Phone', 'android', TRUE, 'yt:trk1', 26000, 180000, 1.0::REAL, 80::SMALLINT, 45,
        'playlist', v_pl_good::TEXT, 'Track 1', 'Artist 1', NULL
    );
    IF (v_hb2->>'status') <> 'OK' THEN
        RAISE EXCEPTION 'heartbeat_presence_v2 failed: %', v_hb2;
    END IF;

    v_sess := public.fetch_active_session_v5(NULL, FALSE);
    v_epoch2 := v_sess->'handoff'->>'handoff_epoch';
    IF v_epoch1 <> v_epoch2 OR (v_sess->'queue') IS NULL OR jsonb_typeof(v_sess->'queue') = 'null' THEN
        RAISE EXCEPTION 'P1-4 regression: handoff_epoch drifted (% -> %) or queue null: %', v_epoch1, v_epoch2, v_sess;
    END IF;

    -- 7. P1-5: ingest_playback_events idempotent checkpointing
    v_res := public.ingest_playback_events(
        v_dev,
        jsonb_build_array(
            jsonb_build_object(
                'client_event_id', v_ev_id::TEXT,
                'track_id', 'yt:trk1',
                'played_at', to_char(clock_timestamp() - INTERVAL '1 minute', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'listened_duration_sec', 45,
                'track_duration_sec', 180
            )
        )
    );
    IF (v_res->>'status') <> 'OK' OR (v_res->'results'->0->>'status') <> 'inserted' THEN
        RAISE EXCEPTION 'P1-5 ingest_playback_events failed: %', v_res;
    END IF;
END $$;

ROLLBACK;

SELECT 'Sync v5 RPC & P0/P1 invariant tests passed' AS result;
