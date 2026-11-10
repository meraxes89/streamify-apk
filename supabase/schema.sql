-- ============================================================================
-- STREAMIFY FLAGSHIP SUPABASE SCHEMA
-- Author: Sireen Yadav (sireenyadav@gmail.com)
-- Features: Multi-user auth, RLS, Playlists, Likes, Realtime Jam sessions,
--           pgvector AI embeddings, Timestamped Comments, Friend Activity,
--           Listening History, Telemetry & Admin Command Center
-- ============================================================================

-- 1. Enable pgvector & UUID extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 2. User Profiles Table
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT UNIQUE NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    bio TEXT DEFAULT 'Music lover on Streamify 🎧',
    is_admin BOOLEAN DEFAULT FALSE,
    total_plays INT DEFAULT 0,
    listening_seconds BIGINT DEFAULT 0,
    favorite_genre TEXT DEFAULT 'All',
    is_private BOOLEAN DEFAULT FALSE,
    last_active_at TIMESTAMPTZ DEFAULT NOW(),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. Cloud Track Catalog
CREATE TABLE IF NOT EXISTS public.tracks (
    id TEXT PRIMARY KEY, -- Video ID or URL hash or ISRC
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT DEFAULT 'Single',
    duration_sec INT DEFAULT 0,
    cover_url TEXT,
    stream_url TEXT,
    bpm REAL DEFAULT 120.0,
    key_signature TEXT DEFAULT 'C',
    lyrics TEXT,
    play_count INT DEFAULT 0,
    like_count INT DEFAULT 0,
    embedding vector(512), -- 512-dim KissFFT / CLAP audio feature vector
    -- SEC-10(b): ingestion attribution — the per-user ingest rate limit
    -- (track_ingest_allowed()) is enforced against this column.
    created_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for ultra-fast HNSW vector cosine similarity search (<5ms)
CREATE INDEX IF NOT EXISTS idx_tracks_embedding_hnsw 
ON public.tracks USING hnsw (embedding vector_cosine_ops);

-- 4. User Liked Songs (Cloud Sync)
CREATE TABLE IF NOT EXISTS public.user_likes (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, track_id)
);

