-- ============================================================================
-- Phase P10: HLC Hardening for Search/History & Monotonic profiles.last_active_at
-- ============================================================================
-- 1. Adds modified_hlc / modified_by to user_search_history & user_listening_history
--    and enforces (modified_hlc, modified_by) LWW in sync_push for search & history.
-- 2. Protects profiles.last_active_at from client clock skew / backward overwrites
--    via a BEFORE UPDATE trigger (monotonic + clamped to server NOW()), and stamps
--    profiles.last_active_at from sync_push, heartbeat_presence, and
--    record_device_session (throttled to 60s to avoid row-lock contention).
-- ============================================================================

-- 1. Schema additions for search & listening history HLC tracking
ALTER TABLE public.user_search_history
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS modified_by  TEXT   NOT NULL DEFAULT '';

ALTER TABLE public.user_listening_history
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS modified_by  TEXT   NOT NULL DEFAULT '';

-- 2. Monotonic server-time guard on profiles.last_active_at
CREATE OR REPLACE FUNCTION public.guard_profiles_last_active_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
  IF NEW.last_active_at IS DISTINCT FROM OLD.last_active_at THEN
    NEW.last_active_at := GREATEST(
      COALESCE(OLD.last_active_at, '-infinity'::timestamptz),
      LEAST(COALESCE(NEW.last_active_at, now()), now())
    );
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tg_guard_profiles_last_active_at ON public.profiles;
CREATE TRIGGER tg_guard_profiles_last_active_at
  BEFORE UPDATE ON public.profiles
  FOR EACH ROW
  EXECUTE FUNCTION public.guard_profiles_last_active_at();

-- Helper: throttled monotonic server-NOW() stamp for profiles.last_active_at
CREATE OR REPLACE FUNCTION public.touch_profile_last_active(p_uid uuid)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  UPDATE public.profiles
     SET last_active_at = GREATEST(COALESCE(last_active_at, '-infinity'::timestamptz), now())
   WHERE id = p_uid
     AND (last_active_at IS NULL OR last_active_at < now() - interval '60 seconds');
$$;

REVOKE ALL ON FUNCTION public.touch_profile_last_active(uuid) FROM PUBLIC, anon;

-- 3. Update heartbeat_presence to also stamp profiles.last_active_at
CREATE OR REPLACE FUNCTION public.heartbeat_presence(
  p_device_id   text,
  p_track_id    text    default null,
  p_position_ms bigint  default 0,
  p_is_playing  boolean default false,
  p_queue_ids   jsonb   default '[]'::jsonb,
  p_hlc         bigint  default 0)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid uuid := auth.uid();
  v_row public.user_presence;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'heartbeat_presence: unauthenticated';
  END IF;
  SELECT * INTO v_row FROM public.user_presence
   WHERE user_id = v_uid AND device_id = p_device_id FOR UPDATE;
  IF v_row.modified_hlc > p_hlc THEN
    RETURN jsonb_build_object('accepted', false, 'reason', 'stale_hlc');
  END IF;
  INSERT INTO public.user_presence
        (user_id, device_id, track_id, position_ms, is_playing,
         queue_ids, modified_hlc, updated_at)
  VALUES (v_uid, p_device_id, p_track_id, p_position_ms, p_is_playing,
          p_queue_ids, p_hlc, now())
  ON CONFLICT (user_id, device_id) DO UPDATE
     SET track_id = excluded.track_id, position_ms = excluded.position_ms,
         is_playing = excluded.is_playing, queue_ids = excluded.queue_ids,
         modified_hlc = excluded.modified_hlc, updated_at = now();

  PERFORM public.touch_profile_last_active(v_uid);
  RETURN jsonb_build_object('accepted', true);
END $$;

-- 4. Update sync_push with (modified_hlc, modified_by) LWW for search & history
--    and throttled profiles.last_active_at stamping.
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
             SET deleted_at = now(), modified_hlc = v_hlc, modified_by = v_device_id
           WHERE user_id = v_uid AND query = v_key
             AND (modified_hlc IS NULL OR (modified_hlc, modified_by) < (v_hlc, v_device_id));
          GET DIAGNOSTICS v_rows = ROW_COUNT;
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
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
          IF v_rows = 0 THEN
            v_status := 'stale';
          END IF;
        END IF;

      ELSIF v_entity = 'history' THEN
        INSERT INTO public.user_listening_history
              (user_id, client_event_id, track_id, artist_id, album_id,
               title, artist_name, album_name, listened_duration_sec, created_at,
               modified_hlc, modified_by)
        VALUES (v_uid, (v_d ->> 'client_event_id')::uuid, v_d ->> 'track_id',
                v_d ->> 'artist_id', v_d ->> 'album_id',
                v_d ->> 'title', v_d ->> 'artist_name', v_d ->> 'album_name',
                coalesce((v_d ->> 'listened_duration_sec')::int, 0),
                coalesce((v_d ->> 'listened_at')::timestamptz, now()),
                v_hlc, v_device_id)
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

  PERFORM public.touch_profile_last_active(v_uid);

  RETURN jsonb_build_object(
    'applied',  v_applied,
    'rejected', v_rejected,
    'cursor',   (SELECT coalesce(max(id), 0) FROM public.sync_changelog
                  WHERE user_id = v_uid AND origin = v_device_id),
    'results',  v_results);
END $function$;
