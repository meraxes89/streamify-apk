-- ============================================================================
-- 2026_11_sync_v3_user_graph.sql — User Graph Alignment & Cross-Device Continuity
--
-- Applied to live Supabase project dbgtvzgwixerhnwvhtil on 2026-11-24.
--
-- Summary of operations:
--   1. playlists: add youtube_playlist_id for YouTube Music import continuity.
--   2. user_listening_history: add album_id for album-level analytics in Wrapped.
--   3. user_item_favorites: extend constraint to include 'podcast'.
--   4. profiles: add active_session JSONB for cross-device playback hand-off.
-- ============================================================================

-- 1. Extend playlists to link YouTube Music playlists
ALTER TABLE public.playlists 
ADD COLUMN IF NOT EXISTS youtube_playlist_id TEXT;

-- 2. Extend user_listening_history with album_id & artist_id for Wrapped Top Albums & Top Artists
ALTER TABLE public.user_listening_history 
ADD COLUMN IF NOT EXISTS album_id TEXT;

ALTER TABLE public.user_listening_history 
ADD COLUMN IF NOT EXISTS artist_id TEXT;

-- 3. Extend user_item_favorites constraint to include podcasts
ALTER TABLE public.user_item_favorites 
DROP CONSTRAINT IF EXISTS user_item_favorites_item_type_check;

ALTER TABLE public.user_item_favorites 
ADD CONSTRAINT user_item_favorites_item_type_check 
CHECK (item_type IN ('artist', 'album', 'podcast'));

-- 4. Add active_session JSONB to profiles for playback session hand-off
ALTER TABLE public.profiles 
ADD COLUMN IF NOT EXISTS active_session JSONB DEFAULT NULL;
