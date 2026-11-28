-- ============================================================================
-- Phase P11: S11 P0 — Preserve playlist_id & track_id on playlist_track deletes
-- ============================================================================
-- Previously tg_bump_changelog() set v_row := null on all DELETE / soft-delete
-- operations. For playlist_track, v_key is the server-generated row UUID
-- (playlist_tracks.id), which remote clients do not store. With payload = NULL,
-- clients could not identify which (playlist_id, track_id) pair was removed.
-- Retain {playlist_id, track_id} in payload for playlist_track 'D' ops.
-- ============================================================================

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
