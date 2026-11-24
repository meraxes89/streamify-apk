-- Migration: Phase P4 Option 1 - Zero-Downtime View Bridge and Decommissioning
-- Author: Elite Backend Architecture Team
-- Date: 2026-11-24
-- Context: Drop legacy physical tracks table, install backward-compatible updatable view
--          Drop profiles.active_session (WAL churning hot-row column superseded by user_presence)
--          Drop user_search_history.is_deleted (superseded by deleted_at timestamptz)

BEGIN;

-- 1. Drop redundant is_deleted from user_search_history
ALTER TABLE public.user_search_history DROP COLUMN IF EXISTS is_deleted;

-- 2. Drop hot-row active_session from profiles
ALTER TABLE public.profiles DROP COLUMN IF EXISTS active_session;

-- 3. Replace physical tracks table with backward-compatible view
DROP TABLE IF EXISTS public.tracks CASCADE;

CREATE OR REPLACE VIEW public.tracks AS
SELECT 
  external_id AS id,
  title,
  coalesce(artist_name, '') AS artist,
  album_name AS album,
  duration_sec,
  thumb_url AS cover_url,
  0::integer AS play_count,
  0::integer AS like_count,
  created_at,
  NULL::uuid AS created_by
FROM public.track_index
WHERE source = 'yt';

-- 4. Enable backward-compatible writes via INSTEAD OF trigger
CREATE OR REPLACE FUNCTION public.tg_tracks_view_upsert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
    INSERT INTO public.track_index (
      id,
      source,
      external_id,
      title,
      artist_name,
      album_name,
      duration_sec,
      thumb_url,
      created_at,
      updated_at
    ) VALUES (
      'yt:' || NEW.id,
      'yt',
      NEW.id,
      NEW.title,
      coalesce(NEW.artist, ''),
      NEW.album,
      NEW.duration_sec,
      NEW.cover_url,
      coalesce(NEW.created_at, now()),
      now()
    )
    ON CONFLICT (id) DO UPDATE SET
      title = EXCLUDED.title,
      artist_name = EXCLUDED.artist_name,
      album_name = EXCLUDED.album_name,
      duration_sec = EXCLUDED.duration_sec,
      thumb_url = EXCLUDED.thumb_url,
      updated_at = now();
    RETURN NEW;
  ELSIF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_tracks_view_upsert ON public.tracks;
CREATE TRIGGER trg_tracks_view_upsert
INSTEAD OF INSERT OR UPDATE OR DELETE ON public.tracks
FOR EACH ROW EXECUTE FUNCTION public.tg_tracks_view_upsert();

-- 5. Grant access permissions to anon, authenticated, and service_role
GRANT SELECT, INSERT, UPDATE, DELETE ON public.tracks TO anon, authenticated, service_role;

-- 6. Reload PostgREST schema cache
NOTIFY pgrst, 'reload schema';

COMMIT;
