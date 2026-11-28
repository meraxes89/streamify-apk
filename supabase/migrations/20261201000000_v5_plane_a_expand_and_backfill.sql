-- ============================================================================
-- ADR-011 / Plane A: Delta Sync v5 — Schema Expansion & Deterministic Backfill
-- Target: Supabase PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil)
-- ============================================================================

BEGIN;

-- 1. Sync Infrastructure Tables & Missing Legacy Columns
ALTER TABLE public.user_settings
    ADD COLUMN IF NOT EXISTS key_hlcs JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE IF NOT EXISTS public.user_sync_state (
    user_id            UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    head_rev           BIGINT NOT NULL DEFAULT 0 CHECK (head_rev >= 0),
    min_available_rev  BIGINT NOT NULL DEFAULT 0 CHECK (min_available_rev >= 0 AND min_available_rev <= head_rev),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.sync_changelog_v5 (
    user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rev               BIGINT NOT NULL CHECK (rev > 0),
    mutation_id       UUID NOT NULL,
    entity_type       TEXT NOT NULL CHECK (entity_type IN (
                          'user_like',
                          'user_library_track',
                          'user_item_favorite',
                          'playlist',
                          'playlist_track',
                          'user_setting',
                          'user_podcast_episode_state'
                      )),
    entity_key        TEXT NOT NULL,
    op                TEXT NOT NULL CHECK (op IN ('upsert', 'delete')),
    occurred_hlc      BIGINT NOT NULL CHECK (occurred_hlc > 0),
    origin_device_id  TEXT NOT NULL CHECK (char_length(origin_device_id) BETWEEN 1 AND 128),
    payload           JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, rev)
);

CREATE INDEX IF NOT EXISTS idx_sync_changelog_v5_created_at
    ON public.sync_changelog_v5 (created_at);

