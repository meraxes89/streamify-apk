-- ══════════════════════════════════════════════════════════════════════
-- STREAMIFY JAM — PHASE 3: HOST LEASE & DETERMINISTIC SUCCESSION (P8)
-- Hand-applied migration. Run once in Supabase SQL Editor.
--
-- Fixes vs draft v2:
--   * Real column names (host_user_id) — compiles against live schema.
--   * Membership guards on BOTH grant paths: an authenticated outsider can
--     no longer claim an abandoned room by knowing the session UUID.
--   * host_epoch persisted on the row: laggard guests read the authoritative
--     fencing token instead of missing a transient RPC return value.
--   * New host's participant_last_seen stamped at grant time (never born
--     already-stale).
--   * Units standardized to MILLISECONDS, matching Phase-2 columns.
--
-- ELECTION CONTRACT (A+B hybrid):
--   Healthy path  — lowest user_id (lowercase hex compare) among members
--                   seen within the last 30s claims during the expiry grace
--                   window; server verifies caller == that member.
--   Vacuum path   — once the lease is >15s past expiry, ANY current
--                   participant may claim; FOR UPDATE serialization makes
--                   first-responder win. Authority vacuum is impossible.
-- ══════════════════════════════════════════════════════════════════════

ALTER TABLE public.listening_sessions
ADD COLUMN IF NOT EXISTS host_lease_expires_at timestamptz,
ADD COLUMN IF NOT EXISTS last_tick_pos_ms bigint,
ADD COLUMN IF NOT EXISTS last_tick_mono_ms bigint,
ADD COLUMN IF NOT EXISTS host_epoch bigint NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS participant_last_seen jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_sessions_lease
    ON public.listening_sessions (host_lease_expires_at)
    WHERE host_lease_expires_at IS NOT NULL;

-- ── Liveness helper: caller's "seen" stamp refresh ───────────────────
CREATE OR REPLACE FUNCTION public.jam_touch_presence(
    p_session_id uuid,
    p_user_id uuid
) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    UPDATE public.listening_sessions
    SET participant_last_seen = jsonb_set(
            COALESCE(participant_last_seen, '{}'::jsonb),
            ARRAY[p_user_id::text],
            to_jsonb((extract(epoch from now()) * 1000)::bigint)
        ),
        updated_at = now()
    WHERE id = p_session_id;
END;
$$;

-- ── Host heartbeat: extends lease OR reports demotion ───────────────
-- Returns 'HOST' when authority held, 'DEMOTED' when lost to a takeover.
CREATE OR REPLACE FUNCTION public.jam_heartbeat(
    p_session_id uuid,
    p_pos_ms bigint,
    p_mono_ms bigint
) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_host uuid;
    v_expires timestamptz;
    v_uid uuid := auth.uid();
BEGIN
    IF v_uid IS NULL THEN RETURN 'DEMOTED'; END IF;

    SELECT host_user_id, host_lease_expires_at
    INTO v_host, v_expires
    FROM public.listening_sessions
    WHERE id = p_session_id
    FOR UPDATE;

    -- Liveness stamp for every caller (hosts and guests alike).
    PERFORM public.jam_touch_presence(p_session_id, v_uid);

    IF v_host = v_uid THEN
        UPDATE public.listening_sessions
        SET host_lease_expires_at = now() + interval '15 seconds',
            last_tick_pos_ms = p_pos_ms,
            last_tick_mono_ms = p_mono_ms,
            updated_at = now()
        WHERE id = p_session_id;
        RETURN 'HOST';
    END IF;

    -- Caller believes they are host but the row disagrees → fenced out.
    RETURN 'DEMOTED';
END;
$$;

-- ── Takeover: A+B hybrid succession, server-arbitrated ──────────────
-- Returns the authoritative epoch fencing token (adopt verbatim).
CREATE OR REPLACE FUNCTION public.jam_takeover(
    p_session_id uuid,
    p_advisory_successor uuid,
    p_pivot_pos_ms bigint,
    p_pivot_mono_ms bigint
) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_expires timestamptz;
    v_seen jsonb;
    v_participants uuid[];
    v_lowest_seen uuid;
    v_is_member boolean;
    v_grace boolean;
    v_new_epoch bigint;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'unauthenticated';
    END IF;

    SELECT host_lease_expires_at, participant_last_seen, participant_ids
    INTO v_expires, v_seen, v_participants
    FROM public.listening_sessions
    WHERE id = p_session_id
    FOR UPDATE;

    IF v_expires IS NULL THEN
        RAISE EXCEPTION 'lease_not_initialized';
    END IF;

    v_is_member := (v_uid = ANY (v_participants));
    IF NOT v_is_member THEN
        RAISE EXCEPTION 'not_a_participant';
    END IF;

    -- Lease must be expired before anyone may claim.
    IF v_expires > now() THEN
        RAISE EXCEPTION 'lease_not_expired';
    END IF;

    -- Lowest recently-seen member (30s window), lowercase-hex deterministic.
    SELECT min(key::uuid)::text::uuid INTO v_lowest_seen
    FROM jsonb_each_text(v_seen)
    WHERE value::bigint > (extract(epoch from now()) * 1000 - 30000)::bigint;

    v_grace := v_expires < now() - interval '15 seconds';

    IF v_lowest_seen IS NOT NULL AND v_lowest_seen = v_uid THEN
        -- Deterministic path.
        NULL;
    ELSIF v_grace THEN
        -- Vacuum breaker: first responder inside the serialized txn wins.
        NULL;
    ELSE
        RAISE EXCEPTION 'awaiting_deterministic_successor';
    END IF;

    -- Authoritative fencing token (server is the single epoch source).
    SELECT COALESCE(MAX(host_epoch), 0) + 1000 INTO v_new_epoch
    FROM public.listening_sessions;

    UPDATE public.listening_sessions
    SET host_user_id = v_uid,
        host_lease_expires_at = now() + interval '15 seconds',
        host_epoch = v_new_epoch,
        last_tick_pos_ms = p_pivot_pos_ms,
        last_tick_mono_ms = p_pivot_mono_ms,
        participant_last_seen = jsonb_set(
            COALESCE(participant_last_seen, '{}'::jsonb),
            ARRAY[v_uid::text],
            to_jsonb((extract(epoch from now()) * 1000)::bigint)
        ),
        updated_at = now()
    WHERE id = p_session_id;

    PERFORM pg_notify(
        'jam:' || p_session_id::text,
        json_build_object(
            'event', 'HOST_TAKEOVER',
            'user_id', v_uid,
            'epoch', v_new_epoch
        )::text
    );

    RETURN v_new_epoch;
END;
$$;

REVOKE ALL ON FUNCTION public.jam_touch_presence(uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.jam_touch_presence(uuid, uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.jam_heartbeat(uuid, bigint, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.jam_heartbeat(uuid, bigint, bigint) TO authenticated;
REVOKE ALL ON FUNCTION public.jam_takeover(uuid, uuid, bigint, bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.jam_takeover(uuid, uuid, bigint, bigint) TO authenticated;

-- Guests discover takeover/lease state via Realtime Postgres Changes on this
-- table (host_user_id / host_epoch / last_tick_* are UPDATE-visible). Ensure
-- the table is published:
ALTER PUBLICATION supabase_realtime ADD TABLE public.listening_sessions;
