-- =========================================================================
-- Phase 2 Fix: Server Contract v4, Deterministic LWW & Protocol Hardening
-- Applied to live project dbgtvzgwixerhnwvhtil on 2026-11-25
-- Fixes: P0-2 (push cursor corruption), P0-5 (playlist_tracks constraint),
--        P0-8 (LWW tie-breakers), Rule 5 (cursor horizon guard)
-- =========================================================================

-- 1. Schema Tracking Migration Table
CREATE SCHEMA IF NOT EXISTS supabase_migrations;
CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations (
    version text NOT NULL PRIMARY KEY,
    statements text[],
    name text
);

-- 2. Deterministic LWW columns (modified_by) across all synced tables
ALTER TABLE public.user_likes ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.user_library_tracks ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.user_podcast_episode_state ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.user_settings ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.playlist_tracks ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.playlists ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';
ALTER TABLE public.user_item_favorites ADD COLUMN IF NOT EXISTS modified_by text NOT NULL DEFAULT '';

-- 3. Playlist Tracks: Drop position unique constraint, enforce active partial index
DROP INDEX IF EXISTS public.uq_playlist_position;
DROP INDEX IF EXISTS public.uq_playlist_track;
CREATE UNIQUE INDEX IF NOT EXISTS uq_playlist_track_active
  ON public.playlist_tracks (playlist_id, track_id)
  WHERE deleted_at IS NULL;

