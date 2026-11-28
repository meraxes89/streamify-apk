-- ============================================================================
-- Sync v5 Plane D: Authoritative Catalog & Metadata Hydration
-- Targets: PostgreSQL 17.6 (dbgtvzgwixerhnwvhtil / S11, S21, S22)
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. Normalization & Quality Helpers
-- ============================================================================
CREATE OR REPLACE FUNCTION public._plane_d_normalize_track_id(p_raw TEXT)
RETURNS TABLE(canonical_id TEXT, source TEXT, external_id TEXT)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_clean TEXT := btrim(COALESCE(p_raw, ''));
    v_pos   INT;
    v_src   TEXT;
    v_ext   TEXT;
BEGIN
    IF v_clean = '' OR char_length(v_clean) > 256 THEN
        RETURN;
    END IF;

    v_pos := strpos(v_clean, ':');
    IF v_pos > 1 THEN
        v_src := lower(substring(v_clean FROM 1 FOR v_pos - 1));
        v_ext := substring(v_clean FROM v_pos + 1);
    ELSIF v_clean ~ '^[A-Za-z0-9_-]{11}$' THEN
        v_src := 'yt';
        v_ext := v_clean;
    ELSE
        RETURN;
    END IF;

    IF v_src NOT IN ('yt', 'ytm', 'sc', 'sp', 'apple', 'local', 'podcast')
       OR char_length(v_ext) NOT BETWEEN 1 AND 220 THEN
        RETURN;
    END IF;

    canonical_id := v_src || ':' || v_ext;
    source       := v_src;
    external_id  := v_ext;
    RETURN NEXT;
END;
$$;

CREATE OR REPLACE FUNCTION public._plane_d_is_placeholder(p_val TEXT)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT p_val IS NULL
        OR btrim(p_val) = ''
        OR lower(btrim(p_val)) IN (
            'synced track', 'unknown track', 'untitled', 'unknown',
            'unknown artist', 'various', 'loading...', 'null', 'n/a'
        );
$$;

-- ============================================================================
-- 2. Canonical Catalog Tables & Enrichment Queue
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.artist_index (
    id                  TEXT PRIMARY KEY
                            CHECK (id = source || ':' || external_id AND char_length(id) BETWEEN 3 AND 256),
    source              TEXT NOT NULL CHECK (source IN ('yt', 'ytm', 'sc', 'sp', 'apple', 'local', 'podcast')),
    external_id         TEXT NOT NULL CHECK (char_length(external_id) BETWEEN 1 AND 220),
    name                TEXT NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 256),
    thumb_url           TEXT CHECK (thumb_url IS NULL OR thumb_url ~ '^https://'),
    metadata_authority  SMALLINT NOT NULL DEFAULT 10 CHECK (metadata_authority IN (0, 10, 50, 100, 200)),
    catalog_version     INTEGER NOT NULL DEFAULT 1 CHECK (catalog_version >= 1),
    enriched_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

CREATE TABLE IF NOT EXISTS public.album_index (
    id                  TEXT PRIMARY KEY
                            CHECK (id = source || ':' || external_id AND char_length(id) BETWEEN 3 AND 256),
    source              TEXT NOT NULL CHECK (source IN ('yt', 'ytm', 'sc', 'sp', 'apple', 'local', 'podcast')),
    external_id         TEXT NOT NULL CHECK (char_length(external_id) BETWEEN 1 AND 220),
    title               TEXT NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 256),
    artist_id           TEXT REFERENCES public.artist_index(id) ON DELETE SET NULL,
    artist_name         TEXT,
    release_year        SMALLINT CHECK (release_year IS NULL OR release_year BETWEEN 1900 AND 2100),
    thumb_url           TEXT CHECK (thumb_url IS NULL OR thumb_url ~ '^https://'),
    metadata_authority  SMALLINT NOT NULL DEFAULT 10 CHECK (metadata_authority IN (0, 10, 50, 100, 200)),
    catalog_version     INTEGER NOT NULL DEFAULT 1 CHECK (catalog_version >= 1),
    enriched_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);

