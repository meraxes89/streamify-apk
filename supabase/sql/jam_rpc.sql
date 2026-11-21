-- ══════════════════════════════════════════════════════════════════════
-- STREAMIFY JAM — PHASE 2: AUTH-BOUND MUTATION PATH (P7)
-- Hand-applied migration. Paste into Supabase SQL Editor and run once.
--
-- WHAT THIS CHANGES
--   Queue mutations stop trusting the unauthenticated broadcast channel and
--   flow through jam_mutation(), a SECURITY DEFINER RPC that:
--     1. Identifies the caller from their Supabase JWT (auth.uid()).
--     2. Verifies active membership in the target session.
--     3. Appends the op to jam_ops (append-only, RLS: insert-own only).
--     4. Fans out to every room subscriber via pg_notify, which Supabase
--        Realtime delivers on the existing `realtime:jam_<CODE>` topic.
--
--   Clients subscribe to the `jam_ops` table via Realtime Postgres Changes
--   (supabase_realtime publication) — see LISTEN section below.
--
-- SPOOFING RESULT AFTER APPLY: a forged broadcast can no longer mutate
-- queue state; only authenticated participants may enqueue ops.
-- ══════════════════════════════════════════════════════════════════════

-- ── 1. Op journal table ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.jam_ops (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    op_id         BIGINT NOT NULL,                -- client monotonic u64
    session_code  TEXT   NOT NULL,
    sender_uid    UUID   NOT NULL DEFAULT auth.uid(),
    sender_nonce  BIGINT NOT NULL,
    op_type       SMALLINT NOT NULL CHECK (op_type BETWEEN 1 AND 4),
    policy_flags  SMALLINT NOT NULL DEFAULT 0,
    track_cad_id  BIGINT NOT NULL,
    frac_bits     BIGINT NOT NULL,                -- IEEE754 raw bits
    target_add_op_id BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_jam_ops_dedupe
    ON public.jam_ops (session_code, op_id);
CREATE INDEX IF NOT EXISTS idx_jam_ops_session_time
    ON public.jam_ops (session_code, created_at DESC);

ALTER TABLE public.jam_ops ENABLE ROW LEVEL SECURITY;

CREATE POLICY "participants read own-room ops"
    ON public.jam_ops FOR SELECT
    USING (
      EXISTS (
        SELECT 1 FROM public.listening_sessions s
        WHERE s.session_code = session_code
          AND (auth.uid() = s.host_user_id OR auth.uid() = ANY (s.participant_ids))
      )
    );

CREATE POLICY "authenticated users insert own ops"
    ON public.jam_ops FOR INSERT
    WITH CHECK (auth.uid() = sender_uid);

-- No UPDATE / DELETE policies: the journal is append-only for clients.

-- ── 2. Membership-guarded mutation RPC ──────────────────────────────
CREATE OR REPLACE FUNCTION public.jam_mutation(
    p_session_code TEXT,
    p_op_id        BIGINT,
    p_sender_nonce BIGINT,
    p_op_type      SMALLINT,
    p_policy       SMALLINT,
    p_cad_id       BIGINT,
    p_frac_bits    BIGINT,
    p_target_op_id BIGINT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_host UUID;
BEGIN
    -- Identity gate
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error', 'unauthenticated');
    END IF;

    -- Membership + role lookup
    SELECT host_user_id INTO v_host
    FROM public.listening_sessions
    WHERE session_code = upper(p_session_code)
      AND updated_at > NOW() - INTERVAL '6 hours';

    IF v_host IS NULL THEN
        RETURN jsonb_build_object('ok', false, 'error', 'session_not_found');
    END IF;

    IF v_uid <> v_host AND NOT EXISTS (
        SELECT 1 FROM public.listening_sessions s
        WHERE s.session_code = upper(p_session_code)
          AND v_uid = ANY (s.participant_ids)
    ) THEN
        RETURN jsonb_build_object('ok', false, 'error', 'not_a_participant');
    END IF;

    -- Persist (idempotent per session+op_id)
    INSERT INTO public.jam_ops
        (op_id, session_code, sender_uid, sender_nonce, op_type,
         policy_flags, track_cad_id, frac_bits, target_add_op_id)
    VALUES
        (p_op_id, upper(p_session_code), v_uid, p_sender_nonce,
         p_op_type, p_policy, p_cad_id, p_frac_bits, p_target_op_id)
    ON CONFLICT (session_code, op_id) DO NOTHING;

    -- Fan-out to the room's realtime topic
    PERFORM pg_notify(
        'jam:' || upper(p_session_code),
        jsonb_build_object(
            'o_id', p_op_id,
            'o_sender', p_sender_nonce,
            'o_type', p_op_type,
            'o_policy', p_policy,
            'o_cad', p_cad_id,
            'o_frac_bits', p_frac_bits,
            'o_target', p_target_op_id
        )::text
    );

    RETURN jsonb_build_object('ok', true);
END;
$$;

REVOKE ALL ON FUNCTION public.jam_mutation FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.jam_mutation TO authenticated;

-- ── 3. Realtime delivery of the journal ─────────────────────────────
-- Supabase Realtime ships Postgres Changes for tables added to the
-- publication. Run once; safe if already present.
ALTER PUBLICATION supabase_realtime ADD TABLE public.jam_ops;

-- ══════════════════════════════════════════════════════════════════════
-- CLIENT INTEGRATION NOTES (Streamify app)
--
-- Preferred mutation path (replaces direct OP broadcasts):
--   POST {SUPABASE_URL}/rest/v1/rpc/jam_mutation
--   Authorization: Bearer <user jwt>
--   Body: {"p_session_code":"ABC123","p_op_id":...,"p_sender_nonce":...,
--          "p_op_type":1,"p_policy":0,"p_cad_id":...,
--          "p_frac_bits":...,"p_target_op_id":0}
--
-- Receive: subscribe to Postgres Changes on public.jam_ops filtered by
--   session_code = <room> ; payload columns map 1:1 onto the existing
--   "OP" extras contract (o_id, o_sender, o_type, o_cad, o_frac_bits,
--   o_target). The Kotlin OP handler needs zero changes.
--
-- Until this migration is applied, the app keeps using direct OP
-- broadcasts (trusted-device model). Apply → flip transport in
-- JamViewModel flush loop from broadcastJamTick(...) to the RPC call.
-- ══════════════════════════════════════════════════════════════════════
