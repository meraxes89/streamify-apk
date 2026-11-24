-- ============================================================================
-- Migration: 2026_11_p1_additive_schema_staging.sql
-- Description: Phase P1 — Additive DDL, track_index staging, and sync ledger tables
-- Traceability: docs/BACKEND_ARCHITECTURE_RFC.md (Listings 1, 2, 3)
-- ============================================================================

-- 1. Catalog Layer — track_index (Listing 1)
CREATE TABLE IF NOT EXISTS public.track_index (
  id            TEXT        PRIMARY KEY,          -- canonical key '{source}:{external_id}'
  source        TEXT        NOT NULL,
  external_id   TEXT        NOT NULL,
  title         TEXT        NOT NULL,
  artist_name   TEXT,
  album_name    TEXT,
  duration_sec  INTEGER,
  thumb_url     TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT track_index_source_chk CHECK (source IN ('yt', 'spotify', 'piped', 'local')),
  CONSTRAINT track_index_dedup UNIQUE (source, external_id)
);

-- One-time idempotent backfill from tracks
INSERT INTO public.track_index (id, source, external_id, title, artist_name,
                                album_name, duration_sec, thumb_url)
SELECT 'yt:' || t.id, 'yt', t.id, t.title, t.artist, t.album,
       t.duration_sec, t.cover_url
  FROM public.tracks t
ON CONFLICT (source, external_id) DO NOTHING;

ALTER TABLE public.track_index ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "track_index_read" ON public.track_index;
CREATE POLICY "track_index_read" ON public.track_index
  FOR SELECT USING (auth.role() = 'authenticated');

-- 2. User Graph Layer — Add sync columns & snapshots (Listing 2)
ALTER TABLE public.user_likes
  DROP CONSTRAINT IF EXISTS user_likes_track_id_fkey,
  ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT,
  ADD COLUMN IF NOT EXISTS snapshot     JSONB;

ALTER TABLE public.user_item_favorites
  ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT;

ALTER TABLE public.playlists
  ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT;

ALTER TABLE public.playlist_tracks
  ALTER COLUMN playlist_id SET NOT NULL,
  DROP CONSTRAINT IF EXISTS uq_playlist_track,
  ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT;

ALTER TABLE public.user_search_history
  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

UPDATE public.user_search_history
   SET deleted_at = searched_at
 WHERE is_deleted AND deleted_at IS NULL;

ALTER TABLE public.user_listening_history
  ADD COLUMN IF NOT EXISTS client_event_id UUID,
  ADD COLUMN IF NOT EXISTS title        TEXT,
  ADD COLUMN IF NOT EXISTS artist_name  TEXT,
  ADD COLUMN IF NOT EXISTS album_name   TEXT,
  ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ;

ALTER TABLE public.user_settings
  ADD COLUMN IF NOT EXISTS modified_hlc BIGINT,
  ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

-- 3. Sync Ledger, Device Registry and Presence (Listing 3)
CREATE TABLE IF NOT EXISTS public.user_devices (
  user_id     UUID        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
  device_id   TEXT        NOT NULL,
  platform    TEXT,
  app_version TEXT,
  last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS public.sync_changelog (
  id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      UUID        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
  entity       TEXT        NOT NULL,
  op           TEXT        NOT NULL CHECK (op IN ('I', 'U', 'D')),
  row_key      TEXT        NOT NULL,
  payload      JSONB,
  occurred_hlc BIGINT,
  origin       TEXT        NOT NULL DEFAULT 'server',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT changelog_entity_chk CHECK (entity IN
    ('like', 'favorite', 'playlist', 'playlist_track', 'search', 'history', 'settings'))
);

CREATE TABLE IF NOT EXISTS public.user_presence (
  user_id      UUID        NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
  device_id    TEXT        NOT NULL,
  track_id     TEXT,
  position_ms  BIGINT      NOT NULL DEFAULT 0,
  is_playing   BOOLEAN     NOT NULL DEFAULT false,
  queue_ids    JSONB       NOT NULL DEFAULT '[]'::jsonb,
  modified_hlc BIGINT      NOT NULL DEFAULT 0,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, device_id)
);

ALTER TABLE public.user_devices   ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_changelog ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_presence  ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "changelog_owner" ON public.sync_changelog;
CREATE POLICY "changelog_owner" ON public.sync_changelog
  FOR SELECT USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "devices_owner" ON public.user_devices;
CREATE POLICY "devices_owner" ON public.user_devices
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "presence_owner" ON public.user_presence;
CREATE POLICY "presence_owner" ON public.user_presence
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
