-- ============================================================================
-- Phase P12: S11 P1 — Server Contract Hardening (head_cursor, skip_changelog,
--                     history LWW & delete support)
-- ============================================================================

-- 1. Allow internal cleanup deletes in sync_push to suppress spurious 'D'
--    changelog entries via streamify.skip_changelog = '1'.
CREATE OR REPLACE FUNCTION public.tg_bump_changelog()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  v_entity text := tg_argv[0];
  v_origin text := coalesce(current_setting('streamify.device_id', true), 'server');
  v_row    jsonb;
  v_user   uuid;
  v_key    text;
  v_op     text;
  v_hlc    bigint;
BEGIN
  IF current_setting('streamify.skip_changelog', true) = '1' THEN
    RETURN coalesce(new, old);
  END IF;

  IF tg_op = 'DELETE' THEN
    v_row := to_jsonb(old);
  ELSE
    v_row := to_jsonb(new);
  END IF;

  v_user := (v_row ->> 'user_id')::uuid;
  v_hlc  := (v_row ->> 'modified_hlc')::bigint;
  v_key  := CASE v_entity
              WHEN 'like'           THEN v_row ->> 'track_id'
              WHEN 'favorite'       THEN (v_row ->> 'item_type') || ':' || (v_row ->> 'item_id')
              WHEN 'playlist'       THEN v_row ->> 'id'
              WHEN 'playlist_track' THEN v_row ->> 'id'
              WHEN 'search'         THEN v_row ->> 'query'
              WHEN 'history'        THEN coalesce(v_row ->> 'client_event_id', v_row ->> 'id')
              WHEN 'settings'       THEN 'settings'
              WHEN 'library_track'  THEN v_row ->> 'track_id'
              WHEN 'episode_state'  THEN v_row ->> 'episode_id'
              ELSE coalesce(v_row ->> 'id', v_row ->> 'track_id')
            END;

  IF tg_op = 'DELETE'
     OR (tg_op = 'UPDATE' AND new.deleted_at IS NOT NULL AND old.deleted_at IS NULL) THEN
    v_op := 'D';
    IF v_entity = 'playlist_track' THEN
      v_row := jsonb_build_object(
        'playlist_id', v_row ->> 'playlist_id',
        'track_id',    v_row ->> 'track_id'
      );
    ELSE
      v_row := null;
    END IF;
  ELSE
    v_op := CASE tg_op WHEN 'INSERT' THEN 'I' ELSE 'U' END;
  END IF;

  INSERT INTO public.sync_changelog
        (user_id, entity, op, row_key, payload, occurred_hlc, origin)
  VALUES (v_user, v_entity, v_op, v_key, v_row, v_hlc, v_origin);

  RETURN coalesce(new, old);
END $function$;

-- 2. Return head_cursor from sync_pull so bootstrap cursor probes capture
--    the true changelog head regardless of p_limit or origin filtering.
CREATE OR REPLACE FUNCTION public.sync_pull(p_since bigint, p_limit integer DEFAULT 500, p_device_id text DEFAULT NULL::text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  v_uid         uuid := auth.uid();
  v_ops         jsonb := '[]'::jsonb;
  v_last        bigint := p_since;
  v_cap         int := least(coalesce(p_limit, 500), 1000);
  v_min_id      bigint;
  v_head_cursor bigint;
  rec           record;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_pull: unauthenticated';
  END IF;

  -- Server Contract v4 Rule 5: Cursor horizon guard against 90-day GC tail loss
  IF p_since > 0 THEN
    SELECT min(id) INTO v_min_id FROM public.sync_changelog WHERE user_id = v_uid;
    IF v_min_id IS NOT NULL AND p_since < (v_min_id - 1) THEN
      RAISE EXCEPTION 'CURSOR_EXPIRED';
    END IF;
  END IF;

  SELECT coalesce(max(id), 0) INTO v_head_cursor
    FROM public.sync_changelog
   WHERE user_id = v_uid;

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
    'head_cursor', v_head_cursor,
    'has_more', jsonb_array_length(v_ops) = v_cap,
    'ops', v_ops);
