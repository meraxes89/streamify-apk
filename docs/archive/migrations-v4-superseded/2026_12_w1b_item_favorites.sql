-- ============================================================================
-- 2026_12_w1b_item_favorites.sql — W1-B: album & artist favorites.
--
-- Extends the #191 favorites ledger beyond tracks: user_likes is
-- track-shaped (track_id FK), so album/artist favorites get their own
-- RLS-fenced table, consumed by the feishin fork's snapshot load
-- (front-end/feishin/src/renderer/api/streamify/cloud.ts
-- fetchItemFavorites) and the createFavorite / deleteFavorite album and
-- artist branches (persist across devices, exactly like track likes).
--
-- Idempotent: safe to re-apply; schema.sql defines the same table for
-- fresh installs.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_item_favorites (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    item_type TEXT NOT NULL CHECK (item_type IN ('album', 'artist')),
    -- The feishin entity id (the alb_/art_ deterministic derivation over
    -- (album, artist) / artist keys — see front-end/feishin ids.ts).
    item_id TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, item_type, item_id)
);

CREATE INDEX IF NOT EXISTS idx_user_item_favorites_user
ON public.user_item_favorites (user_id, created_at DESC);

ALTER TABLE public.user_item_favorites ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users manage own item favorites" ON public.user_item_favorites;
CREATE POLICY "Users manage own item favorites" ON public.user_item_favorites
FOR ALL USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Anyone can view item favorites" ON public.user_item_favorites;
CREATE POLICY "Anyone can view item favorites" ON public.user_item_favorites
FOR SELECT USING (TRUE);