CREATE TABLE IF NOT EXISTS public.sync_mutations_applied (
    user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    mutation_id    UUID NOT NULL,
    entity_type    TEXT NOT NULL,
    entity_key     TEXT NOT NULL,
    status         TEXT NOT NULL CHECK (status IN ('applied', 'stale')),
    assigned_rev   BIGINT,
    applied_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, mutation_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_mutations_applied_at
    ON public.sync_mutations_applied (applied_at);

-- 2. Deterministic 64-bit HLC Backfill Helper
--    Clamped to [2020-01-01, now()] so backfilled rows never outrank real client writes.
CREATE OR REPLACE FUNCTION public._v5_backfill_hlc(p_ts TIMESTAMPTZ)
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT (
        GREATEST(
            1577836800000::BIGINT,
            LEAST(
                (EXTRACT(EPOCH FROM COALESCE(p_ts, '2024-01-01T00:00:00Z'::timestamptz)) * 1000)::BIGINT,
                (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT
            )
        ) << 22
    );
$$;

-- 3. Evolve & Backfill user_likes (preserves deleted_at for view compatibility)
ALTER TABLE public.user_likes
    ADD COLUMN IF NOT EXISTS deleted    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE public.user_likes
SET
    deleted      = (deleted_at IS NOT NULL),
    modified_hlc = CASE
        WHEN modified_hlc IS NULL OR modified_hlc <= 0
            THEN public._v5_backfill_hlc(COALESCE(deleted_at, created_at))
        ELSE modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill'),
    updated_at   = COALESCE(deleted_at, created_at, now());

ALTER TABLE public.user_likes
    ALTER COLUMN modified_hlc SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_user_likes_hlc,
    ADD CONSTRAINT chk_user_likes_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_user_likes_by,
    ADD CONSTRAINT chk_user_likes_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

CREATE INDEX IF NOT EXISTS idx_user_likes_active_bootstrap
    ON public.user_likes (user_id, track_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_user_likes_gc
    ON public.user_likes (updated_at)
    WHERE deleted;

-- 4. Evolve & Backfill user_library_tracks
ALTER TABLE public.user_library_tracks
    ADD COLUMN IF NOT EXISTS source     TEXT NOT NULL DEFAULT 'library',
    ADD COLUMN IF NOT EXISTS metadata   JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS deleted    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE public.user_library_tracks
SET
    deleted      = (deleted_at IS NOT NULL),
    modified_hlc = CASE
        WHEN modified_hlc IS NULL OR modified_hlc <= 0
            THEN public._v5_backfill_hlc(COALESCE(deleted_at, added_at))
        ELSE modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill'),
    updated_at   = COALESCE(deleted_at, added_at, now());

ALTER TABLE public.user_library_tracks
    ALTER COLUMN modified_hlc SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_user_library_tracks_hlc,
    ADD CONSTRAINT chk_user_library_tracks_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_user_library_tracks_by,
    ADD CONSTRAINT chk_user_library_tracks_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

CREATE INDEX IF NOT EXISTS idx_user_library_tracks_active_bootstrap
    ON public.user_library_tracks (user_id, track_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_user_library_tracks_gc
    ON public.user_library_tracks (updated_at)
    WHERE deleted;

-- 5. Evolve & Backfill user_item_favorites
ALTER TABLE public.user_item_favorites
    ADD COLUMN IF NOT EXISTS deleted    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE public.user_item_favorites
SET
    deleted      = (deleted_at IS NOT NULL),
    metadata     = COALESCE(metadata, '{}'::jsonb),
    modified_hlc = CASE
        WHEN modified_hlc IS NULL OR modified_hlc <= 0
            THEN public._v5_backfill_hlc(COALESCE(deleted_at, created_at))
        ELSE modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill'),
    updated_at   = COALESCE(deleted_at, created_at, now());

ALTER TABLE public.user_item_favorites
    ALTER COLUMN modified_hlc SET NOT NULL,
    ALTER COLUMN metadata SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_user_item_favorites_hlc,
    ADD CONSTRAINT chk_user_item_favorites_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_user_item_favorites_by,
    ADD CONSTRAINT chk_user_item_favorites_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

CREATE INDEX IF NOT EXISTS idx_user_item_favorites_active_bootstrap
    ON public.user_item_favorites (user_id, item_type COLLATE "C", item_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_user_item_favorites_gc
    ON public.user_item_favorites (updated_at)
    WHERE deleted;

-- 6. Evolve & Backfill playlists
ALTER TABLE public.playlists
    ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false;

UPDATE public.playlists
SET
    deleted      = (deleted_at IS NOT NULL),
    description  = COALESCE(description, ''),
    is_public    = COALESCE(is_public, false),
    modified_hlc = CASE
        WHEN modified_hlc IS NULL OR modified_hlc <= 0
            THEN public._v5_backfill_hlc(COALESCE(deleted_at, updated_at, created_at))
        ELSE modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill'),
    updated_at   = COALESCE(updated_at, created_at, now());

ALTER TABLE public.playlists
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN description SET DEFAULT '',
    ALTER COLUMN description SET NOT NULL,
    ALTER COLUMN is_public SET DEFAULT false,
    ALTER COLUMN is_public SET NOT NULL,
    ALTER COLUMN modified_hlc SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_playlists_hlc,
    ADD CONSTRAINT chk_playlists_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_playlists_by,
    ADD CONSTRAINT chk_playlists_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.playlists'::regclass
          AND conname = 'playlists_id_user_id_key'
    ) THEN
        ALTER TABLE public.playlists ADD CONSTRAINT playlists_id_user_id_key UNIQUE (id, user_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_playlists_active_bootstrap
    ON public.playlists (user_id, id)
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_playlists_gc
    ON public.playlists (updated_at)
    WHERE deleted;

-- 7. Evolve playlist_tracks (0 live rows -> clean PK/FK & fractional sort_key upgrade)
ALTER TABLE public.playlist_tracks
    DROP CONSTRAINT IF EXISTS playlist_tracks_pkey,
    DROP CONSTRAINT IF EXISTS playlist_tracks_playlist_id_fkey;

ALTER TABLE public.playlist_tracks
    ADD COLUMN IF NOT EXISTS user_id    UUID,
    ADD COLUMN IF NOT EXISTS sort_key   TEXT COLLATE "C" NOT NULL DEFAULT 'V',
    ADD COLUMN IF NOT EXISTS deleted    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Defensive backfill if any row exists
UPDATE public.playlist_tracks pt
SET
    user_id      = p.user_id,
    added_by     = COALESCE(pt.added_by, p.user_id),
    deleted      = (pt.deleted_at IS NOT NULL),
    modified_hlc = CASE
        WHEN pt.modified_hlc IS NULL OR pt.modified_hlc <= 0
            THEN public._v5_backfill_hlc(COALESCE(pt.deleted_at, pt.added_at))
        ELSE pt.modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(pt.modified_by), ''), 'legacy_backfill'),
    updated_at   = COALESCE(pt.deleted_at, pt.added_at, now())
FROM public.playlists p
WHERE pt.playlist_id = p.id;

ALTER TABLE public.playlist_tracks
    ALTER COLUMN track_id SET NOT NULL,
    ALTER COLUMN user_id SET NOT NULL,
    ALTER COLUMN added_by SET NOT NULL,
    ALTER COLUMN position DROP NOT NULL,
    ALTER COLUMN modified_hlc SET NOT NULL,
    ADD CONSTRAINT playlist_tracks_pkey PRIMARY KEY (playlist_id, track_id),
    ADD CONSTRAINT fk_playlist_tracks_owner
        FOREIGN KEY (playlist_id, user_id) REFERENCES public.playlists (id, user_id) ON DELETE CASCADE,
    ADD CONSTRAINT chk_playlist_tracks_sort_key
        CHECK (char_length(sort_key) BETWEEN 1 AND 128 AND sort_key ~ '^[0-9A-Za-z]+$'),
    ADD CONSTRAINT chk_playlist_tracks_hlc CHECK (modified_hlc > 0),
    ADD CONSTRAINT chk_playlist_tracks_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

CREATE INDEX IF NOT EXISTS idx_playlist_tracks_order
    ON public.playlist_tracks (playlist_id, sort_key COLLATE "C", track_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_playlist_tracks_active_bootstrap
    ON public.playlist_tracks (user_id, playlist_id, track_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_playlist_tracks_gc
    ON public.playlist_tracks (updated_at)
    WHERE deleted;

-- 8. Evolve & Backfill user_settings
UPDATE public.user_settings
SET
    modified_hlc = CASE WHEN modified_hlc IS NULL OR modified_hlc <= 0 THEN 1 ELSE modified_hlc END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'init'),
    updated_at   = COALESCE(updated_at, now());

ALTER TABLE public.user_settings
    ALTER COLUMN modified_hlc SET DEFAULT 1,
    ALTER COLUMN modified_hlc SET NOT NULL,
    ALTER COLUMN modified_by SET DEFAULT 'init',
    ALTER COLUMN modified_by SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_user_settings_hlc,
    ADD CONSTRAINT chk_user_settings_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_user_settings_by,
    ADD CONSTRAINT chk_user_settings_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

-- 9. Evolve & Backfill user_podcast_episode_state
ALTER TABLE public.user_podcast_episode_state
    ADD COLUMN IF NOT EXISTS position_ms BIGINT NOT NULL DEFAULT 0 CHECK (position_ms >= 0),
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    ADD COLUMN IF NOT EXISTS played_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS deleted     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS deleted_at  TIMESTAMPTZ;

UPDATE public.user_podcast_episode_state
SET
    position_ms  = GREATEST(0, COALESCE(position_sec, 0)::BIGINT * 1000),
    modified_hlc = CASE
        WHEN modified_hlc IS NULL OR modified_hlc <= 0
            THEN public._v5_backfill_hlc(updated_at)
        ELSE modified_hlc
    END,
    modified_by  = COALESCE(NULLIF(btrim(modified_by), ''), 'legacy_backfill');

ALTER TABLE public.user_podcast_episode_state
    DROP CONSTRAINT IF EXISTS chk_podcast_state_hlc,
    ADD CONSTRAINT chk_podcast_state_hlc CHECK (modified_hlc > 0),
    DROP CONSTRAINT IF EXISTS chk_podcast_state_by,
    ADD CONSTRAINT chk_podcast_state_by CHECK (char_length(modified_by) BETWEEN 1 AND 128);

CREATE INDEX IF NOT EXISTS idx_podcast_state_active_bootstrap
    ON public.user_podcast_episode_state (user_id, episode_id COLLATE "C")
    WHERE NOT deleted;

CREATE INDEX IF NOT EXISTS idx_podcast_state_gc
    ON public.user_podcast_episode_state (updated_at)
    WHERE deleted;

-- 10. Seed user_sync_state for all existing users with Plane A data
INSERT INTO public.user_sync_state (user_id, head_rev, min_available_rev)
SELECT DISTINCT u.user_id, 0, 0
FROM (
    SELECT user_id FROM public.user_likes
    UNION SELECT user_id FROM public.user_library_tracks
    UNION SELECT user_id FROM public.user_item_favorites
    UNION SELECT user_id FROM public.playlists
    UNION SELECT user_id FROM public.user_settings
    UNION SELECT user_id FROM public.user_podcast_episode_state
) u
ON CONFLICT (user_id) DO NOTHING;

COMMIT;
