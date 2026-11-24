-- ============================================================================
-- Migration: 2026_11_p2_rpcs_triggers_indexes.sql
-- Description: Phase P2 — High-velocity index plan, change-log triggers, sync RPCs, views, and GC cron
-- Traceability: docs/BACKEND_ARCHITECTURE_RFC.md (Listings 4, 5, 6, 8)
-- ============================================================================

-- ============================================================================
-- 1. Index Plan (Listing 8)
-- ============================================================================
CREATE INDEX IF NOT EXISTS idx_history_user_time
  ON public.user_listening_history (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_history_brin_time
  ON public.user_listening_history USING BRIN (created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_history_client_event
  ON public.user_listening_history (user_id, client_event_id);

CREATE INDEX IF NOT EXISTS idx_likes_chrono
  ON public.user_likes (user_id, created_at DESC)
  WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_playlist_position
  ON public.playlist_tracks (playlist_id, position)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_changelog_cursor
  ON public.sync_changelog (user_id, id);

CREATE INDEX IF NOT EXISTS idx_presence_recent
  ON public.user_presence (user_id, updated_at DESC);

-- ============================================================================
-- 2. Change-Log Trigger (Listing 4)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.tg_bump_changelog()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
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
END $$;

DROP TRIGGER IF EXISTS tg_likes_changelog ON public.user_likes;
CREATE TRIGGER tg_likes_changelog AFTER INSERT OR UPDATE OR DELETE
  ON public.user_likes FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('like');

DROP TRIGGER IF EXISTS tg_favorites_changelog ON public.user_item_favorites;
CREATE TRIGGER tg_favorites_changelog AFTER INSERT OR UPDATE OR DELETE
  ON public.user_item_favorites FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('favorite');

DROP TRIGGER IF EXISTS tg_playlists_changelog ON public.playlists;
CREATE TRIGGER tg_playlists_changelog AFTER INSERT OR UPDATE OR DELETE
  ON public.playlists FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('playlist');

DROP TRIGGER IF EXISTS tg_playlist_tracks_changelog ON public.playlist_tracks;
CREATE TRIGGER tg_playlist_tracks_changelog AFTER INSERT OR UPDATE OR DELETE
  ON public.playlist_tracks FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('playlist_track');

DROP TRIGGER IF EXISTS tg_search_changelog ON public.user_search_history;
CREATE TRIGGER tg_search_changelog AFTER INSERT OR UPDATE OR DELETE
  ON public.user_search_history FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('search');

DROP TRIGGER IF EXISTS tg_history_changelog ON public.user_listening_history;
CREATE TRIGGER tg_history_changelog AFTER INSERT OR DELETE
  ON public.user_listening_history FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('history');

DROP TRIGGER IF EXISTS tg_settings_changelog ON public.user_settings;
CREATE TRIGGER tg_settings_changelog AFTER INSERT OR UPDATE
  ON public.user_settings FOR EACH ROW
  EXECUTE FUNCTION public.tg_bump_changelog('settings');

-- ============================================================================
-- 3. Sync RPCs (Listing 5)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.sync_push(p_ops jsonb, p_device_id text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
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

      ELSIF v_entity = 'favorite' THEN
        IF v_op = 'D' THEN
          UPDATE public.user_item_favorites
             SET deleted_at = now(), modified_hlc = v_hlc
           WHERE user_id = v_uid AND item_type = v_d ->> 'item_type'
             AND item_id = v_d ->> 'item_id'
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.user_item_favorites
                (user_id, item_type, item_id, metadata, modified_hlc, deleted_at)
          VALUES (v_uid, v_d ->> 'item_type', v_d ->> 'item_id',
                  v_d -> 'meta', v_hlc, null)
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
           WHERE id = v_key::uuid
             AND playlist_id IN (SELECT id FROM public.playlists
                                  WHERE user_id = v_uid AND deleted_at IS NULL)
             AND (modified_hlc IS NULL OR modified_hlc <= v_hlc);
        ELSE
          INSERT INTO public.playlist_tracks
                (id, playlist_id, track_id, position, added_by, added_at,
                 modified_hlc, deleted_at)
          VALUES (v_key::uuid, (v_d ->> 'playlist_id')::uuid, v_d ->> 'track_id',
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

      IF v_entity IN ('like', 'history', 'playlist_track') AND v_d ? 'catalog' THEN
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
END $$;

CREATE OR REPLACE FUNCTION public.sync_pull(
  p_since bigint, p_limit int default 500, p_device_id text default null)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid  uuid := auth.uid();
  v_ops  jsonb := '[]'::jsonb;
  v_last bigint := p_since;
  v_cap  int := least(coalesce(p_limit, 500), 1000);
  rec    record;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'sync_pull: unauthenticated';
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
END $$;

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
  RETURN jsonb_build_object('accepted', true);
END $$;

CREATE OR REPLACE FUNCTION public.get_latest_presence()
RETURNS SETOF public.user_presence
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
STABLE
AS $$
  SELECT * FROM public.user_presence
   WHERE user_id = auth.uid()
   ORDER BY updated_at DESC;
$$;

-- ============================================================================
-- 4. Views and GC Schedule (Listing 6)
-- ============================================================================
CREATE OR REPLACE VIEW public.v_active_playlist_tracks AS
SELECT pt.id, pt.playlist_id, pt.track_id, pt.position, pt.added_by, pt.added_at
  FROM public.playlist_tracks pt
 WHERE pt.deleted_at IS NULL;

CREATE OR REPLACE VIEW public.v_user_graph_summary AS
SELECT p.id as user_id,
       (SELECT count(*) FROM public.user_likes l
         WHERE l.user_id = p.id AND l.deleted_at IS NULL)        AS liked_tracks,
       (SELECT count(*) FROM public.user_item_favorites f
         WHERE f.user_id = p.id AND f.deleted_at IS NULL)        AS favorites,
       (SELECT count(*) FROM public.user_listening_history h
         WHERE h.user_id = p.id AND h.deleted_at IS NULL)        AS play_events,
       (SELECT coalesce(sum(h.listened_duration_sec), 0)
          FROM public.user_listening_history h
         WHERE h.user_id = p.id AND h.deleted_at IS NULL)        AS listened_seconds
  FROM public.profiles p;

DO $$ BEGIN
  PERFORM cron.unschedule('streamify_gc_daily');
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

SELECT cron.schedule('streamify_gc_daily', '17 3 * * *', $gc$
  DELETE FROM public.sync_changelog
   WHERE created_at < now() - interval '90 days';
  DELETE FROM public.user_presence
   WHERE updated_at  < now() - interval '7 days';
  DELETE FROM public.user_likes
   WHERE deleted_at  < now() - interval '90 days';
  DELETE FROM public.playlist_tracks
   WHERE deleted_at  < now() - interval '90 days';
  DELETE FROM public.user_item_favorites
   WHERE deleted_at  < now() - interval '90 days';
$gc$);