ALTER TABLE public.track_index
    ADD COLUMN IF NOT EXISTS artist_id          TEXT REFERENCES public.artist_index(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS album_id           TEXT REFERENCES public.album_index(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS isrc               TEXT,
    ADD COLUMN IF NOT EXISTS explicit           BOOLEAN,
    ADD COLUMN IF NOT EXISTS metadata_authority SMALLINT NOT NULL DEFAULT 10,
    ADD COLUMN IF NOT EXISTS metadata_quality   SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS enrichment_status  TEXT NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS catalog_version    INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS enriched_at        TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conrelid = 'public.track_index'::regclass AND conname = 'chk_ti_authority'
    ) THEN
        ALTER TABLE public.track_index
            ADD CONSTRAINT chk_ti_authority CHECK (metadata_authority IN (0, 10, 50, 100, 200));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conrelid = 'public.track_index'::regclass AND conname = 'chk_ti_quality'
    ) THEN
        ALTER TABLE public.track_index
            ADD CONSTRAINT chk_ti_quality CHECK (metadata_quality BETWEEN 0 AND 100);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conrelid = 'public.track_index'::regclass AND conname = 'chk_ti_enrichment_status'
    ) THEN
        ALTER TABLE public.track_index
            ADD CONSTRAINT chk_ti_enrichment_status
            CHECK (enrichment_status IN ('pending', 'enriched', 'partial', 'unavailable', 'dead_letter'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conrelid = 'public.track_index'::regclass AND conname = 'chk_ti_catalog_version'
    ) THEN
        ALTER TABLE public.track_index
            ADD CONSTRAINT chk_ti_catalog_version CHECK (catalog_version >= 1);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_track_index_incomplete
    ON public.track_index (enrichment_status, metadata_authority, updated_at)
    WHERE enrichment_status IN ('pending', 'partial') OR duration_sec IS NULL OR duration_sec = 0;

CREATE INDEX IF NOT EXISTS idx_track_index_artist_id
    ON public.track_index (artist_id)
    WHERE artist_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_track_index_album_id
    ON public.track_index (album_id)
    WHERE album_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.catalog_enrichment_queue (
    track_id            TEXT PRIMARY KEY REFERENCES public.track_index(id) ON DELETE CASCADE,
    source              TEXT NOT NULL,
    external_id         TEXT NOT NULL,
    priority            SMALLINT NOT NULL DEFAULT 50 CHECK (priority BETWEEN 1 AND 200),
    reason              TEXT NOT NULL DEFAULT 'missing_duration',
    status              TEXT NOT NULL DEFAULT 'queued'
                            CHECK (status IN ('queued', 'processing', 'dead_letter')),
    attempts            SMALLINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts        SMALLINT NOT NULL DEFAULT 6 CHECK (max_attempts BETWEEN 1 AND 20),
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by           TEXT,
    locked_until        TIMESTAMPTZ,
    last_error_code     TEXT,
    last_error_detail   TEXT,
    enqueued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ceq_claimable
    ON public.catalog_enrichment_queue (priority DESC, next_attempt_at ASC)
    WHERE status IN ('queued', 'processing');

-- ============================================================================
-- 3. Backfill Existing track_index & Seed Missing References
-- ============================================================================
UPDATE public.track_index
SET
    duration_sec       = CASE WHEN duration_sec IS NOT NULL AND duration_sec > 0 THEN duration_sec ELSE NULL END,
    metadata_authority = 10,
    metadata_quality   = (CASE WHEN duration_sec IS NOT NULL AND duration_sec > 0 THEN 35 ELSE 0 END)
                       + (CASE WHEN NOT public._plane_d_is_placeholder(title) THEN 20 ELSE 0 END)
                       + (CASE WHEN NOT public._plane_d_is_placeholder(artist_name) THEN 20 ELSE 0 END)
                       + (CASE WHEN thumb_url ~ '^https://' THEN 15 ELSE 0 END)
                       + (CASE WHEN album_name IS NOT NULL AND btrim(album_name) <> '' THEN 10 ELSE 0 END),
    enrichment_status  = 'pending',
    updated_at         = now();

WITH referenced_ids AS (
    SELECT track_id FROM public.user_likes WHERE NOT deleted
    UNION
    SELECT track_id FROM public.user_library_tracks WHERE NOT deleted
    UNION
    SELECT track_id FROM public.playlist_tracks WHERE NOT deleted
    UNION
    SELECT track_id FROM public.user_listening_history
),
normalized_refs AS (
    SELECT DISTINCT n.canonical_id, n.source, n.external_id
    FROM referenced_ids r
    CROSS JOIN LATERAL public._plane_d_normalize_track_id(r.track_id) n
)
INSERT INTO public.track_index (
    id, source, external_id, title, artist_name,
    metadata_authority, metadata_quality, enrichment_status, catalog_version
)
SELECT
    nr.canonical_id, nr.source, nr.external_id,
    'Unknown Track', 'Unknown Artist',
    0, 0, 'pending', 1
FROM normalized_refs nr
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.catalog_enrichment_queue (track_id, source, external_id, priority, reason)
SELECT
    id,
    source,
    external_id,
    CASE WHEN duration_sec IS NULL OR metadata_authority = 0 THEN 100 ELSE 30 END,
    CASE
        WHEN metadata_authority = 0 THEN 'orphan_reference_seed'
        WHEN duration_sec IS NULL THEN 'backfill_zero_duration'
        ELSE 'backfill_verify_canonical'
    END
FROM public.track_index
WHERE metadata_authority < 100
ON CONFLICT (track_id) DO NOTHING;

-- ============================================================================
-- 4. RLS Hardening & Hint-Only tg_tracks_view_upsert() Shim
-- ============================================================================
DO $$
DECLARE
    v_tbl TEXT;
    v_pol RECORD;
BEGIN
    FOREACH v_tbl IN ARRAY ARRAY['track_index', 'artist_index', 'album_index', 'catalog_enrichment_queue'] LOOP
        FOR v_pol IN
            SELECT policyname FROM pg_policies
            WHERE schemaname = 'public' AND tablename = v_tbl
        LOOP
            EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', v_pol.policyname, v_tbl);
        END LOOP;

        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', v_tbl);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', v_tbl);
        EXECUTE format('REVOKE ALL ON public.%I FROM anon, authenticated', v_tbl);

        IF v_tbl <> 'catalog_enrichment_queue' THEN
            EXECUTE format('GRANT SELECT ON public.%I TO authenticated', v_tbl);
            EXECUTE format(
                'CREATE POLICY %I ON public.%I FOR SELECT TO authenticated USING (true)',
                v_tbl || '_select_authenticated',
                v_tbl
            );
        END IF;
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION public.tg_tracks_view_upsert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NOT NULL AND NEW.id IS NOT NULL THEN
        PERFORM public.ingest_track_hints(
            jsonb_build_array(
                jsonb_build_object(
                    'track_id', NEW.id,
                    'title', NEW.title,
                    'artist_name', NEW.artist,
                    'album_name', NEW.album,
                    'duration_sec', NEW.duration_sec,
                    'thumb_url', NEW.cover_url
                )
            )
        );
    END IF;
    RETURN NEW;
END;
$$;

-- ============================================================================
-- 5. Client & S21 Worker RPCs
-- ============================================================================

-- 5.1 get_tracks_batch
CREATE OR REPLACE FUNCTION public.get_tracks_batch(
    p_track_ids       TEXT[],
    p_known_versions  JSONB DEFAULT '{}'::jsonb,
    p_enqueue_missing BOOLEAN DEFAULT TRUE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_raw           TEXT;
    v_norm          RECORD;
    v_tracks        JSONB := '{}'::jsonb;
    v_aliases       JSONB := '{}'::jsonb;
    v_unchanged     TEXT[] := '{}';
    v_missing       TEXT[] := '{}';
    v_row           RECORD;
    v_known_ver     BIGINT;
    v_can_enqueue   BOOLEAN := FALSE;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_track_ids IS NULL OR COALESCE(array_length(p_track_ids, 1), 0) = 0 THEN
        RETURN jsonb_build_object(
            'status', 'OK',
            'tracks', '{}'::jsonb,
            'id_aliases', '{}'::jsonb,
            'unchanged_ids', '[]'::jsonb,
            'missing_ids', '[]'::jsonb
        );
    END IF;

    IF array_length(p_track_ids, 1) > 200 THEN
        RETURN jsonb_build_object('status', 'INVALID_BATCH', 'max_batch_size', 200);
    END IF;

    IF COALESCE(p_enqueue_missing, TRUE) THEN
        v_can_enqueue := public.track_ingest_allowed();
    END IF;

    FOREACH v_raw IN ARRAY p_track_ids LOOP
        SELECT * INTO v_norm FROM public._plane_d_normalize_track_id(v_raw);
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        IF v_raw <> v_norm.canonical_id THEN
            v_aliases := v_aliases || jsonb_build_object(v_raw, v_norm.canonical_id);
        END IF;

        SELECT
            id, source, external_id, title, artist_name, album_name,
            artist_id, album_id, duration_sec, thumb_url,
            metadata_authority, metadata_quality, enrichment_status, catalog_version, enriched_at
        INTO v_row
        FROM public.track_index
        WHERE id = v_norm.canonical_id;

        IF NOT FOUND THEN
            v_missing := array_append(v_missing, v_norm.canonical_id);
            IF v_can_enqueue THEN
                INSERT INTO public.track_index (
                    id, source, external_id, title, artist_name,
                    metadata_authority, metadata_quality, enrichment_status, catalog_version
                ) VALUES (
                    v_norm.canonical_id, v_norm.source, v_norm.external_id,
                    'Unknown Track', 'Unknown Artist', 0, 0, 'pending', 1
                ) ON CONFLICT (id) DO NOTHING;

                INSERT INTO public.catalog_enrichment_queue (track_id, source, external_id, priority, reason)
                VALUES (v_norm.canonical_id, v_norm.source, v_norm.external_id, 80, 'get_tracks_batch_missing')
                ON CONFLICT (track_id) DO NOTHING;
            END IF;
            CONTINUE;
        END IF;

        v_known_ver := COALESCE(
            public._v5_try_parse_bigint(p_known_versions->>v_norm.canonical_id),
            public._v5_try_parse_bigint(p_known_versions->>v_raw)
        );

        IF v_known_ver IS NOT NULL AND v_known_ver = v_row.catalog_version THEN
            v_unchanged := array_append(v_unchanged, v_row.id);
            CONTINUE;
        END IF;

        v_tracks := v_tracks || jsonb_build_object(
            v_row.id,
            jsonb_build_object(
                'id', v_row.id,
                'source', v_row.source,
                'external_id', v_row.external_id,
                'title', v_row.title,
                'artist_name', v_row.artist_name,
                'album_name', v_row.album_name,
                'artist_id', v_row.artist_id,
                'album_id', v_row.album_id,
                'duration_sec', v_row.duration_sec,
                'thumb_url', v_row.thumb_url,
                'metadata_authority', v_row.metadata_authority,
                'metadata_quality', v_row.metadata_quality,
                'enrichment_status', v_row.enrichment_status,
                'catalog_version', v_row.catalog_version,
                'enriched_at', v_row.enriched_at
            )
        );
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'OK',
        'tracks', v_tracks,
        'id_aliases', v_aliases,
        'unchanged_ids', to_jsonb(v_unchanged),
        'missing_ids', to_jsonb(v_missing)
    );
END;
$$;

-- 5.2 ingest_track_hints
CREATE OR REPLACE FUNCTION public.ingest_track_hints(p_hints JSONB)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id     UUID := auth.uid();
    v_item        JSONB;
    v_norm        RECORD;
    v_title       TEXT;
    v_artist      TEXT;
    v_album       TEXT;
    v_dur_big     BIGINT;
    v_dur         INT;
    v_thumb       TEXT;
    v_existing    RECORD;
    v_accepted    INT := 0;
    v_ignored     INT := 0;
    v_enqueued    INT := 0;
    v_quality     SMALLINT;
    v_need_enrich BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    IF p_hints IS NULL OR jsonb_typeof(p_hints) <> 'array' OR jsonb_array_length(p_hints) > 100 THEN
        RETURN jsonb_build_object('status', 'INVALID_BATCH', 'max_batch_size', 100);
    END IF;

    IF NOT public.track_ingest_allowed() THEN
        RETURN jsonb_build_object('status', 'RATE_LIMITED', 'rate_limited', true);
    END IF;

    FOR v_item IN SELECT value FROM jsonb_array_elements(p_hints) LOOP
        SELECT * INTO v_norm FROM public._plane_d_normalize_track_id(v_item->>'track_id');
        IF NOT FOUND THEN
            CONTINUE;
        END IF;

        v_title   := NULLIF(btrim(v_item->>'title'), '');
        v_artist  := NULLIF(btrim(v_item->>'artist_name'), '');
        v_album   := NULLIF(btrim(v_item->>'album_name'), '');
        v_dur_big := public._v5_try_parse_bigint(v_item->>'duration_sec');
        v_dur     := CASE
            WHEN v_dur_big IS NOT NULL AND v_dur_big BETWEEN 1 AND 86400 THEN v_dur_big::INT
            ELSE NULL
        END;
        v_thumb   := CASE
            WHEN (v_item->>'thumb_url') ~ '^https://' THEN left(btrim(v_item->>'thumb_url'), 1024)
            WHEN (v_item->>'thumb_url') ~ '^http://'  THEN 'https://' || substring(left(btrim(v_item->>'thumb_url'), 1024) FROM 8)
            ELSE NULL
        END;

        IF public._plane_d_is_placeholder(v_title) THEN v_title := NULL; END IF;
        IF public._plane_d_is_placeholder(v_artist) THEN v_artist := NULL; END IF;

        SELECT id, title, artist_name, album_name, duration_sec, thumb_url, metadata_authority, enrichment_status
        INTO v_existing
        FROM public.track_index
        WHERE id = v_norm.canonical_id;

        IF FOUND AND v_existing.metadata_authority >= 50 THEN
            v_ignored := v_ignored + 1;
            CONTINUE;
        END IF;

        IF NOT FOUND THEN
            v_quality := (CASE WHEN v_dur IS NOT NULL THEN 35 ELSE 0 END)
                       + (CASE WHEN v_title IS NOT NULL THEN 20 ELSE 0 END)
                       + (CASE WHEN v_artist IS NOT NULL THEN 20 ELSE 0 END)
                       + (CASE WHEN v_thumb IS NOT NULL THEN 15 ELSE 0 END)
                       + (CASE WHEN v_album IS NOT NULL THEN 10 ELSE 0 END);

            INSERT INTO public.track_index (
                id, source, external_id, title, artist_name, album_name,
                duration_sec, thumb_url, metadata_authority, metadata_quality,
                enrichment_status, catalog_version
            ) VALUES (
                v_norm.canonical_id, v_norm.source, v_norm.external_id,
                COALESCE(left(v_title, 512), 'Unknown Track'),
                COALESCE(left(v_artist, 512), 'Unknown Artist'),
                v_album,
                v_dur,
                v_thumb,
                CASE WHEN v_title IS NOT NULL AND v_artist IS NOT NULL THEN 10 ELSE 0 END,
                v_quality,
                'pending',
                1
            )
            ON CONFLICT (id) DO NOTHING;

            v_accepted    := v_accepted + 1;
            v_need_enrich := TRUE;
        ELSE
            UPDATE public.track_index
            SET
                title              = CASE WHEN public._plane_d_is_placeholder(title) AND v_title IS NOT NULL THEN left(v_title, 512) ELSE title END,
                artist_name        = CASE WHEN public._plane_d_is_placeholder(artist_name) AND v_artist IS NOT NULL THEN left(v_artist, 512) ELSE artist_name END,
                album_name         = COALESCE(NULLIF(btrim(album_name), ''), v_album),
                duration_sec       = CASE WHEN COALESCE(duration_sec, 0) = 0 AND v_dur IS NOT NULL THEN v_dur ELSE duration_sec END,
                thumb_url          = COALESCE(thumb_url, v_thumb),
                metadata_authority = GREATEST(metadata_authority, CASE WHEN v_title IS NOT NULL THEN 10 ELSE 0 END),
                catalog_version    = catalog_version + 1,
                updated_at         = now()
            WHERE id = v_norm.canonical_id
              AND metadata_authority < 50
              AND (
                  (public._plane_d_is_placeholder(title) AND v_title IS NOT NULL)
                  OR (public._plane_d_is_placeholder(artist_name) AND v_artist IS NOT NULL)
                  OR (COALESCE(duration_sec, 0) = 0 AND v_dur IS NOT NULL)
                  OR (thumb_url IS NULL AND v_thumb IS NOT NULL)
                  OR (album_name IS NULL AND v_album IS NOT NULL)
              );

            IF FOUND THEN
                v_accepted := v_accepted + 1;
            ELSE
                v_ignored := v_ignored + 1;
            END IF;

            v_need_enrich := (v_existing.enrichment_status <> 'enriched');
        END IF;

        IF v_need_enrich THEN
            INSERT INTO public.catalog_enrichment_queue (track_id, source, external_id, priority, reason)
            VALUES (
                v_norm.canonical_id,
                v_norm.source,
                v_norm.external_id,
                CASE WHEN COALESCE(v_dur, v_existing.duration_sec, 0) = 0 THEN 100 ELSE 50 END,
                CASE WHEN COALESCE(v_dur, v_existing.duration_sec, 0) = 0 THEN 'missing_duration' ELSE 'verify_hint' END
            )
            ON CONFLICT (track_id) DO UPDATE SET
                priority   = GREATEST(catalog_enrichment_queue.priority, EXCLUDED.priority),
                updated_at = now()
            WHERE catalog_enrichment_queue.status = 'queued';

            v_enqueued := v_enqueued + 1;
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'status', 'OK',
        'accepted', v_accepted,
        'ignored_higher_authority', v_ignored,
        'enqueued_for_enrichment', v_enqueued,
        'rate_limited', false
    );
