-- =========================================================================
-- Phase B: Data Plane V4.1 DDL & Playlist / Library Track Delta Synchronization
-- Migrations: V4.1-01, V4.1-02, V4.1-04, V4.1-05, and RPC enhancements
-- =========================================================================

-- V4.1-01: Widen user_item_favorites to cover playlists
ALTER TABLE public.user_item_favorites
  DROP CONSTRAINT IF EXISTS user_item_favorites_item_type_check;

ALTER TABLE public.user_item_favorites
  ADD CONSTRAINT user_item_favorites_item_type_check
  CHECK (item_type = ANY (ARRAY['artist','album','podcast','playlist']));

-- V4.1-02: New user_library_tracks (in-library state distinct from likes)
CREATE TABLE IF NOT EXISTS public.user_library_tracks (
  user_id      uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  track_id     text NOT NULL,
  added_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz,
  modified_hlc bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, track_id)
);

CREATE INDEX IF NOT EXISTS idx_library_tracks_chrono
  ON public.user_library_tracks (user_id, added_at DESC)
  WHERE deleted_at IS NULL;

ALTER TABLE public.user_library_tracks ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "user_library_tracks_owner" ON public.user_library_tracks;
CREATE POLICY "user_library_tracks_owner" ON public.user_library_tracks
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- V4.1-04: Extend changelog vocabulary to accept library_track and episode_state
ALTER TABLE public.sync_changelog
  DROP CONSTRAINT IF EXISTS changelog_entity_chk;

ALTER TABLE public.sync_changelog
  ADD CONSTRAINT changelog_entity_chk
  CHECK (entity = ANY (ARRAY['like','favorite','playlist','playlist_track',
                             'search','history','settings','library_track','episode_state']));

-- V4.1-05: Wrapped-ready daily rollup view (zero extra storage)
CREATE OR REPLACE VIEW public.v_user_listening_daily AS
SELECT user_id,
       date_trunc('day', created_at) AS day,
       count(*)                      AS plays,
       sum(listened_duration_sec)    AS seconds,
       count(DISTINCT track_id)      AS distinct_tracks
FROM public.user_listening_history
WHERE deleted_at IS NULL
GROUP BY user_id, date_trunc('day', created_at);

-- Enhance tg_bump_changelog() to extract key for library_track
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
              ELSE coalesce(v_row ->> 'id', v_row ->> 'track_id')
            END;

  IF tg_op = 'DELETE'
     OR (tg_op = 'UPDATE' AND new.deleted_at IS NOT NULL AND old.deleted_at IS NULL) THEN
    v_op := 'D';
    v_row := null;
  ELSE
    v_op := CASE tg_op WHEN 'INSERT' THEN 'I' ELSE 'U' END;
  END IF;

  INSERT INTO public.sync_changelog
        (user_id, entity, op, row_key, payload, occurred_hlc, origin)
  VALUES (v_user, v_entity, v_op, v_key, v_row, v_hlc, v_origin);

  RETURN coalesce(new, old);
END $function$;

-- Trigger for user_library_tracks changelog
DROP TRIGGER IF EXISTS tg_library_tracks_changelog ON public.user_library_tracks;
CREATE TRIGGER tg_library_tracks_changelog
  AFTER INSERT OR DELETE OR UPDATE ON public.user_library_tracks
  FOR EACH ROW EXECUTE FUNCTION tg_bump_changelog('library_track');

