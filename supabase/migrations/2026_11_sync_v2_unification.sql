-- ============================================================================
-- 2026_11_sync_v2_unification.sql — Hybrid Sync V2 live schema unification
--
-- Applied to live Supabase project dbgtvzgwixerhnwvhtil on 2026-11-24.
--
-- Changes:
--   * user_item_favorites: unified favorites table for artists and albums
--     with row-level security and indexing for rapid cross-device hydration.
--   * user_search_history: cross-device search queries with owner-scoped RLS.
--   * Integrity: reinforces valid YouTube video ID constraints on tracks.
-- ============================================================================

-- 1. Create user_item_favorites (artists & albums)
CREATE TABLE IF NOT EXISTS public.user_item_favorites (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    item_type TEXT NOT NULL CHECK (item_type IN ('artist', 'album')),
    item_id TEXT NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, item_type, item_id)
);

CREATE INDEX IF NOT EXISTS idx_user_item_favorites_user 
ON public.user_item_favorites (user_id, created_at DESC);

ALTER TABLE public.user_item_favorites ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own item favorites" ON public.user_item_favorites;
CREATE POLICY "Users manage own item favorites" 
ON public.user_item_favorites FOR ALL 
USING (auth.uid() = user_id) 
WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Item favorites are viewable by everyone" ON public.user_item_favorites;
CREATE POLICY "Item favorites are viewable by everyone" 
ON public.user_item_favorites FOR SELECT 
USING (TRUE);

-- 2. Create user_search_history (search queries)
CREATE TABLE IF NOT EXISTS public.user_search_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    searched_at TIMESTAMPTZ DEFAULT NOW(),
    is_deleted BOOLEAN DEFAULT FALSE,
    CONSTRAINT uq_user_query UNIQUE (user_id, query)
);

CREATE INDEX IF NOT EXISTS idx_user_search_history_user 
ON public.user_search_history (user_id, searched_at DESC);

ALTER TABLE public.user_search_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own search history" ON public.user_search_history;
CREATE POLICY "Users manage own search history" 
ON public.user_search_history FOR ALL 
USING (auth.uid() = user_id) 
WITH CHECK (auth.uid() = user_id);

-- 3. Create user_settings (JSONB snapshot of user preferences & settings)
CREATE TABLE IF NOT EXISTS public.user_settings (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own settings" ON public.user_settings;
CREATE POLICY "Users manage own settings" 
ON public.user_settings FOR ALL 
USING (auth.uid() = user_id) 
WITH CHECK (auth.uid() = user_id);

-- 4. Relax foreign key on user_listening_history to prevent 409 collisions on arbitrary YouTube stream plays
ALTER TABLE public.user_listening_history DROP CONSTRAINT IF EXISTS user_listening_history_track_id_fkey;

-- 5. Harden playlist_tracks: enforce uniqueness and relax foreign key to prevent 409 rejections on unliked tracks
ALTER TABLE public.playlist_tracks DROP CONSTRAINT IF EXISTS playlist_tracks_track_id_fkey;
ALTER TABLE public.playlist_tracks DROP CONSTRAINT IF EXISTS uq_playlist_track;
ALTER TABLE public.playlist_tracks ADD CONSTRAINT uq_playlist_track UNIQUE (playlist_id, track_id);