END;
$$;

-- 5.3 S21 Worker RPCs (service_role only)
CREATE OR REPLACE FUNCTION public.claim_catalog_enrichment_batch(
    p_worker_id  TEXT,
    p_batch_size INT DEFAULT 20,
    p_lease_sec  INT DEFAULT 90
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_now   TIMESTAMPTZ := clock_timestamp();
    v_limit INT := LEAST(GREATEST(COALESCE(p_batch_size, 20), 1), 100);
    v_lease INTERVAL := make_interval(secs => LEAST(GREATEST(COALESCE(p_lease_sec, 90), 15), 600));
    v_jobs  JSONB := '[]'::jsonb;
BEGIN
    WITH claimed AS (
        SELECT track_id
        FROM public.catalog_enrichment_queue
        WHERE (status = 'queued' AND next_attempt_at <= v_now)
           OR (status = 'processing' AND locked_until < v_now)
        ORDER BY priority DESC, next_attempt_at ASC
        LIMIT v_limit
        FOR UPDATE SKIP LOCKED
    ),
    updated AS (
        UPDATE public.catalog_enrichment_queue q
        SET status       = 'processing',
            locked_by    = p_worker_id,
            locked_until = v_now + v_lease,
            attempts     = q.attempts + 1,
            updated_at   = v_now
        FROM claimed c
        WHERE q.track_id = c.track_id
        RETURNING q.track_id, q.source, q.external_id, q.priority, q.reason, q.attempts
    )
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'track_id', track_id,
            'source', source,
            'external_id', external_id,
            'priority', priority,
            'reason', reason,
            'attempts', attempts
        )
    ), '[]'::jsonb)
    INTO v_jobs
    FROM updated;

    RETURN jsonb_build_object('status', 'OK', 'jobs', v_jobs);