-- 4. Server Contract v4: sync_pull with cursor horizon guard
CREATE OR REPLACE FUNCTION public.sync_pull(p_since bigint, p_limit integer DEFAULT 500, p_device_id text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  v_uid    uuid := auth.uid();
  v_ops    jsonb := '[]'::jsonb;
  v_last   bigint := p_since;
  v_cap    int := least(coalesce(p_limit, 500), 1000);
  v_min_id bigint;
  rec      record;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_pull: unauthenticated';
  END IF;

  -- Rule 5: Cursor horizon guard against 90-day GC tail loss
  IF p_since > 0 THEN
    SELECT min(id) INTO v_min_id FROM public.sync_changelog WHERE user_id = v_uid;
    IF v_min_id IS NOT NULL AND p_since < (v_min_id - 1) THEN
      RAISE EXCEPTION 'CURSOR_EXPIRED';
    END IF;
  END IF;

  FOR rec IN
    SELECT id, entity, op, row_key, payload, occurred_hlc
      FROM public.sync_changelog
     WHERE user_id = v_uid
       AND id > p_since
       AND (p_device_id IS NULL OR origin IS DISTINCT FROM p_device_id)
     ORDER BY id
     LIMIT v_cap
  LOOP
    v_ops := v_ops || jsonb_build_object(
      'seq', rec.id, 'entity', rec.entity, 'op', rec.op, 'key', rec.row_key,
      'hlc', rec.occurred_hlc, 'data', rec.payload);
    v_last := rec.id;
  END LOOP;

  RETURN jsonb_build_object(
    'cursor', v_last,
    'has_more', jsonb_array_length(v_ops) = v_cap,
    'ops', v_ops);
END $function$;

-- 5. Server Contract v4: sync_push with deterministic LWW and per-op results
CREATE OR REPLACE FUNCTION public.sync_push(p_ops jsonb, p_device_id text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  v_uid       uuid := auth.uid();
  v_device_id text := coalesce(nullif(trim(p_device_id), ''), 'server');
  v_applied   int  := 0;
  v_rejected  int  := 0;
  v_results   jsonb := '[]'::jsonb;
  op          jsonb;
  v_entity    text;
  v_op        text;
  v_key       text;
  v_hlc       bigint;
  v_d         jsonb;
  v_status    text;
  v_rows      int;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_push: unauthenticated';
  END IF;

  PERFORM set_config('streamify.device_id', v_device_id, true);
  INSERT INTO public.user_devices (user_id, device_id) VALUES (v_uid, v_device_id)
  ON CONFLICT (user_id, device_id) DO UPDATE SET last_seen = now();

  FOR op IN SELECT * FROM jsonb_array_elements(p_ops) LOOP
    v_entity := op ->> 'entity';
    v_op     := coalesce(op ->> 'op', 'U');
    v_key    := op ->> 'key';
    v_hlc    := coalesce((op ->> 'hlc')::bigint, 0);
    v_d      := coalesce(op -> 'data', '{}'::jsonb);
    v_status := 'applied';

    BEGIN
      IF v_entity = 'like' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_likes
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND track_id = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          INSERT INTO public.user_likes
                (user_id, track_id, created_at, snapshot, modified_hlc, modified_by, deleted_at)
          VALUES (v_uid, v_key,
                  coalesce((v_d ->> 'created_at')::timestamptz, now()),
                  v_d -> 'meta', v_hlc, v_device_id, null)
          ON CONFLICT (user_id, track_id) DO UPDATE
             SET created_at   = excluded.created_at,
                 snapshot     = coalesce(excluded.snapshot, public.user_likes.snapshot),
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.user_likes.modified_hlc IS NULL
              OR (public.user_likes.modified_hlc, public.user_likes.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'library_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_library_tracks
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND track_id = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          INSERT INTO public.user_library_tracks
                (user_id, track_id, added_at, modified_hlc, modified_by, deleted_at)
          VALUES (v_uid, v_key,
                  coalesce((v_d ->> 'added_at')::timestamptz, now()),
                  v_hlc, v_device_id, null)
          ON CONFLICT (user_id, track_id) DO UPDATE
             SET added_at     = excluded.added_at,
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.user_library_tracks.modified_hlc IS NULL
              OR (public.user_library_tracks.modified_hlc, public.user_library_tracks.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'episode_state' THEN
        IF v_op = 'D' THEN
          DELETE FROM public.user_podcast_episode_state
           WHERE user_id = v_uid AND episode_id = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          INSERT INTO public.user_podcast_episode_state
                (user_id, episode_id, podcast_id, position_sec, completed, modified_hlc, modified_by, updated_at)
          VALUES (v_uid, v_key, coalesce(v_d ->> 'podcast_id', ''),
                  coalesce((v_d ->> 'position_sec')::int, 0),
                  coalesce((v_d ->> 'completed')::boolean, false),
                  v_hlc, v_device_id, now())
          ON CONFLICT (user_id, episode_id) DO UPDATE
             SET position_sec = excluded.position_sec,
                 completed    = excluded.completed,
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 updated_at   = now()
           WHERE public.user_podcast_episode_state.modified_hlc IS NULL
              OR (public.user_podcast_episode_state.modified_hlc, public.user_podcast_episode_state.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'favorite' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_item_favorites
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND item_type = (v_d ->> 'item_type')
             AND item_id = (v_d ->> 'item_id')
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          INSERT INTO public.user_item_favorites
                (user_id, item_type, item_id, metadata, modified_hlc, modified_by, deleted_at)
          VALUES (v_uid, v_d ->> 'item_type', v_d ->> 'item_id',
                  coalesce(v_d -> 'metadata', v_d -> 'meta'), v_hlc, v_device_id, null)
          ON CONFLICT (user_id, item_type, item_id) DO UPDATE
             SET metadata     = coalesce(excluded.metadata, public.user_item_favorites.metadata),
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.user_item_favorites.modified_hlc IS NULL
              OR (public.user_item_favorites.modified_hlc, public.user_item_favorites.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'playlist' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlists
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE id = v_key::uuid AND user_id = v_uid
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          INSERT INTO public.playlists
                (id, user_id, name, description, cover_url, is_public,
                 youtube_playlist_id, created_at, modified_hlc, modified_by, deleted_at)
          VALUES (v_key::uuid, v_uid, v_d ->> 'name', v_d ->> 'description',
                  v_d ->> 'cover_url', coalesce((v_d ->> 'is_public')::boolean, false),
                  v_d ->> 'youtube_playlist_id',
                  coalesce((v_d ->> 'created_at')::timestamptz, now()), v_hlc, v_device_id, null)
          ON CONFLICT (id) DO UPDATE
             SET name = excluded.name, description = excluded.description,
                 cover_url = excluded.cover_url, is_public = excluded.is_public,
                 youtube_playlist_id = excluded.youtube_playlist_id,
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.playlists.user_id = v_uid
             AND (public.playlists.modified_hlc IS NULL
                  OR (public.playlists.modified_hlc, public.playlists.modified_by) < (excluded.modified_hlc, excluded.modified_by));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'playlist_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlist_tracks
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE (id::text = v_key OR (track_id = v_key AND playlist_id = (v_d ->> 'playlist_id')::uuid))
             AND playlist_id IN (SELECT id FROM public.playlists WHERE user_id = v_uid)
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        ELSE
          -- Remove any stale tombstones for this (playlist_id, track_id) to avoid clutter,
          -- guarded by ownership and LWW so delayed older adds cannot destroy newer deletes.
          DELETE FROM public.playlist_tracks
           WHERE playlist_id = (v_d ->> 'playlist_id')::uuid
             AND track_id = coalesce(v_d ->> 'track_id', v_key)
             AND deleted_at IS NOT NULL
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id))
             AND EXISTS (SELECT 1 FROM public.playlists p
                          WHERE p.id = (v_d ->> 'playlist_id')::uuid
                            AND (p.user_id = v_uid OR v_uid = ANY (p.collaborator_ids)));

          INSERT INTO public.playlist_tracks
                (id, playlist_id, track_id, position, added_by, added_at,
                 modified_hlc, modified_by, deleted_at)
          SELECT coalesce(nullif(v_d ->> 'id', '')::uuid, gen_random_uuid()),
                 (v_d ->> 'playlist_id')::uuid,
                 coalesce(v_d ->> 'track_id', v_key),
                 coalesce((v_d ->> 'position')::int, 0),
                 v_uid,
                 coalesce((v_d ->> 'added_at')::timestamptz, now()),
                 v_hlc, v_device_id, null
           WHERE EXISTS (SELECT 1 FROM public.playlists p
                          WHERE p.id = (v_d ->> 'playlist_id')::uuid
                            AND (p.user_id = v_uid OR v_uid = ANY (p.collaborator_ids)))
          ON CONFLICT (playlist_id, track_id) WHERE deleted_at IS NULL DO UPDATE
             SET position     = excluded.position,
                 track_id     = excluded.track_id,
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE (public.playlist_tracks.modified_hlc IS NULL
                  OR (public.playlist_tracks.modified_hlc, public.playlist_tracks.modified_by) < (excluded.modified_hlc, excluded.modified_by))
             AND EXISTS (SELECT 1 FROM public.playlists p
                          WHERE p.id = public.playlist_tracks.playlist_id
                            AND (p.user_id = v_uid OR v_uid = ANY (p.collaborator_ids)));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'search' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_search_history
             SET deleted_at = now()
           WHERE user_id = v_uid AND query = v_key;
        ELSE
          INSERT INTO public.user_search_history (user_id, query, searched_at)
          VALUES (v_uid, v_key, coalesce((v_d ->> 'searched_at')::timestamptz, now()))
          ON CONFLICT (user_id, query) DO UPDATE
             SET searched_at = excluded.searched_at, deleted_at = null;
        END IF;

      ELSIF v_entity = 'history' THEN
        INSERT INTO public.user_listening_history
              (user_id, client_event_id, track_id, artist_id, album_id,
               title, artist_name, album_name, listened_duration_sec, created_at)
        VALUES (v_uid, (v_d ->> 'client_event_id')::uuid, v_d ->> 'track_id',
                v_d ->> 'artist_id', v_d ->> 'album_id',
                v_d ->> 'title', v_d ->> 'artist_name', v_d ->> 'album_name',
                coalesce((v_d ->> 'listened_duration_sec')::int, 0),
                coalesce((v_d ->> 'listened_at')::timestamptz, now()))
        ON CONFLICT (user_id, client_event_id) DO NOTHING;

      ELSIF v_entity = 'settings' THEN
        INSERT INTO public.user_settings (user_id, settings, modified_hlc, modified_by, updated_at)
        VALUES (v_uid,
                (SELECT coalesce(jsonb_object_agg(k, v), '{}'::jsonb)
                   FROM jsonb_each(coalesce(v_d -> 'settings', '{}'::jsonb)) AS x(k, v)
                  WHERE k NOT LIKE 'equalizer_%'),
                v_hlc, v_device_id, now())
        ON CONFLICT (user_id) DO UPDATE
           SET settings     = public.user_settings.settings || excluded.settings,
               modified_hlc = excluded.modified_hlc,
               modified_by  = excluded.modified_by,
               updated_at   = now()
         WHERE public.user_settings.modified_hlc IS NULL
            OR (public.user_settings.modified_hlc, public.user_settings.modified_by) < (excluded.modified_hlc, excluded.modified_by);
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        IF v_rows = 0 THEN
          v_status := 'stale';
        END IF;
      END IF;

      IF v_entity IN ('like', 'history', 'playlist_track', 'library_track') AND v_d ? 'catalog' THEN
        INSERT INTO public.track_index
              (id, source, external_id, title, artist_name, album_name,
               duration_sec, thumb_url)
        VALUES (v_d -> 'catalog' ->> 'id',
                v_d -> 'catalog' ->> 'source',
                v_d -> 'catalog' ->> 'external_id',
                v_d -> 'catalog' ->> 'title',
                v_d -> 'catalog' ->> 'artist_name',
                v_d -> 'catalog' ->> 'album_name',
                (v_d -> 'catalog' ->> 'duration_sec')::int,
                v_d -> 'catalog' ->> 'thumb_url')
        ON CONFLICT (source, external_id) DO UPDATE
           SET title = excluded.title, artist_name = excluded.artist_name,
               album_name = excluded.album_name,
               duration_sec = excluded.duration_sec,
               thumb_url = excluded.thumb_url, updated_at = now();
      END IF;

      v_applied := v_applied + 1;
      v_results := v_results || jsonb_build_object(
        'key', v_key, 'entity', v_entity, 'status', v_status
      );

    EXCEPTION WHEN OTHERS THEN
      v_rejected := v_rejected + 1;
      v_results := v_results || jsonb_build_object(
        'key', v_key, 'entity', v_entity, 'status', 'rejected', 'error', SQLERRM
      );
    END;
  END LOOP;

  -- Server Contract v4 Rule 1: Push returns own_cursor (scoped to origin = v_device_id)
  -- so push never skips remote changelog entries.
  RETURN jsonb_build_object(
    'applied',  v_applied,
    'rejected', v_rejected,
    'cursor',   (SELECT coalesce(max(id), 0) FROM public.sync_changelog
                  WHERE user_id = v_uid AND origin = v_device_id),
    'results',  v_results);
END $function$;