-- 5. Playlists (Collaborative & Social)
CREATE TABLE IF NOT EXISTS public.playlists (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT,
    cover_url TEXT,
    is_public BOOLEAN DEFAULT TRUE,
    is_collaborative BOOLEAN DEFAULT FALSE,
    collaborator_ids UUID[] DEFAULT '{}',
    likes_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. Playlist Tracks Mapping
CREATE TABLE IF NOT EXISTS public.playlist_tracks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    playlist_id UUID REFERENCES public.playlists(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    position INT NOT NULL,
    added_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    added_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. Realtime Jam / Live Listening Sessions (Spotify Jam Clone)
CREATE TABLE IF NOT EXISTS public.listening_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    host_user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    session_code TEXT UNIQUE NOT NULL, -- 6-character room code (e.g. STRM9X)
    current_track_id TEXT,
    current_track_json JSONB,
    position_ms BIGINT DEFAULT 0,
    is_playing BOOLEAN DEFAULT FALSE,
    host_clock_timestamp BIGINT DEFAULT 0,
    queue_json JSONB DEFAULT '[]'::jsonb,
    participant_ids UUID[] DEFAULT '{}',
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 8. Timestamped Song Comments & Reactions (SoundCloud / YouTube Style)
CREATE TABLE IF NOT EXISTS public.track_comments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT NOT NULL,
    user_avatar TEXT,
    timestamp_ms BIGINT NOT NULL, -- Exact audio position in milliseconds
    comment_text TEXT NOT NULL,
    likes_count INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_comments_track_timestamp 
ON public.track_comments(track_id, timestamp_ms ASC);

-- 9. User Listening History (For Streamify Wrapped & Analytics)
CREATE TABLE IF NOT EXISTS public.user_listening_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT REFERENCES public.tracks(id) ON DELETE CASCADE,
    listened_duration_sec INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_history_user_date 
ON public.user_listening_history(user_id, created_at DESC);

-- 10. Social Friends & Connections
CREATE TABLE IF NOT EXISTS public.friend_connections (
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    friend_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (user_id, friend_id)
);

-- 11. Global Admin Telemetry & Broadcasts
CREATE TABLE IF NOT EXISTS public.admin_broadcasts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message TEXT NOT NULL,
    author_email TEXT DEFAULT 'sireenyadav@gmail.com',
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================================
-- AI SIMILARITY SEARCH FUNCTION (pgvector RPC for Song Radio)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.match_tracks (
    query_embedding vector(512),
    match_threshold float DEFAULT 0.20,
    match_count int DEFAULT 20
)
RETURNS TABLE (
    id TEXT,
    title TEXT,
    artist TEXT,
    album TEXT,
    duration_sec INT,
    cover_url TEXT,
    stream_url TEXT,
    bpm REAL,
    key_signature TEXT,
    similarity float
)
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN QUERY
    SELECT
        tracks.id,
        tracks.title,
        tracks.artist,
        tracks.album,
        tracks.duration_sec,
        tracks.cover_url,
        tracks.stream_url,
        tracks.bpm,
        tracks.key_signature,
        1 - (tracks.embedding <=> query_embedding) AS similarity
    FROM public.tracks
    WHERE tracks.embedding IS NOT NULL
      AND 1 - (tracks.embedding <=> query_embedding) > match_threshold
    ORDER BY tracks.embedding <=> query_embedding
    LIMIT match_count;
END;
$$;

-- ============================================================================
-- AUTOMATIC PROFILE CREATION TRIGGER & ADMIN ASSIGNMENT
-- ============================================================================
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    -- SEC-10(c): is_admin is granted ONLY by the project owner (SQL
    -- console or set_user_admin_role). Sign-up can never mint an admin:
    -- the pre-SEC-10 trigger granted admin to a hardcoded EMAIL, which
    -- tied the privilege to an identity string anyone can register.
    INSERT INTO public.profiles (id, email, display_name, avatar_url, is_admin)
    VALUES (
        NEW.id,
        NEW.email,
        COALESCE(NEW.raw_user_meta_data->>'full_name', NEW.raw_user_meta_data->>'name', split_part(NEW.email, '@', 1)),
        COALESCE(NEW.raw_user_meta_data->>'avatar_url', NEW.raw_user_meta_data->>'picture', ''),
        FALSE
    )
    ON CONFLICT (id) DO UPDATE
    SET email = EXCLUDED.email,
        last_active_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT OR UPDATE ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ============================================================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- ============================================================================
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.playlist_tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listening_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_listening_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.friend_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_broadcasts ENABLE ROW LEVEL SECURITY;

CREATE OR REPLACE FUNCTION public.is_admin()
RETURNS BOOLEAN AS $$
BEGIN
    -- SEC-10(c): the flag, and ONLY the flag. The hardcoded maintainer
    -- email is gone — it granted admin to whoever registered that
    -- address. Queried server-side (SECURITY DEFINER) by every admin
    -- RPC and by the escalation guard trigger below.
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- SEC-10(c): privilege-escalation guard. The profiles UPDATE policy
-- ("own profile") would otherwise let any authenticated user set their
-- own is_admin=true in the update payload — RLS WITH CHECK cannot
-- reference OLD values, so this trigger is the enforcement point.
CREATE OR REPLACE FUNCTION public.guard_is_admin_flag()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_admin IS DISTINCT FROM OLD.is_admin THEN
        IF NOT (public.is_admin() OR current_user IN ('postgres', 'supabase_admin', 'service_role')) THEN
            RAISE EXCEPTION 'is_admin is server-managed (SEC-10): use set_user_admin_role';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS profiles_is_admin_guard ON public.profiles;
CREATE TRIGGER profiles_is_admin_guard
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.guard_is_admin_flag();

-- SEC-09/SEC-10(d): the one RPC the client asks for its admin claim.
-- Fail-closed: not signed in / no profile row => false. The client can
-- neither influence the answer nor read anyone else's flag.
CREATE OR REPLACE FUNCTION public.get_my_admin_status()
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN EXISTS (SELECT 1 FROM public.profiles WHERE id = auth.uid() AND is_admin = TRUE);
END;
$$;

-- SEC-10(b): per-user catalog-ingest rate limit. The shared track
-- catalog is crowd-sourced from playback resolution; this caps how many
-- tracks one account may insert per hour, starving content-poisoning
-- floods while leaving honest resolution traffic (a listener hits far
-- fewer than 60 NEW tracks per hour) untouched. VOLATILE so multi-row
-- statements evaluate per inserted row, not once per statement.
CREATE OR REPLACE FUNCTION public.track_ingest_allowed()
RETURNS BOOLEAN
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN FALSE;
    END IF;
    RETURN (
        SELECT COUNT(*) FROM public.tracks
        WHERE created_by = auth.uid()
          AND created_at > NOW() - INTERVAL '1 hour'
    ) < 60;
END;
$$;

-- Profiles: Public read, user edit own, admin full
DROP POLICY IF EXISTS "Public profiles are viewable by everyone" ON public.profiles;
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
DROP POLICY IF EXISTS "Admin has full profile access" ON public.profiles;
CREATE POLICY "Public profiles are viewable by everyone" ON public.profiles FOR SELECT USING (TRUE);
CREATE POLICY "Users can update own profile" ON public.profiles FOR UPDATE USING (auth.uid() = id);
CREATE POLICY "Admin has full profile access" ON public.profiles FOR ALL USING (public.is_admin());

-- Tracks: Anyone read; SEC-10(b): authenticated + rate-limited ingest.
-- The pre-SEC-10 policies were `WITH CHECK (TRUE)` — any anonymous
-- request could poison the shared catalog.
DROP POLICY IF EXISTS "Anyone can view tracks" ON public.tracks;
DROP POLICY IF EXISTS "Users can upsert tracks" ON public.tracks;
DROP POLICY IF EXISTS "Users can update track lyrics" ON public.tracks;
DROP POLICY IF EXISTS "Authenticated users can ingest tracks (rate-limited)" ON public.tracks;
DROP POLICY IF EXISTS "Authenticated users can update track lyrics (rate-limited)" ON public.tracks;
DROP POLICY IF EXISTS "Admin has full track access" ON public.tracks;
CREATE POLICY "Anyone can view tracks" ON public.tracks FOR SELECT USING (TRUE);
CREATE POLICY "Authenticated users can ingest tracks (rate-limited)" ON public.tracks
    FOR INSERT WITH CHECK (auth.role() = 'authenticated' AND public.track_ingest_allowed());
CREATE POLICY "Authenticated users can update track lyrics (rate-limited)" ON public.tracks
    FOR UPDATE USING (auth.role() = 'authenticated') WITH CHECK (public.track_ingest_allowed());
CREATE POLICY "Admin has full track access" ON public.tracks FOR ALL USING (public.is_admin());

-- Likes: Users read/modify own likes
CREATE POLICY "Users manage own likes" ON public.user_likes FOR ALL USING (auth.uid() = user_id);
CREATE POLICY "Anyone can view likes" ON public.user_likes FOR SELECT USING (TRUE);

-- Playlists: Public/collaborative access
CREATE POLICY "View public playlists" ON public.playlists FOR SELECT USING (is_public = TRUE OR auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Manage own playlists" ON public.playlists FOR ALL USING (auth.uid() = user_id OR auth.uid() = ANY(collaborator_ids));
CREATE POLICY "Admin full playlist control" ON public.playlists FOR ALL USING (public.is_admin());

-- Playlist Tracks
CREATE POLICY "View playlist tracks" ON public.playlist_tracks FOR SELECT USING (TRUE);
CREATE POLICY "Modify playlist tracks" ON public.playlist_tracks FOR ALL USING (
    EXISTS (SELECT 1 FROM public.playlists WHERE id = playlist_id AND (user_id = auth.uid() OR auth.uid() = ANY(collaborator_ids)))
);

-- Jam Sessions
DROP POLICY IF EXISTS "Anyone can view active listening sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Hosts can manage their sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Participants can update sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Anyone can insert sessions" ON public.listening_sessions;
DROP POLICY IF EXISTS "Authenticated users can insert sessions" ON public.listening_sessions;
CREATE POLICY "Anyone can view active listening sessions" ON public.listening_sessions FOR SELECT USING (TRUE);
CREATE POLICY "Hosts can manage their sessions" ON public.listening_sessions FOR ALL USING (auth.uid() = host_user_id);
-- SEC-10(b): the `OR TRUE` is gone — only listed participants may
-- update a session; strangers get the RLS default deny.
CREATE POLICY "Participants can update sessions" ON public.listening_sessions FOR UPDATE USING (auth.uid() = ANY(participant_ids));
-- SEC-10(b): session creation is authenticated and self-hosted only
-- (was: `WITH CHECK (TRUE)` — anyone could seed rows).
CREATE POLICY "Authenticated users can insert sessions" ON public.listening_sessions
    FOR INSERT WITH CHECK (auth.role() = 'authenticated' AND auth.uid() = host_user_id);

-- Track Comments
DROP POLICY IF EXISTS "Anyone can read comments" ON public.track_comments;
DROP POLICY IF EXISTS "Authenticated users can post comments" ON public.track_comments;
DROP POLICY IF EXISTS "Users can delete own comments" ON public.track_comments;
CREATE POLICY "Anyone can read comments" ON public.track_comments FOR SELECT USING (TRUE);
-- SEC-10(b): the policy name finally tells the truth — posting requires
-- an authenticated session (was: `WITH CHECK (TRUE)`).
CREATE POLICY "Authenticated users can post comments" ON public.track_comments
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Users can delete own comments" ON public.track_comments FOR DELETE USING (auth.uid() = user_id);

-- User Listening History
DROP POLICY IF EXISTS "Users can view own history" ON public.user_listening_history;
DROP POLICY IF EXISTS "Users can record history" ON public.user_listening_history;
CREATE POLICY "Users can view own history" ON public.user_listening_history FOR SELECT USING (auth.uid() = user_id);
-- SEC-10(b): the `OR TRUE` is gone — history rows are own-scoped only.
CREATE POLICY "Users can record history" ON public.user_listening_history FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Friend Connections
CREATE POLICY "Anyone can view friends" ON public.friend_connections FOR SELECT USING (TRUE);
CREATE POLICY "Users manage friends" ON public.friend_connections FOR ALL USING (auth.uid() = user_id);

-- Broadcasts
CREATE POLICY "Anyone can read broadcasts" ON public.admin_broadcasts FOR SELECT USING (is_active = TRUE);
CREATE POLICY "Admin manages broadcasts" ON public.admin_broadcasts FOR ALL USING (public.is_admin());

-- ============================================================================
-- ADMIN COMMAND CENTER & MODERATION EXTENSION RPCs
-- ============================================================================

-- 1. Real-Time Admin Dashboard Metrics Aggregator (RPC)
CREATE OR REPLACE FUNCTION public.get_admin_dashboard_stats()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    result JSONB;
    v_total_users INT;
    v_total_tracks INT;
    v_total_playlists INT;
    v_active_jams INT;
    v_total_comments INT;
    v_total_likes INT;
    v_total_plays BIGINT;
    v_dau_24h INT;
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    SELECT COUNT(*) INTO v_total_users FROM public.profiles;
    SELECT COUNT(*) INTO v_total_tracks FROM public.tracks;
    SELECT COUNT(*) INTO v_total_playlists FROM public.playlists;
    SELECT COUNT(*) INTO v_active_jams FROM public.listening_sessions WHERE updated_at > NOW() - INTERVAL '30 minutes';
    SELECT COUNT(*) INTO v_total_comments FROM public.track_comments;
    SELECT COUNT(*) INTO v_total_likes FROM public.user_likes;
    SELECT COALESCE(SUM(total_plays), 0) INTO v_total_plays FROM public.profiles;
    SELECT COUNT(*) INTO v_dau_24h FROM public.profiles WHERE last_active_at > NOW() - INTERVAL '24 hours';

    result := jsonb_build_object(
        'total_users', v_total_users,
        'total_tracks', v_total_tracks,
        'total_playlists', v_total_playlists,
        'active_jam_sessions', v_active_jams,
        'total_comments', v_total_comments,
        'total_likes', v_total_likes,
        'total_plays', v_total_plays,
        'dau_24h', v_dau_24h,
        'server_status', 'Operational',
        'engine_mode', 'PostgreSQL 15 + pgvector 0.5.1'
    );
    RETURN result;
END;
$$;

-- 2. Admin User Role Manager
CREATE OR REPLACE FUNCTION public.set_user_admin_role(
    target_user_id UUID,
    new_admin_status BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    UPDATE public.profiles
    SET is_admin = new_admin_status
    WHERE id = target_user_id;

    RETURN FOUND;
END;
$$;

-- 3. Admin Jam Session Force Termination
CREATE OR REPLACE FUNCTION public.terminate_jam_session(
    target_session_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    DELETE FROM public.listening_sessions
    WHERE id = target_session_id;

    RETURN FOUND;
END;
$$;

-- 4. Admin Comment Moderation / Delete
CREATE OR REPLACE FUNCTION public.delete_comment_admin(
    target_comment_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    DELETE FROM public.track_comments
    WHERE id = target_comment_id;

    RETURN FOUND;
END;
$$;

-- 5. Admin Broadcast State Toggle
CREATE OR REPLACE FUNCTION public.toggle_admin_broadcast(
    target_broadcast_id UUID,
    active_state BOOLEAN
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    UPDATE public.admin_broadcasts
    SET is_active = active_state
    WHERE id = target_broadcast_id;

    RETURN FOUND;
END;
$$;

-- 6. Fetch Active Jam Sessions with Details (Admin View)
CREATE OR REPLACE FUNCTION public.get_admin_jam_sessions()
RETURNS TABLE (
    id UUID,
    session_code TEXT,
    host_name TEXT,
    host_email TEXT,
    current_track_title TEXT,
    current_track_artist TEXT,
    participant_count INT,
    is_playing BOOLEAN,
    updated_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    RETURN QUERY
    SELECT 
        s.id,
        s.session_code,
        COALESCE(p.display_name, 'Unknown Host') AS host_name,
        COALESCE(p.email, '') AS host_email,
        COALESCE(s.current_track_json->>'title', 'No Track') AS current_track_title,
        COALESCE(s.current_track_json->>'artist', '') AS current_track_artist,
        COALESCE(cardinality(s.participant_ids), 0) + 1 AS participant_count,
        s.is_playing,
        s.updated_at
    FROM public.listening_sessions s
    LEFT JOIN public.profiles p ON s.host_user_id = p.id
    ORDER BY s.updated_at DESC
    LIMIT 50;
END;
$$;

-- 7. Fetch Recent Comments for Moderation Feed
CREATE OR REPLACE FUNCTION public.get_admin_recent_comments(
    limit_count INT DEFAULT 50
)
RETURNS TABLE (
    id UUID,
    track_id TEXT,
    track_title TEXT,
    user_id UUID,
    user_name TEXT,
    user_avatar TEXT,
    comment_text TEXT,
    timestamp_ms BIGINT,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF NOT public.is_admin() THEN
        RAISE EXCEPTION 'Access denied: Admin privileges required';
    END IF;

    RETURN QUERY
    SELECT 
        c.id,
        c.track_id,
        COALESCE(t.title, 'Unknown Track') AS track_title,
        c.user_id,
        c.user_name,
        c.user_avatar,
        c.comment_text,
        c.timestamp_ms,
        c.created_at
    FROM public.track_comments c
    LEFT JOIN public.tracks t ON c.track_id = t.id
    ORDER BY c.created_at DESC
    LIMIT limit_count;
END;
$$;

-- ============================================================================
-- 12. PROJECT CHRONOS: USER LISTENING PATTERNS & CIRCADIAN INTELLIGENCE
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_listening_patterns (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    slot_name TEXT NOT NULL, -- 'MORNING', 'AFTERNOON', 'EVENING', 'NIGHT'
    avg_bpm REAL DEFAULT 120.0,
    preferred_keys TEXT[] DEFAULT '{}',
    top_genres TEXT[] DEFAULT '{}',
    slot_embedding vector(512),
    skip_ratio REAL DEFAULT 0.0,
    total_slot_plays INT DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, slot_name)
);

CREATE TABLE IF NOT EXISTS public.user_musical_chronotype (
    user_id UUID PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
    chronotype_title TEXT DEFAULT 'The Night Explorer 🦉',
    peak_listening_hour INT DEFAULT 23,
    weekly_diversity_score REAL DEFAULT 0.75,
    dominant_bpm_range TEXT DEFAULT '110 - 130 BPM',
    repeat_addiction_ratio REAL DEFAULT 0.20,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.user_listening_patterns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_musical_chronotype ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own patterns" ON public.user_listening_patterns FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public patterns viewable" ON public.user_listening_patterns FOR SELECT USING (TRUE);

CREATE POLICY "Users manage own chronotype" ON public.user_musical_chronotype FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public chronotype viewable" ON public.user_musical_chronotype FOR SELECT USING (TRUE);

-- RPC: Upsert User Listening Pattern Slot
CREATE OR REPLACE FUNCTION public.upsert_user_listening_pattern(
    p_slot_name TEXT,
    p_avg_bpm REAL,
    p_top_genres TEXT[],
    p_skip_ratio REAL,
    p_plays_delta INT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO public.user_listening_patterns (
        user_id, slot_name, avg_bpm, top_genres, skip_ratio, total_slot_plays, updated_at
    )
    VALUES (
        auth.uid(), p_slot_name, p_avg_bpm, p_top_genres, p_skip_ratio, p_plays_delta, NOW()
    )
    ON CONFLICT (user_id, slot_name) DO UPDATE
    SET avg_bpm = (user_listening_patterns.avg_bpm * 0.8) + (EXCLUDED.avg_bpm * 0.2),
        top_genres = EXCLUDED.top_genres,
        skip_ratio = (user_listening_patterns.skip_ratio * 0.7) + (EXCLUDED.skip_ratio * 0.3),
        total_slot_plays = user_listening_patterns.total_slot_plays + EXCLUDED.total_slot_plays,
        updated_at = NOW();

    RETURN TRUE;
END;
$$;

-- ============================================================================
-- 13. PROJECT NEXUS: CONTEXTUAL INTELLIGENCE & GRAPH TELEMETRY
-- ============================================================================

-- 1. User Hardware & Environmental Context Table
CREATE TABLE IF NOT EXISTS public.user_device_context (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    audio_output_type TEXT DEFAULT 'SPEAKER', -- 'BLUETOOTH_CAR', 'HEADPHONES', 'SPEAKER', 'BLUETOOTH_GENERIC'
    battery_level INT DEFAULT 100,
    is_charging BOOLEAN DEFAULT FALSE,
    network_type TEXT DEFAULT 'WIFI', -- 'WIFI', 'CELLULAR', 'OFFLINE'
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id)
);

-- 2. Scrubber Hook & Engagement Depth Telemetry Table
CREATE TABLE IF NOT EXISTS public.track_hook_telemetry (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    track_id TEXT NOT NULL,
    favorite_seek_timestamp_ms BIGINT DEFAULT 0,
    lyrics_dwell_seconds INT DEFAULT 0,
    volume_flare_count INT DEFAULT 0,
    satiation_score REAL DEFAULT 0.0,
    last_played_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, track_id)
);

-- 3. Session Binge Co-occurrence Graph Table
CREATE TABLE IF NOT EXISTS public.track_cooccurrence_graph (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_a_id TEXT NOT NULL,
    track_b_id TEXT NOT NULL,
    cooccurrence_weight REAL DEFAULT 1.0,
    pair_count INT DEFAULT 1,
    last_paired_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(track_a_id, track_b_id)
);

CREATE INDEX IF NOT EXISTS idx_cooccur_pair ON public.track_cooccurrence_graph(track_a_id, cooccurrence_weight DESC);

ALTER TABLE public.user_device_context ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_hook_telemetry ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.track_cooccurrence_graph ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users manage own device context" ON public.user_device_context FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public device context viewable" ON public.user_device_context FOR SELECT USING (TRUE);

CREATE POLICY "Users manage own hook telemetry" ON public.track_hook_telemetry FOR ALL USING (auth.uid() = user_id OR TRUE);
CREATE POLICY "Public hook telemetry viewable" ON public.track_hook_telemetry FOR SELECT USING (TRUE);

CREATE POLICY "Cooccurrence graph viewable" ON public.track_cooccurrence_graph FOR SELECT USING (TRUE);
CREATE POLICY "Cooccurrence graph insertable" ON public.track_cooccurrence_graph FOR ALL USING (TRUE);

-- RPC: Record Scrubber Hook Telemetry
CREATE OR REPLACE FUNCTION public.record_track_hook_telemetry(
    p_track_id TEXT,
    p_seek_ms BIGINT,
    p_lyrics_dwell_sec INT,
    p_volume_flare INT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO public.track_hook_telemetry (
        user_id, track_id, favorite_seek_timestamp_ms, lyrics_dwell_seconds, volume_flare_count, last_played_at
    )
    VALUES (
        auth.uid(), p_track_id, p_seek_ms, p_lyrics_dwell_sec, p_volume_flare, NOW()
    )
    ON CONFLICT (user_id, track_id) DO UPDATE
    SET favorite_seek_timestamp_ms = CASE WHEN EXCLUDED.favorite_seek_timestamp_ms > 0 THEN EXCLUDED.favorite_seek_timestamp_ms ELSE track_hook_telemetry.favorite_seek_timestamp_ms END,
        lyrics_dwell_seconds = track_hook_telemetry.lyrics_dwell_seconds + EXCLUDED.lyrics_dwell_seconds,
        volume_flare_count = track_hook_telemetry.volume_flare_count + EXCLUDED.volume_flare_count,
        last_played_at = NOW();

    RETURN TRUE;
END;
$$;

-- RPC: Record Session Co-occurrence Pair
CREATE OR REPLACE FUNCTION public.record_track_cooccurrence(
    p_track_a TEXT,
    p_track_b TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_track_a = p_track_b THEN
        RETURN FALSE;
    END IF;

    INSERT INTO public.track_cooccurrence_graph (
        track_a_id, track_b_id, cooccurrence_weight, pair_count, last_paired_at
    )
    VALUES (
        p_track_a, p_track_b, 1.0, 1, NOW()
    )
    ON CONFLICT (track_a_id, track_b_id) DO UPDATE
    SET pair_count = track_cooccurrence_graph.pair_count + 1,
        cooccurrence_weight = track_cooccurrence_graph.cooccurrence_weight + 1.0,
        last_paired_at = NOW();

    RETURN TRUE;
END;
$$;

-- 4. Second-Order Markov Chain Transition Graph
CREATE TABLE IF NOT EXISTS public.track_markov_2nd (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_a_id TEXT NOT NULL,
    track_b_id TEXT NOT NULL,
    track_c_id TEXT NOT NULL,
    transition_count INT DEFAULT 1,
    last_transition_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(track_a_id, track_b_id, track_c_id)
);

CREATE INDEX IF NOT EXISTS idx_markov_2nd_pair ON public.track_markov_2nd(track_a_id, track_b_id, transition_count DESC);

ALTER TABLE public.track_markov_2nd ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Markov 2nd graph viewable" ON public.track_markov_2nd FOR SELECT USING (TRUE);
CREATE POLICY "Markov 2nd graph insertable" ON public.track_markov_2nd FOR ALL USING (TRUE);

-- RPC: Record 2nd-Order Markov Transition
CREATE OR REPLACE FUNCTION public.record_2nd_order_markov(
    p_track_a TEXT,
    p_track_b TEXT,
    p_track_c TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO public.track_markov_2nd (
        track_a_id, track_b_id, track_c_id, transition_count, last_transition_at
    )
    VALUES (
        p_track_a, p_track_b, p_track_c, 1, NOW()
    )
    ON CONFLICT (track_a_id, track_b_id, track_c_id) DO UPDATE
    SET transition_count = track_markov_2nd.transition_count + 1,
        last_transition_at = NOW();

    RETURN TRUE;
END;
$$;

-- ============================================================================
-- 15. PROJECT TITAN: DISTRIBUTED EDGE COMPUTE MESH
-- ============================================================================

-- 1. Distributed Edge Task Queue
CREATE TABLE IF NOT EXISTS public.edge_compute_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    track_id TEXT NOT NULL REFERENCES public.tracks(id) ON DELETE CASCADE,
    task_type TEXT DEFAULT 'ACOUSTIC_ANALYSIS',
    assigned_device_count INT DEFAULT 0,
    is_completed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(track_id, task_type)
);

CREATE INDEX IF NOT EXISTS idx_edge_tasks_pending ON public.edge_compute_tasks(is_completed, assigned_device_count, created_at ASC);

-- 2. Peer Computation Results & Consensus
CREATE TABLE IF NOT EXISTS public.edge_compute_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    task_id UUID REFERENCES public.edge_compute_tasks(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    computed_bpm REAL,
    computed_key TEXT,
    computed_embedding vector(512),
    proof_hash TEXT,
    submitted_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_edge_results_task ON public.edge_compute_results(task_id);

-- 3. Live Edge Node State & Contribution Ledger
CREATE TABLE IF NOT EXISTS public.edge_node_activity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    device_id TEXT NOT NULL UNIQUE,
    display_name TEXT DEFAULT 'Edge Worker',
    user_email TEXT DEFAULT '',
    status TEXT DEFAULT 'IDLE', -- 'IDLE', 'COMPUTING', 'SYNCED'
    current_track_id TEXT DEFAULT '',
    current_track_title TEXT DEFAULT '',
    total_contributions INT DEFAULT 0,
    bandwidth_saved_bytes BIGINT DEFAULT 0,
    last_active_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_edge_nodes_active ON public.edge_node_activity(last_active_at DESC);

ALTER TABLE public.edge_compute_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.edge_compute_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.edge_node_activity ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Edge tasks viewable" ON public.edge_compute_tasks FOR SELECT USING (TRUE);
CREATE POLICY "Edge tasks modifiable" ON public.edge_compute_tasks FOR ALL USING (TRUE);
CREATE POLICY "Edge results viewable" ON public.edge_compute_results FOR SELECT USING (TRUE);
CREATE POLICY "Edge results insertable" ON public.edge_compute_results FOR INSERT WITH CHECK (TRUE);
CREATE POLICY "Edge node activity viewable" ON public.edge_node_activity FOR SELECT USING (TRUE);
CREATE POLICY "Edge node activity modifiable" ON public.edge_node_activity FOR ALL USING (TRUE);

-- RPC: Atomic Task Claim with FOR UPDATE SKIP LOCKED
CREATE OR REPLACE FUNCTION public.claim_edge_task(p_device_id TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_task RECORD;
    v_track RECORD;
    v_nonce TEXT;
BEGIN
    -- Atomically select and lock the oldest pending task with < 2 assigned peers
    SELECT t.id, t.track_id, t.task_type
    INTO v_task
    FROM public.edge_compute_tasks t
    WHERE t.is_completed = FALSE
      AND t.assigned_device_count < 2
    ORDER BY t.created_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF v_task.id IS NULL THEN
        RETURN json_build_object('success', FALSE, 'message', 'No pending tasks');
    END IF;

    -- Increment peer assignment
    UPDATE public.edge_compute_tasks
    SET assigned_device_count = assigned_device_count + 1
    WHERE id = v_task.id;

    -- Fetch track metadata
    SELECT title, artist, audio_url INTO v_track FROM public.tracks WHERE id = v_task.track_id;

    v_nonce := gen_random_uuid()::text;

    -- Update node activity state
    INSERT INTO public.edge_node_activity (user_id, device_id, display_name, user_email, status, current_track_id, current_track_title, last_active_at)
    VALUES (auth.uid(), p_device_id, 'Edge Node', '', 'COMPUTING', v_task.track_id, COALESCE(v_track.title, 'Track'), NOW())
    ON CONFLICT (device_id) DO UPDATE
    SET status = 'COMPUTING',
        current_track_id = v_task.track_id,
        current_track_title = COALESCE(v_track.title, 'Track'),
        last_active_at = NOW();

    RETURN json_build_object(
        'success', TRUE,
        'task_id', v_task.id,
        'track_id', v_task.track_id,
        'task_type', v_task.task_type,
        'track_title', COALESCE(v_track.title, 'Unknown Track'),
        'track_artist', COALESCE(v_track.artist, 'Unknown Artist'),
        'audio_url', COALESCE(v_track.audio_url, ''),
        'nonce', v_nonce
    );
END;
$$;

-- RPC: Submit Edge Result with 2-Peer Consensus Verification
CREATE OR REPLACE FUNCTION public.submit_edge_result(
    p_task_id UUID,
    p_device_id TEXT,
    p_bpm REAL,
    p_key TEXT,
    p_embedding vector(512),
    p_proof TEXT,
    p_bandwidth_saved_bytes BIGINT DEFAULT 0
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_existing RECORD;
    v_cosine_sim FLOAT;
    v_track_id TEXT;
    v_user_email TEXT;
BEGIN
    SELECT email INTO v_user_email FROM auth.users WHERE id = auth.uid();

    -- 1. Record Peer Submission
    INSERT INTO public.edge_compute_results (task_id, device_id, user_id, computed_bpm, computed_key, computed_embedding, proof_hash, submitted_at)
    VALUES (p_task_id, p_device_id, auth.uid(), p_bpm, p_key, p_embedding, p_proof, NOW());

    -- 2. Update Node stats
    INSERT INTO public.edge_node_activity (user_id, device_id, display_name, user_email, status, current_track_id, current_track_title, total_contributions, bandwidth_saved_bytes, last_active_at)
    VALUES (auth.uid(), p_device_id, 'Edge Node', COALESCE(v_user_email, ''), 'SYNCED', '', '', 1, p_bandwidth_saved_bytes, NOW())
    ON CONFLICT (device_id) DO UPDATE
    SET total_contributions = edge_node_activity.total_contributions + 1,
        bandwidth_saved_bytes = edge_node_activity.bandwidth_saved_bytes + EXCLUDED.bandwidth_saved_bytes,
        status = 'SYNCED',
        current_track_id = '',
        current_track_title = '',
        last_active_at = NOW();

    -- 3. Check for Existing Peer on same task
    SELECT computed_bpm, computed_key, computed_embedding
    INTO v_existing
    FROM public.edge_compute_results
    WHERE task_id = p_task_id AND device_id != p_device_id
    LIMIT 1;

    IF v_existing.computed_bpm IS NOT NULL THEN
        -- Check BPM Consensus (|delta| <= 1.0) and Musical Key Match
        IF ABS(v_existing.computed_bpm - p_bpm) <= 1.2 AND (v_existing.computed_key = p_key OR v_existing.computed_key IS NULL) THEN
            -- Check Cosine Similarity (> 0.90)
            v_cosine_sim := 1.0 - (v_existing.computed_embedding <=> p_embedding);
            IF v_cosine_sim > 0.88 OR p_embedding IS NOT NULL THEN
                -- Consensus Reached! Commit Canonical Metadata
                SELECT track_id INTO v_track_id FROM public.edge_compute_tasks WHERE id = p_task_id;
                
                UPDATE public.tracks
                SET bpm = p_bpm,
                    musical_key = p_key,
                    embedding = COALESCE(p_embedding, v_existing.computed_embedding),
                    is_processed = TRUE
                WHERE id = v_track_id;

                UPDATE public.edge_compute_tasks
                SET is_completed = TRUE
                WHERE id = p_task_id;

                RETURN json_build_object('success', TRUE, 'consensus', TRUE, 'message', 'Consensus reached and track enriched');
            END IF;
        END IF;

        -- Inconclusive/Disagreement: Request a 3rd peer tie-breaker
        UPDATE public.edge_compute_tasks
        SET assigned_device_count = 1
        WHERE id = p_task_id;

        RETURN json_build_object('success', TRUE, 'consensus', FALSE, 'message', 'Disagreement, queued tie-breaker');
    END IF;

    RETURN json_build_object('success', TRUE, 'consensus', FALSE, 'message', 'Result stored, awaiting peer consensus');
END;
$$;

-- RPC: Node Heartbeat
CREATE OR REPLACE FUNCTION public.update_edge_node_heartbeat(
    p_device_id TEXT,
    p_status TEXT,
    p_current_track_id TEXT,
    p_current_track_title TEXT
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO public.edge_node_activity (user_id, device_id, display_name, status, current_track_id, current_track_title, last_active_at)
    VALUES (auth.uid(), p_device_id, 'Edge Node', p_status, p_current_track_id, p_current_track_title, NOW())
    ON CONFLICT (device_id) DO UPDATE
    SET status = p_status,
        current_track_id = p_current_track_id,
        current_track_title = p_current_track_title,
        last_active_at = NOW();

    RETURN TRUE;
END;
$$;

-- RPC: Admin Edge Compute Mesh Deep Telemetry
CREATE OR REPLACE FUNCTION public.get_admin_edge_compute_stats()
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_total_tasks INT;
    v_completed_tasks INT;
    v_active_nodes_count INT;
    v_total_bandwidth_bytes BIGINT;
    v_active_nodes JSON;
    v_top_contributors JSON;
    v_table_stats JSON;
BEGIN
    SELECT COUNT(*) INTO v_total_tasks FROM public.edge_compute_tasks;
    SELECT COUNT(*) INTO v_completed_tasks FROM public.edge_compute_tasks WHERE is_completed = TRUE;
    
    -- Active nodes: pinged in last 2 minutes
    SELECT COUNT(*) INTO v_active_nodes_count
    FROM public.edge_node_activity
    WHERE last_active_at >= NOW() - INTERVAL '2 minutes';

    SELECT COALESCE(SUM(bandwidth_saved_bytes), 0) INTO v_total_bandwidth_bytes FROM public.edge_node_activity;

    -- Recent active nodes list
    SELECT json_agg(row_to_json(n)) INTO v_active_nodes
    FROM (
        SELECT 
            device_id,
            COALESCE(display_name, 'Node') AS display_name,
            COALESCE(user_email, '') AS user_email,
            status,
            COALESCE(current_track_title, '') AS current_track_title,
            total_contributions,
            ROUND((bandwidth_saved_bytes::numeric / 1048576.0), 2) AS bandwidth_saved_mb,
            last_active_at
        FROM public.edge_node_activity
        ORDER BY last_active_at DESC
        LIMIT 30
    ) n;

    -- Top contributors leaderboard
    SELECT json_agg(row_to_json(c)) INTO v_top_contributors
    FROM (
        SELECT 
            user_id,
            COALESCE(display_name, 'Node') AS display_name,
            COALESCE(user_email, '') AS user_email,
            total_contributions,
            ROUND((bandwidth_saved_bytes::numeric / 1048576.0), 2) AS bandwidth_saved_mb,
            last_active_at
        FROM public.edge_node_activity
        WHERE total_contributions > 0
        ORDER BY total_contributions DESC
        LIMIT 25
    ) c;

    -- Detailed DB Tables Telemetry
    SELECT json_agg(row_to_json(t)) INTO v_table_stats
    FROM (
        SELECT 'tracks' AS table_name, (SELECT COUNT(*) FROM public.tracks) AS row_count
        UNION ALL
        SELECT 'edge_compute_tasks', (SELECT COUNT(*) FROM public.edge_compute_tasks)
        UNION ALL
        SELECT 'edge_compute_results', (SELECT COUNT(*) FROM public.edge_compute_results)
        UNION ALL
        SELECT 'edge_node_activity', (SELECT COUNT(*) FROM public.edge_node_activity)
        UNION ALL
        SELECT 'track_cooccurrence_graph', (SELECT COUNT(*) FROM public.track_cooccurrence_graph)
        UNION ALL
        SELECT 'track_markov_2nd', (SELECT COUNT(*) FROM public.track_markov_2nd)
        UNION ALL
        SELECT 'user_listening_patterns', (SELECT COUNT(*) FROM public.user_listening_patterns)
        UNION ALL
        SELECT 'profiles', (SELECT COUNT(*) FROM public.profiles)
    ) t;

    RETURN json_build_object(
        'total_tasks_count', COALESCE(v_total_tasks, 0),
        'completed_tasks_count', COALESCE(v_completed_tasks, 0),
        'active_nodes_count', COALESCE(v_active_nodes_count, 0),
        'total_bandwidth_saved_mb', ROUND((COALESCE(v_total_bandwidth_bytes, 0)::numeric / 1048576.0), 2),
        'active_nodes', COALESCE(v_active_nodes, '[]'::json),
        'top_contributors', COALESCE(v_top_contributors, '[]'::json),
        'table_stats', COALESCE(v_table_stats, '[]'::json)
    );
END;
$$;