END;
$$;

CREATE OR REPLACE FUNCTION public.upsert_catalog_authoritative(
    p_authority SMALLINT,
    p_tracks    JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_now      TIMESTAMPTZ := clock_timestamp();
    v_item     JSONB;
    v_norm     RECORD;
    v_title    TEXT;
    v_artist   TEXT;
    v_album    TEXT;
    v_dur      INT;
    v_thumb    TEXT;
    v_quality  SMALLINT;
    v_upserted INT := 0;
BEGIN
    IF p_authority NOT IN (50, 100, 200) OR p_tracks IS NULL OR jsonb_typeof(p_tracks) <> 'array' THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    FOR v_item IN SELECT value FROM jsonb_array_elements(p_tracks) LOOP
        SELECT * INTO v_norm FROM public._plane_d_normalize_track_id(v_item->>'track_id');
        IF NOT FOUND THEN CONTINUE; END IF;

        v_title  := NULLIF(btrim(v_item->>'title'), '');
        v_artist := NULLIF(btrim(v_item->>'artist_name'), '');
        v_album  := NULLIF(btrim(v_item->>'album_name'), '');
        v_dur    := CASE WHEN public._v5_try_parse_bigint(v_item->>'duration_sec') BETWEEN 1 AND 86400
                         THEN public._v5_try_parse_bigint(v_item->>'duration_sec')::INT ELSE NULL END;
        v_thumb  := CASE
            WHEN (v_item->>'thumb_url') ~ '^https://' THEN left(btrim(v_item->>'thumb_url'), 1024)
            WHEN (v_item->>'thumb_url') ~ '^http://'  THEN 'https://' || substring(left(btrim(v_item->>'thumb_url'), 1024) FROM 8)
            ELSE NULL
        END;

        v_quality := (CASE WHEN v_dur IS NOT NULL THEN 35 ELSE 0 END)
                   + (CASE WHEN NOT public._plane_d_is_placeholder(v_title) THEN 20 ELSE 0 END)
                   + (CASE WHEN NOT public._plane_d_is_placeholder(v_artist) THEN 20 ELSE 0 END)
                   + (CASE WHEN v_thumb ~ '^https://' THEN 15 ELSE 0 END)
                   + (CASE WHEN v_album IS NOT NULL THEN 10 ELSE 0 END);

        INSERT INTO public.track_index (
            id, source, external_id, title, artist_name, album_name,
            duration_sec, thumb_url, metadata_authority, metadata_quality,
            enrichment_status, catalog_version, enriched_at, updated_at
        ) VALUES (
            v_norm.canonical_id, v_norm.source, v_norm.external_id,
            COALESCE(left(v_title, 512), 'Unknown Track'),
            COALESCE(left(v_artist, 512), 'Unknown Artist'),
            v_album, v_dur, v_thumb, p_authority, v_quality,
            CASE WHEN p_authority >= 100 THEN 'enriched' ELSE 'partial' END,
            1,
            CASE WHEN p_authority >= 100 THEN v_now ELSE NULL END,
            v_now
        )
        ON CONFLICT (id) DO UPDATE SET
            title              = CASE WHEN NOT public._plane_d_is_placeholder(EXCLUDED.title) THEN EXCLUDED.title ELSE track_index.title END,
            artist_name        = CASE WHEN NOT public._plane_d_is_placeholder(EXCLUDED.artist_name) THEN EXCLUDED.artist_name ELSE track_index.artist_name END,
            album_name         = COALESCE(EXCLUDED.album_name, track_index.album_name),
            duration_sec       = COALESCE(EXCLUDED.duration_sec, track_index.duration_sec),
            thumb_url          = COALESCE(EXCLUDED.thumb_url, track_index.thumb_url),
            metadata_authority = GREATEST(track_index.metadata_authority, EXCLUDED.metadata_authority),
            metadata_quality   = GREATEST(track_index.metadata_quality, EXCLUDED.metadata_quality),
            enrichment_status  = CASE WHEN p_authority >= 100 THEN 'enriched' ELSE track_index.enrichment_status END,
            enriched_at        = COALESCE(EXCLUDED.enriched_at, track_index.enriched_at),
            catalog_version    = track_index.catalog_version + 1,
            updated_at         = v_now
        WHERE track_index.metadata_authority <= p_authority;

        IF FOUND THEN
            v_upserted := v_upserted + 1;
            IF v_dur IS NOT NULL AND p_authority = 50 THEN
                UPDATE public.catalog_enrichment_queue
                SET priority = LEAST(priority, 25),
                    reason = 'verify_canonical_ids',
                    updated_at = v_now
                WHERE track_id = v_norm.canonical_id AND status = 'queued';
            END IF;
        END IF;
    END LOOP;

    RETURN jsonb_build_object('status', 'OK', 'upserted', v_upserted);
END;
$$;

CREATE OR REPLACE FUNCTION public.complete_catalog_enrichment_batch(
    p_worker_id TEXT,
    p_results   JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_now       TIMESTAMPTZ := clock_timestamp();
    v_item      JSONB;
    v_tid       TEXT;
    v_outcome   TEXT;
    v_attempts  SMALLINT;
    v_max_att   SMALLINT;
    v_backoff_s INT;
    v_done      INT := 0;
BEGIN
    IF p_results IS NULL OR jsonb_typeof(p_results) <> 'array' THEN
        RETURN jsonb_build_object('status', 'INVALID_PAYLOAD');
    END IF;

    FOR v_item IN SELECT value FROM jsonb_array_elements(p_results) LOOP
        v_tid     := v_item->>'track_id';
        v_outcome := v_item->>'outcome';
        IF v_tid IS NULL THEN CONTINUE; END IF;

        IF v_outcome = 'enriched' THEN
            PERFORM public.upsert_catalog_authoritative(100::SMALLINT, jsonb_build_array(v_item));
            DELETE FROM public.catalog_enrichment_queue WHERE track_id = v_tid;
            v_done := v_done + 1;
        ELSIF v_outcome = 'partial' THEN
            PERFORM public.upsert_catalog_authoritative(50::SMALLINT, jsonb_build_array(v_item));
            UPDATE public.catalog_enrichment_queue
            SET status = 'queued', locked_by = NULL, locked_until = NULL,
                next_attempt_at = v_now + INTERVAL '24 hours', updated_at = v_now
            WHERE track_id = v_tid;
            v_done := v_done + 1;
        ELSIF v_outcome = 'unavailable' THEN
            UPDATE public.track_index
            SET enrichment_status = 'unavailable', updated_at = v_now
            WHERE id = v_tid AND metadata_authority < 100;
            DELETE FROM public.catalog_enrichment_queue WHERE track_id = v_tid;
            v_done := v_done + 1;
        ELSIF v_outcome = 'retryable_error' THEN
            SELECT attempts, max_attempts INTO v_attempts, v_max_att
            FROM public.catalog_enrichment_queue WHERE track_id = v_tid;

            IF FOUND AND v_attempts >= v_max_att THEN
                UPDATE public.catalog_enrichment_queue
                SET status = 'dead_letter', locked_by = NULL, locked_until = NULL,
                    last_error_code = v_item->>'error_code', last_error_detail = v_item->>'error_detail',
                    updated_at = v_now
                WHERE track_id = v_tid;
                UPDATE public.track_index SET enrichment_status = 'dead_letter', updated_at = v_now WHERE id = v_tid;
            ELSIF FOUND THEN
                v_backoff_s := LEAST(21600, (30 * power(4, GREATEST(v_attempts - 1, 0)))::INT);
                UPDATE public.catalog_enrichment_queue
                SET status = 'queued', locked_by = NULL, locked_until = NULL,
                    next_attempt_at = v_now + make_interval(secs => v_backoff_s),
                    last_error_code = v_item->>'error_code', last_error_detail = v_item->>'error_detail',
                    updated_at = v_now
                WHERE track_id = v_tid;
            END IF;
            v_done := v_done + 1;
        END IF;
    END LOOP;

    RETURN jsonb_build_object('status', 'OK', 'processed', v_done);
END;
$$;

-- ============================================================================
-- 6. Cross-Plane Sidecar Integration (Plane A + Plane B)
-- ============================================================================

-- 6.1 Shared Sidecar Builder Helper
CREATE OR REPLACE FUNCTION public._plane_d_build_catalog_sidecar(p_raw_track_ids TEXT[])
RETURNS JSONB
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    WITH distinct_ids AS (
        SELECT DISTINCT unnest(COALESCE(p_raw_track_ids, '{}'::TEXT[])) AS raw_id
    ),
    resolved AS (
        SELECT DISTINCT
            t.id, t.source, t.external_id, t.title, t.artist_name, t.album_name,
            t.artist_id, t.album_id, t.duration_sec, t.thumb_url,
            t.metadata_authority, t.metadata_quality, t.enrichment_status, t.catalog_version
        FROM distinct_ids d
        CROSS JOIN LATERAL public._plane_d_normalize_track_id(d.raw_id) n
        JOIN public.track_index t ON t.id = n.canonical_id
    )
    SELECT jsonb_build_object(
        'tracks',
        COALESCE((
            SELECT jsonb_object_agg(
                r.id,
                jsonb_build_object(
                    'id', r.id,
                    'source', r.source,
                    'external_id', r.external_id,
                    'title', r.title,
                    'artist_name', r.artist_name,
                    'album_name', r.album_name,
                    'artist_id', r.artist_id,
                    'album_id', r.album_id,
                    'duration_sec', r.duration_sec,
                    'thumb_url', r.thumb_url,
                    'metadata_authority', r.metadata_authority,
                    'metadata_quality', r.metadata_quality,
                    'enrichment_status', r.enrichment_status,
                    'catalog_version', r.catalog_version
                )
            )
            FROM resolved r
        ), '{}'::jsonb)
    );
$$;

-- 6.2 Auto-seed tier-0 stubs on Plane A track upserts inside sync_push_v5
CREATE OR REPLACE FUNCTION public._plane_d_tg_seed_changelog_track()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_raw_tid TEXT;
    v_norm    RECORD;
BEGIN
    IF NEW.op = 'upsert' AND NEW.entity_type IN ('user_like', 'user_library_track', 'playlist_track') THEN
        v_raw_tid := NEW.payload->>'track_id';
        IF v_raw_tid IS NOT NULL THEN
            SELECT * INTO v_norm FROM public._plane_d_normalize_track_id(v_raw_tid);
            IF FOUND THEN
                INSERT INTO public.track_index (
                    id, source, external_id, title, artist_name,
                    metadata_authority, metadata_quality, enrichment_status, catalog_version
                ) VALUES (
                    v_norm.canonical_id, v_norm.source, v_norm.external_id,
                    'Unknown Track', 'Unknown Artist', 0, 0, 'pending', 1
                ) ON CONFLICT (id) DO NOTHING;

                INSERT INTO public.catalog_enrichment_queue (track_id, source, external_id, priority, reason)
                VALUES (v_norm.canonical_id, v_norm.source, v_norm.external_id, 80, 'plane_a_ref')
                ON CONFLICT (track_id) DO NOTHING;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tg_plane_d_seed_track ON public.sync_changelog_v5;
CREATE TRIGGER tg_plane_d_seed_track
    AFTER INSERT ON public.sync_changelog_v5
    FOR EACH ROW
    EXECUTE FUNCTION public._plane_d_tg_seed_changelog_track();

-- 6.3 Upgrade sync_pull_v5 to populate catalog sidecar when p_include_catalog = TRUE
CREATE OR REPLACE FUNCTION public.sync_pull_v5(
    p_device_id       TEXT,
    p_since_rev       TEXT,
    p_limit           INT DEFAULT 200,
    p_exclude_own     BOOLEAN DEFAULT TRUE,
    p_include_catalog BOOLEAN DEFAULT TRUE
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id       UUID := auth.uid();
    v_device_id     TEXT := btrim(COALESCE(p_device_id, ''));
    v_since_rev     BIGINT;
    v_limit         INT := LEAST(GREATEST(COALESCE(p_limit, 200), 1), 500);
    v_head_rev      BIGINT := 0;
    v_min_rev       BIGINT := 0;
    v_next_rev      BIGINT;
    v_has_more      BOOLEAN := FALSE;
    v_changes       JSONB := '[]'::jsonb;
    v_track_ids     TEXT[] := '{}';
    v_catalog       JSONB := jsonb_build_object('tracks', '{}'::jsonb);
    v_server_now_ms BIGINT := (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'UNAUTHORIZED');
    END IF;

    IF char_length(v_device_id) > 0 AND public._plane_c_is_device_revoked(v_user_id, v_device_id) THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'DEVICE_REVOKED');
    END IF;

    v_since_rev := public._v5_try_parse_bigint(p_since_rev);
    IF v_since_rev IS NULL OR v_since_rev < 0 THEN
        RETURN jsonb_build_object('protocol_version', 5, 'status', 'INVALID_CURSOR');
    END IF;

    SELECT COALESCE(head_rev, 0), COALESCE(min_available_rev, 0)
    INTO v_head_rev, v_min_rev
    FROM public.user_sync_state
    WHERE user_id = v_user_id;

    v_head_rev := COALESCE(v_head_rev, 0);
    v_min_rev  := COALESCE(v_min_rev, 0);

    IF v_since_rev < v_min_rev THEN
        RETURN jsonb_build_object(
            'protocol_version', 5,
            'status', 'CURSOR_EXPIRED',
            'server_time_ms', v_server_now_ms::TEXT,
            'head_rev', v_head_rev::TEXT,
            'min_available_rev', v_min_rev::TEXT
        );
    END IF;

    WITH scanned AS (
        SELECT rev, mutation_id, entity_type, entity_key, op, occurred_hlc, origin_device_id, payload
        FROM public.sync_changelog_v5
        WHERE user_id = v_user_id
          AND rev > v_since_rev
        ORDER BY rev ASC
        LIMIT v_limit + 1
    ),
    page AS (
        SELECT * FROM scanned LIMIT v_limit
    )
    SELECT
        COALESCE(MAX(p.rev), v_since_rev),
        (SELECT COUNT(*) > v_limit FROM scanned),
        COALESCE(
            jsonb_agg(
                jsonb_build_object(
                    'rev', p.rev::TEXT,
                    'mutation_id', p.mutation_id::TEXT,
                    'entity_type', p.entity_type,
                    'entity_key', p.entity_key,
                    'op', p.op,
                    'hlc', p.occurred_hlc::TEXT,
                    'origin_device_id', p.origin_device_id,
                    'payload', p.payload
                )
                ORDER BY p.rev ASC
            ) FILTER (WHERE NOT COALESCE(p_exclude_own, TRUE) OR p.origin_device_id <> v_device_id),
            '[]'::jsonb
        ),
        COALESCE(
            array_agg(DISTINCT (p.payload->>'track_id')) FILTER (
                WHERE p.op = 'upsert'
                  AND p.entity_type IN ('user_like', 'user_library_track', 'playlist_track')
                  AND (p.payload->>'track_id') IS NOT NULL
            ),
            '{}'::TEXT[]
        )
    INTO v_next_rev, v_has_more, v_changes, v_track_ids
    FROM page p;

    IF COALESCE(p_include_catalog, TRUE) AND COALESCE(array_length(v_track_ids, 1), 0) > 0 THEN
        v_catalog := public._plane_d_build_catalog_sidecar(v_track_ids);
    END IF;

    IF char_length(v_device_id) > 0 THEN
        PERFORM public._plane_c_touch_activity(v_user_id, v_device_id);
    END IF;

    RETURN jsonb_build_object(
        'protocol_version', 5,
        'status', 'OK',
        'server_time_ms', v_server_now_ms::TEXT,
        'head_rev', v_head_rev::TEXT,
        'min_available_rev', v_min_rev::TEXT,
        'next_rev', v_next_rev::TEXT,
        'has_more', v_has_more,
        'changes', v_changes,
        'catalog', v_catalog
    );
END;
$$;

-- 6.4 Upgrade fetch_listening_history to LEFT JOIN track_index
CREATE OR REPLACE FUNCTION public.fetch_listening_history(
    p_cursor_played_at TIMESTAMPTZ DEFAULT NULL,
    p_cursor_event_id  UUID DEFAULT NULL,
    p_limit            INT DEFAULT 50,
    p_since            TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id  UUID := auth.uid();
    v_limit    INT := LEAST(GREATEST(COALESCE(p_limit, 50), 1), 100);
    v_items    JSONB := '[]'::jsonb;
    v_has_more BOOLEAN := FALSE;
    v_next_ts  TIMESTAMPTZ;
    v_next_id  UUID;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('status', 'UNAUTHORIZED');
    END IF;

    WITH page AS (
        SELECT
            h.client_event_id,
            h.track_id,
            h.played_at,
            h.listened_duration_sec,
            COALESCE(ti.duration_sec, h.track_duration_sec) AS track_duration_sec,
            h.play_counted,
            h.source_context,
            h.source_id,
            COALESCE(ti.title, h.track_title) AS track_title,
            COALESCE(ti.artist_name, h.artist_name) AS artist_name,
            COALESCE(ti.album_name, h.album_name) AS album_name,
            COALESCE(ti.thumb_url, h.artwork_url) AS artwork_url
        FROM public.user_listening_history h
        LEFT JOIN LATERAL public._plane_d_normalize_track_id(h.track_id) n ON TRUE
        LEFT JOIN public.track_index ti ON ti.id = n.canonical_id
        WHERE h.user_id = v_user_id
          AND (p_since IS NULL OR h.updated_at > p_since)
          AND (
              p_cursor_played_at IS NULL
              OR (h.played_at, h.client_event_id) < (p_cursor_played_at, COALESCE(p_cursor_event_id, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::UUID))
          )
        ORDER BY h.played_at DESC, h.client_event_id DESC
        LIMIT v_limit + 1
    ),
    trimmed AS (
        SELECT * FROM page LIMIT v_limit
    )
    SELECT
        COALESCE(jsonb_agg(
            jsonb_build_object(
                'client_event_id', client_event_id::TEXT,
                'track_id', track_id,
                'played_at', played_at,
                'listened_duration_sec', listened_duration_sec,
                'track_duration_sec', track_duration_sec,
                'play_counted', play_counted,
                'source_context', source_context,
                'source_id', source_id,
                'track_title', track_title,
                'artist_name', artist_name,
                'album_name', album_name,
                'artwork_url', artwork_url
            )
            ORDER BY played_at DESC, client_event_id DESC
        ), '[]'::jsonb),
        (SELECT COUNT(*) > v_limit FROM page),
        (SELECT played_at FROM trimmed ORDER BY played_at ASC, client_event_id ASC LIMIT 1),
        (SELECT client_event_id FROM trimmed ORDER BY played_at ASC, client_event_id ASC LIMIT 1)
    INTO v_items, v_has_more, v_next_ts, v_next_id
    FROM trimmed;

    RETURN jsonb_build_object(
        'status', 'OK',
        'has_more', COALESCE(v_has_more, FALSE),
        'next_cursor', CASE
            WHEN v_has_more AND v_next_ts IS NOT NULL THEN
                jsonb_build_object('played_at', v_next_ts, 'client_event_id', v_next_id::TEXT)
            ELSE NULL
        END,
        'items', v_items
    );
END;
$$;

-- ============================================================================
-- 7. Lock Down Plane D RPCs
-- ============================================================================
REVOKE ALL ON FUNCTION public.get_tracks_batch(TEXT[], JSONB, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_tracks_batch(TEXT[], JSONB, BOOLEAN) TO authenticated;

REVOKE ALL ON FUNCTION public.ingest_track_hints(JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ingest_track_hints(JSONB) TO authenticated;

REVOKE ALL ON FUNCTION public.claim_catalog_enrichment_batch(TEXT, INT, INT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_catalog_enrichment_batch(TEXT, INT, INT) TO service_role;

REVOKE ALL ON FUNCTION public.upsert_catalog_authoritative(SMALLINT, JSONB) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.upsert_catalog_authoritative(SMALLINT, JSONB) TO service_role;

REVOKE ALL ON FUNCTION public.complete_catalog_enrichment_batch(TEXT, JSONB) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_catalog_enrichment_batch(TEXT, JSONB) TO service_role;

COMMIT;
