-- P9: Close world-readable favorites regression from 2026_12_w1b_item_favorites.sql
-- and enforce strict auth.uid() = user_id ownership on user_item_favorites (#371 Tier 3 #16).

DROP POLICY IF EXISTS "Anyone can view item favorites" ON public.user_item_favorites;
DROP POLICY IF EXISTS "Users manage own item favorites" ON public.user_item_favorites;

CREATE POLICY "Users manage own item favorites"
ON public.user_item_favorites
FOR ALL
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);
