-- ============================================================================
-- Migration: 2026_11_p0_sec_and_bloat_lockdown.sql
-- Description: Phase P0 — RLS lockdown, drop cascading FK, and purge non-sovereign media/vector bloat
-- Traceability: docs/BACKEND_ARCHITECTURE_RFC.md (Findings F-01, F-02, F-12)
-- ============================================================================

-- 1. Close world-read leaks on user_likes (F-12)
DROP POLICY IF EXISTS "Anyone can view likes" ON public.user_likes;
DROP POLICY IF EXISTS "Users manage own likes" ON public.user_likes;
CREATE POLICY "likes_owner_select" ON public.user_likes
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "likes_owner_write" ON public.user_likes
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- 2. Close world-read leaks on user_item_favorites (F-12)
DROP POLICY IF EXISTS "Item favorites are viewable by everyone" ON public.user_item_favorites;
DROP POLICY IF EXISTS "Users manage own item favorites" ON public.user_item_favorites;
CREATE POLICY "favorites_owner_select" ON public.user_item_favorites
  FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "favorites_owner_write" ON public.user_item_favorites
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- 3. Close private playlist membership leak on playlist_tracks (F-12)
DROP POLICY IF EXISTS "View playlist tracks" ON public.playlist_tracks;
DROP POLICY IF EXISTS "Modify playlist tracks" ON public.playlist_tracks;
CREATE POLICY "playlist_tracks_read" ON public.playlist_tracks
  FOR SELECT USING (
    EXISTS (SELECT 1 FROM public.playlists p
             WHERE p.id = playlist_id
               AND (p.is_public
                    OR p.user_id = auth.uid()
                    OR auth.uid() = ANY (p.collaborator_ids))));
CREATE POLICY "playlist_tracks_write" ON public.playlist_tracks
  FOR ALL USING (
    EXISTS (SELECT 1 FROM public.playlists p
             WHERE p.id = playlist_id
               AND (p.user_id = auth.uid()
                    OR auth.uid() = ANY (p.collaborator_ids))))
  WITH CHECK (
    EXISTS (SELECT 1 FROM public.playlists p
             WHERE p.id = playlist_id
               AND (p.user_id = auth.uid()
                    OR auth.uid() = ANY (p.collaborator_ids))));

-- 4. Enforce is_private on profiles (F-12)
DROP POLICY IF EXISTS "Public profiles are viewable by everyone" ON public.profiles;
CREATE POLICY "profiles_select_scoped" ON public.profiles
  FOR SELECT USING ((NOT is_private) OR auth.uid() = id OR public.is_admin());

-- 5. Drop cascading foreign key from user_likes to tracks (F-01 safeguard)
ALTER TABLE public.user_likes DROP CONSTRAINT IF EXISTS user_likes_track_id_fkey;

-- 6. Purge non-sovereign media and vector bloat from tracks (F-02, Invariant 1)
DROP POLICY IF EXISTS "Authenticated users can update track lyrics (rate-limited)" ON public.tracks;
DROP INDEX IF EXISTS public.idx_tracks_embedding_hnsw;

ALTER TABLE public.tracks
  DROP COLUMN IF EXISTS stream_url,
  DROP COLUMN IF EXISTS lyrics,
  DROP COLUMN IF EXISTS embedding,
  DROP COLUMN IF EXISTS bpm,
  DROP COLUMN IF EXISTS key_signature;