-- Enhance sync_push() RPC to support library_track and atomic playlist_track operations
CREATE OR REPLACE FUNCTION public.sync_push(p_ops jsonb, p_device_id text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
  v_uid      uuid := auth.uid();
  v_applied  int  := 0;
  v_rejected int  := 0;
  op         jsonb;
  v_entity   text;
  v_op       text;
  v_key      text;
  v_hlc      bigint;
  v_d        jsonb;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_push: unauthenticated';
  END IF;

  PERFORM set_config('streamify.device_id', coalesce(p_device_id, 'server'), true);
  INSERT INTO public.user_devices (user_id, device_id) VALUES (v_uid, p_device_id)
  ON CONFLICT (user_id, device_id) DO UPDATE SET last_seen = now();

  FOR op IN SELECT * FROM jsonb_array_elements(p_ops) LOOP
    v_entity := op ->> 'entity';
    v_op     := coalesce(op ->> 'op', 'U');
    v_key    := op ->> 'key';
    v_hlc    := coalesce((op ->> 'hlc')::bigint, 0);
    v_d      := coalesce(op -> 'data', '{}'::jsonb);

    BEGIN
      IF v_entity = 'like' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_likes
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE user_id = v_uid AND track_id = v_key
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.user_likes
                (user_id, track_id, created_at, snapshot, modified_hlc, deleted_at)
          VALUES (v_uid, v_key,
                  coalesce((v_d ->> 'created_at')::timestamptz, now()),
                  v_d -> 'meta', v_hlc, null)
          ON CONFLICT (user_id, track_id) DO UPDATE
             SET created_at   = excluded.created_at,
                 snapshot     = coalesce(excluded.snapshot, public.user_likes.snapshot),
                 modified_hlc = excluded.modified_hlc,
                 deleted_at   = null
           WHERE public.user_likes.modified_hlc IS NULL
              OR public.user_likes.modified_hlc <= excluded.modified_hlc;
        END IF;

      ELSIF v_entity = 'library_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_library_tracks
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE user_id = v_uid AND track_id = v_key
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.user_library_tracks
                (user_id, track_id, added_at, modified_hlc, deleted_at)
          VALUES (v_uid, v_key,
                  coalesce((v_d ->> 'added_at')::timestamptz, now()),
                  v_hlc, null)
          ON CONFLICT (user_id, track_id) DO UPDATE
             SET added_at     = excluded.added_at,
                 modified_hlc = excluded.modified_hlc,
                 deleted_at   = null
           WHERE public.user_library_tracks.modified_hlc IS NULL
              OR public.user_library_tracks.modified_hlc <= excluded.modified_hlc;
        END IF;

      ELSIF v_entity = 'favorite' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_item_favorites
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE user_id = v_uid AND item_type = (v_d ->> 'item_type')
             AND item_id = (v_d ->> 'item_id')
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.user_item_favorites
                (user_id, item_type, item_id, metadata, modified_hlc, deleted_at)
          VALUES (v_uid, v_d ->> 'item_type', v_d ->> 'item_id',
                  coalesce(v_d -> 'metadata', v_d -> 'meta'), v_hlc, null)
          ON CONFLICT (user_id, item_type, item_id) DO UPDATE
             SET metadata     = coalesce(excluded.metadata, public.user_item_favorites.metadata),
                 modified_hlc = excluded.modified_hlc,
                 deleted_at   = null
           WHERE public.user_item_favorites.modified_hlc IS NULL
              OR public.user_item_favorites.modified_hlc <= excluded.modified_hlc;
        END IF;

      ELSIF v_entity = 'playlist' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlists
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE id = v_key::uuid AND user_id = v_uid
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.playlists
                (id, user_id, name, description, cover_url, is_public,
                 youtube_playlist_id, created_at, modified_hlc, deleted_at)
          VALUES (v_key::uuid, v_uid, v_d ->> 'name', v_d ->> 'description',
                  v_d ->> 'cover_url', coalesce((v_d ->> 'is_public')::boolean, false),
                  v_d ->> 'youtube_playlist_id',
                  coalesce((v_d ->> 'created_at')::timestamptz, now()), v_hlc, null)
          ON CONFLICT (id) DO UPDATE
             SET name = excluded.name, description = excluded.description,
                 cover_url = excluded.cover_url, is_public = excluded.is_public,
                 youtube_playlist_id = excluded.youtube_playlist_id,
                 modified_hlc = excluded.modified_hlc, deleted_at = null
           WHERE public.playlists.user_id = v_uid
             AND (public.playlists.modified_hlc IS NULL
                  OR public.playlists.modified_hlc <= excluded.modified_hlc);
        END IF;

      ELSIF v_entity = 'playlist_track' THEN
        IF v_op = 'D' THEN
          UPDATE public.playlist_tracks
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE (id::text = v_key OR (track_id = v_key AND playlist_id = (v_d ->> 'playlist_id')::uuid))
             AND playlist_id IN (SELECT id FROM public.playlists
                                  WHERE user_id = v_uid AND deleted_at IS NULL)
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.playlist_tracks
                (id, playlist_id, track_id, position, added_by, added_at,
                 modified_hlc, deleted_at)
          VALUES (coalesce(nullif(v_d ->> 'id', '')::uuid, gen_random_uuid()),
                  (v_d ->> 'playlist_id')::uuid,
                  coalesce(v_d ->> 'track_id', v_key),
                  (v_d ->> 'position')::int, v_uid,
                  coalesce((v_d ->> 'added_at')::timestamptz, now()), v_hlc, null)
          ON CONFLICT (id) DO UPDATE
             SET position = excluded.position, track_id = excluded.track_id,
                 modified_hlc = excluded.modified_hlc, deleted_at = null
           WHERE (public.playlist_tracks.modified_hlc IS NULL
                  OR public.playlist_tracks.modified_hlc <= excluded.modified_hlc)
             AND EXISTS (SELECT 1 FROM public.playlists p
                          WHERE p.id = public.playlist_tracks.playlist_id
                            AND p.user_id = v_uid);
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
        INSERT INTO public.user_settings (user_id, settings, modified_hlc, updated_at)
        VALUES (v_uid,
                (SELECT coalesce(jsonb_object_agg(k, v), '{}'::jsonb)
                   FROM jsonb_each(coalesce(v_d -> 'settings', '{}'::jsonb)) AS x(k, v)
                  WHERE k NOT LIKE 'equalizer_%'),
                v_hlc, now())
        ON CONFLICT (user_id) DO UPDATE
           SET settings     = public.user_settings.settings || excluded.settings,
               modified_hlc = excluded.modified_hlc,
               updated_at   = now()
         WHERE public.user_settings.modified_hlc IS NULL
            OR public.user_settings.modified_hlc <= excluded.modified_hlc;
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
    EXCEPTION WHEN OTHERS THEN
      v_rejected := v_rejected + 1;
    END;
  END LOOP;

  RETURN jsonb_build_object(
    'applied',  v_applied,
    'rejected', v_rejected,
    'cursor',   (SELECT coalesce(max(id), 0) FROM public.sync_changelog
                  WHERE user_id = v_uid));
END $function$;
