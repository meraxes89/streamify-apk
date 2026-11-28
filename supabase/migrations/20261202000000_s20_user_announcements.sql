-- ============================================================================
-- S20 Phase 2: Authenticated 1:1 & Segment Targeted User Announcements
-- ============================================================================
-- Complements the Ed25519-signed RemoteAnnouncementSource (which remains the
-- global authority for popupModalEnabled and revokedIds kill-switches).
-- Client enforces DeepLinkAllowlist on all returned DeepLink actions.

CREATE TABLE IF NOT EXISTS public.user_announcements (
    id TEXT PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    schema_version INT NOT NULL DEFAULT 1,
    priority INT NOT NULL DEFAULT 0,
    presentation TEXT NOT NULL DEFAULT 'INBOX'
        CHECK (presentation IN ('MODAL', 'SHEET', 'BANNER', 'SNACKBAR', 'INBOX', 'SILENT')),
    content JSONB NOT NULL,
    action JSONB NOT NULL DEFAULT '{"type":"com.streamify.announcements.AnnouncementAction.None"}'::jsonb,
    targeting JSONB NOT NULL DEFAULT '{}'::jsonb,
    frequency JSONB NOT NULL DEFAULT '{"maxImpressions":1,"cooldownMs":604800000}'::jsonb,
    dismiss_policy TEXT NOT NULL DEFAULT 'LATER_SNOOZE'
        CHECK (dismiss_policy IN ('LATER_SNOOZE', 'PERMANENT', 'ACT_CONSUMES')),
    start_at_epoch_ms BIGINT NOT NULL DEFAULT 0,
    end_at_epoch_ms BIGINT NOT NULL DEFAULT 9223372036854775807,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_announcements_user_active
    ON public.user_announcements (user_id, priority DESC, created_at DESC)
    WHERE NOT revoked;

ALTER TABLE public.user_announcements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_announcements FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS user_announcements_select_own ON public.user_announcements;
CREATE POLICY user_announcements_select_own
    ON public.user_announcements
    FOR SELECT
    TO authenticated
    USING ((user_id IS NULL OR user_id = auth.uid()) AND NOT revoked);

DROP POLICY IF EXISTS user_announcements_admin_all ON public.user_announcements;
CREATE POLICY user_announcements_admin_all
    ON public.user_announcements
    FOR ALL
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND p.is_admin = TRUE
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND p.is_admin = TRUE
        )
    );

CREATE OR REPLACE FUNCTION public.get_targeted_announcements()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_now_ms BIGINT := (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT;
    v_result JSONB;
BEGIN
    IF v_uid IS NULL THEN
        RETURN '[]'::jsonb;
    END IF;

    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'id', a.id,
                'schemaVersion', a.schema_version,
                'priority', a.priority,
                'presentation', a.presentation,
                'content', a.content,
                'action', a.action,
                'targeting', a.targeting,
                'frequency', a.frequency,
                'dismissPolicy', a.dismiss_policy,
                'startAtEpochMs', a.start_at_epoch_ms,
                'endAtEpochMs', a.end_at_epoch_ms
            )
            ORDER BY a.priority DESC, a.created_at DESC
        ),
        '[]'::jsonb
    )
    INTO v_result
    FROM (
        SELECT *
        FROM public.user_announcements ua
        WHERE NOT ua.revoked
          AND (ua.user_id IS NULL OR ua.user_id = v_uid)
          AND v_now_ms BETWEEN ua.start_at_epoch_ms AND ua.end_at_epoch_ms
        ORDER BY ua.priority DESC, ua.created_at DESC
        LIMIT 50
    ) a;

    RETURN v_result;
END;
$$;

REVOKE ALL ON FUNCTION public.get_targeted_announcements() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.get_targeted_announcements() TO authenticated;