END $function$;

-- 3. Helper to coerce legacy non-UUID client_event_id strings to deterministic UUIDs
CREATE OR REPLACE FUNCTION public.safe_sync_uuid(p_raw text)
 RETURNS uuid
 LANGUAGE sql
 IMMUTABLE
AS $$
  SELECT CASE
    WHEN p_raw ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      THEN p_raw::uuid
    ELSE (
      substr(md5(coalesce(p_raw, '')), 1, 8) || '-' ||
      substr(md5(coalesce(p_raw, '')), 9, 4) || '-' ||
      substr(md5(coalesce(p_raw, '')), 13, 4) || '-' ||
      substr(md5(coalesce(p_raw, '')), 17, 4) || '-' ||
      substr(md5(coalesce(p_raw, '')), 21, 12)
    )::uuid
  END;
$$;

-- 4. Update sync_push:
--    - Suppress spurious 'D' changelog op on playlist_track re-add cleanup
--    - Add 'D' branch, safe_sync_uuid(), and (modified_hlc, modified_by) LWW
--      guard on history
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
  v_event_uid uuid;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_push: unauthenticated';
  END IF;

  PERFORM set_config('streamify.device_id', v_device_id, true);
  INSERT INTO public.user_devices (user_id, device_id) VALUES (v_uid, v_device_id)
  ON CONFLICT (user_id, device_id) DO UPDATE SET last_seen = now(), last_active_at = now();

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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'library_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_library_tracks
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND track_id = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'episode_state' THEN
        IF v_op = 'D' THEN
          DELETE FROM public.user_podcast_episode_state
           WHERE user_id = v_uid AND episode_id = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'favorite' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_item_favorites
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND item_type = (v_d ->> 'item_type')
             AND item_id = (v_d ->> 'item_id')
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'playlist' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlists
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE id = v_key::uuid AND user_id = v_uid
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'playlist_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlist_tracks
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE (id::text = v_key OR (track_id = v_key AND playlist_id = (v_d ->> 'playlist_id')::uuid))
             AND playlist_id IN (SELECT id FROM public.playlists WHERE user_id = v_uid)
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        ELSE
          PERFORM set_config('streamify.skip_changelog', '1', true);
          DELETE FROM public.playlist_tracks
           WHERE playlist_id = (v_d ->> 'playlist_id')::uuid
             AND track_id = coalesce(v_d ->> 'track_id', v_key)
             AND deleted_at IS NOT NULL
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id))
             AND EXISTS (SELECT 1 FROM public.playlists p
                          WHERE p.id = (v_d ->> 'playlist_id')::uuid
                            AND (p.user_id = v_uid OR v_uid = ANY (p.collaborator_ids)));
          PERFORM set_config('streamify.skip_changelog', '0', true);

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
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'search' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_search_history
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND query = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        ELSE
          INSERT INTO public.user_search_history
                (user_id, query, searched_at, modified_hlc, modified_by)
          VALUES (v_uid, v_key,
                  coalesce((v_d ->> 'searched_at')::timestamptz, now()),
                  v_hlc, v_device_id)
          ON CONFLICT (user_id, query) DO UPDATE
             SET searched_at  = excluded.searched_at,
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.user_search_history.modified_hlc IS NULL
              OR (public.user_search_history.modified_hlc, public.user_search_history.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'history' THEN
        v_event_uid := public.safe_sync_uuid(coalesce(nullif(v_d ->> 'client_event_id', ''), v_key));
        IF v_op = 'D' THEN
          UPDATE public.user_listening_history
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND client_event_id = v_event_uid
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        ELSE
          INSERT INTO public.user_listening_history
                (user_id, client_event_id, track_id, artist_id, album_id,
                 title, artist_name, album_name, listened_duration_sec, created_at,
                 modified_hlc, modified_by, deleted_at)
          VALUES (v_uid, v_event_uid, v_d ->> 'track_id',
                  v_d ->> 'artist_id', v_d ->> 'album_id',
                  v_d ->> 'title', v_d ->> 'artist_name', v_d ->> 'album_name',
                  coalesce((v_d ->> 'listened_duration_sec')::int, 0),
                  coalesce((v_d ->> 'listened_at')::timestamptz, now()),
                  v_hlc, v_device_id, null)
          ON CONFLICT (user_id, client_event_id) DO UPDATE
             SET listened_duration_sec = greatest(
                   coalesce(public.user_listening_history.listened_duration_sec, 0),
                   excluded.listened_duration_sec
                 ),
                 title        = coalesce(nullif(excluded.title, ''), public.user_listening_history.title),
                 artist_name  = coalesce(nullif(excluded.artist_name, ''), public.user_listening_history.artist_name),
                 album_name   = coalesce(nullif(excluded.album_name, ''), public.user_listening_history.album_name),
                 modified_hlc = excluded.modified_hlc,
                 modified_by  = excluded.modified_by,
                 deleted_at   = null
           WHERE public.user_listening_history.modified_hlc IS NULL
              OR (public.user_listening_history.modified_hlc, public.user_listening_history.modified_by) < (excluded.modified_hlc, excluded.modified_by);
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN v_status := 'stale'; END IF;
        END IF;

      ELSIF v_entity = 'settings' THEN
        WITH incoming AS (
          SELECT k, v
            FROM jsonb_each(coalesce(v_d -> 'settings', '{}'::jsonb)) AS x(k, v)
           WHERE k NOT LIKE 'equalizer_%'
        ),
        existing AS (
          SELECT settings, key_hlcs
            FROM public.user_settings
           WHERE user_id = v_uid
        ),
        winners AS (
          SELECT i.k, i.v
            FROM incoming i
            LEFT JOIN existing e ON true
           WHERE coalesce((e.key_hlcs -> i.k ->> 'hlc')::bigint, -1) < v_hlc
              OR (coalesce((e.key_hlcs -> i.k ->> 'hlc')::bigint, -1) = v_hlc
                  AND coalesce(e.key_hlcs -> i.k ->> 'by', '') < v_device_id)
        ),
        merged_settings AS (
          SELECT coalesce((SELECT settings FROM existing), '{}'::jsonb)
                 || coalesce((SELECT jsonb_object_agg(k, v) FROM winners), '{}'::jsonb) AS val
        ),
        merged_hlcs AS (
          SELECT coalesce((SELECT key_hlcs FROM existing), '{}'::jsonb)
                 || coalesce((SELECT jsonb_object_agg(k, jsonb_build_object('hlc', v_hlc, 'by', v_device_id)) FROM winners), '{}'::jsonb) AS val
        )
        INSERT INTO public.user_settings (user_id, settings, key_hlcs, modified_hlc, modified_by, updated_at)
        SELECT v_uid,
               (SELECT val FROM merged_settings),
               (SELECT val FROM merged_hlcs),
               v_hlc, v_device_id, now()
          WHERE EXISTS (SELECT 1 FROM winners)
        ON CONFLICT (user_id) DO UPDATE
           SET settings     = excluded.settings,
               key_hlcs     = excluded.key_hlcs,
               modified_hlc = greatest(coalesce(public.user_settings.modified_hlc, 0), excluded.modified_hlc),
               modified_by  = excluded.modified_by,
               updated_at   = now();
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        IF v_rows = 0 THEN v_status := 'stale'; END IF;
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

  PERFORM public.touch_profile_last_active(v_uid);

  RETURN jsonb_build_object(
    'applied',  v_applied,
    'rejected', v_rejected,
    'cursor',   (SELECT coalesce(max(id), 0) FROM public.sync_changelog
                  WHERE user_id = v_uid AND origin = v_device_id),
    'results',  v_results);
END $function$;
