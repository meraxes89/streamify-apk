-- ============================================================================
-- Migration: 2026_11_p7_v4_1_device_fleet_and_version_telemetry.sql
-- Description: Fleet Device Registry & Multi-Device Version Telemetry Engine
-- Author: Sireen (zephyr4289@gmail.com)
-- Contract: Supports N concurrent devices per user account with build, OS,
--           and architecture telemetry, automatic version transition logging,
--           remote revocation, and fleet observability views.
-- ============================================================================

-- 1. Extend user_devices with rich build and hardware telemetry
ALTER TABLE public.user_devices
  ADD COLUMN IF NOT EXISTS version_code   INTEGER      NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS git_sha        TEXT         NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS build_flavor   TEXT         NOT NULL DEFAULT 'standard',
  ADD COLUMN IF NOT EXISTS device_model   TEXT         NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS os_version     TEXT         NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS architecture   TEXT         NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  ADD COLUMN IF NOT EXISTS is_revoked     BOOLEAN      NOT NULL DEFAULT false;

-- Backfill last_active_at from last_seen for existing rows
UPDATE public.user_devices
   SET last_active_at = last_seen
 WHERE last_active_at IS NULL;

-- 2. Append-Only Upgrade Transition Ledger
CREATE TABLE IF NOT EXISTS public.user_device_version_history (
  id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           UUID         NOT NULL REFERENCES public.profiles (id) ON DELETE CASCADE,
  device_id         TEXT         NOT NULL,
  from_version      TEXT,
  from_version_code INTEGER,
  to_version        TEXT         NOT NULL,
  to_version_code   INTEGER      NOT NULL,
  to_git_sha        TEXT         NOT NULL DEFAULT '',
  upgraded_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 3. High-Performance Indexes
CREATE INDEX IF NOT EXISTS idx_user_devices_fleet
  ON public.user_devices (app_version, version_code, last_active_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_devices_user_active
  ON public.user_devices (user_id, last_active_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_version_history_user
  ON public.user_device_version_history (user_id, upgraded_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_version_history_target
  ON public.user_device_version_history (to_version, to_version_code, upgraded_at DESC);

-- 4. Row Level Security (RLS)
ALTER TABLE public.user_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_device_version_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "devices_owner" ON public.user_devices;
DROP POLICY IF EXISTS "user_devices_owner" ON public.user_devices;
CREATE POLICY "user_devices_owner" ON public.user_devices
  FOR ALL USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "version_history_owner" ON public.user_device_version_history;
CREATE POLICY "version_history_owner" ON public.user_device_version_history
  FOR SELECT USING (auth.uid() = user_id);

-- 5. Stored Procedures: Device Session Registration & Version Transition Engine
CREATE OR REPLACE FUNCTION public.record_device_session(
  p_device_id     TEXT,
  p_platform      TEXT,
  p_app_version   TEXT,
  p_version_code  INTEGER,
  p_git_sha       TEXT DEFAULT '',
  p_build_flavor  TEXT DEFAULT 'standard',
  p_device_model  TEXT DEFAULT '',
  p_os_version    TEXT DEFAULT '',
  p_architecture  TEXT DEFAULT ''
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid       UUID := auth.uid();
  v_existing  public.user_devices%ROWTYPE;
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'record_device_session: unauthenticated';
  END IF;

  IF p_device_id IS NULL OR length(trim(p_device_id)) = 0 THEN
    RAISE EXCEPTION 'record_device_session: device_id required';
  END IF;

  SELECT * INTO v_existing
    FROM public.user_devices
   WHERE user_id = v_uid AND device_id = p_device_id;

  IF NOT FOUND THEN
    -- First time device registration
    INSERT INTO public.user_devices (
      user_id, device_id, platform, app_version, version_code, git_sha,
      build_flavor, device_model, os_version, architecture, first_seen_at, last_seen, last_active_at
    ) VALUES (
      v_uid, p_device_id, coalesce(p_platform, 'unknown'), coalesce(p_app_version, 'unknown'),
      coalesce(p_version_code, 0), coalesce(p_git_sha, ''), coalesce(p_build_flavor, 'standard'),
      coalesce(p_device_model, ''), coalesce(p_os_version, ''), coalesce(p_architecture, ''),
      now(), now(), now()
    );

    INSERT INTO public.user_device_version_history (
      user_id, device_id, to_version, to_version_code, to_git_sha
    ) VALUES (
      v_uid, p_device_id, coalesce(p_app_version, 'unknown'), coalesce(p_version_code, 0), coalesce(p_git_sha, '')
    );
  ELSE
    -- Check if application was upgraded or re-spun
    IF (v_existing.version_code <> p_version_code AND p_version_code IS NOT NULL AND p_version_code > 0)
       OR (p_git_sha IS NOT NULL AND p_git_sha <> '' AND v_existing.git_sha <> p_git_sha) THEN
      INSERT INTO public.user_device_version_history (
        user_id, device_id, from_version, from_version_code, to_version, to_version_code, to_git_sha
      ) VALUES (
        v_uid, p_device_id, v_existing.app_version, v_existing.version_code,
        coalesce(p_app_version, v_existing.app_version),
        coalesce(p_version_code, v_existing.version_code),
        coalesce(p_git_sha, v_existing.git_sha)
      );
    END IF;

    -- Update active device record
    UPDATE public.user_devices
       SET platform       = coalesce(p_platform, platform),
           app_version    = coalesce(p_app_version, app_version),
           version_code   = case when p_version_code > 0 then p_version_code else version_code end,
           git_sha        = case when p_git_sha <> '' then p_git_sha else git_sha end,
           build_flavor   = coalesce(p_build_flavor, build_flavor),
           device_model   = case when p_device_model <> '' then p_device_model else device_model end,
           os_version     = case when p_os_version <> '' then p_os_version else os_version end,
           architecture   = case when p_architecture <> '' then p_architecture else architecture end,
           last_seen      = now(),
           last_active_at = now()
     WHERE user_id = v_uid AND device_id = p_device_id;
  END IF;

  RETURN jsonb_build_object(
    'status', 'ok',
    'is_revoked', coalesce(v_existing.is_revoked, false)
  );
END;
$$;

REVOKE ALL ON FUNCTION public.record_device_session(TEXT,TEXT,TEXT,INTEGER,TEXT,TEXT,TEXT,TEXT,TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_device_session(TEXT,TEXT,TEXT,INTEGER,TEXT,TEXT,TEXT,TEXT,TEXT) TO authenticated;

-- 6. Remote Device Revocation Procedure
CREATE OR REPLACE FUNCTION public.revoke_device_session(
  p_device_id TEXT
) RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
  v_uid UUID := auth.uid();
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'revoke_device_session: unauthenticated';
  END IF;

  UPDATE public.user_devices
     SET is_revoked = true,
         last_seen = now(),
         last_active_at = now()
   WHERE user_id = v_uid AND device_id = p_device_id;

  RETURN jsonb_build_object('status', 'ok', 'device_id', p_device_id, 'revoked', true);
END;
$$;

REVOKE ALL ON FUNCTION public.revoke_device_session(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.revoke_device_session(TEXT) TO authenticated;

-- 7. Query User's Active Devices
CREATE OR REPLACE FUNCTION public.get_my_devices()
RETURNS SETOF public.user_devices
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
  SELECT * FROM public.user_devices
   WHERE user_id = auth.uid()
   ORDER BY last_active_at DESC;
$$;

REVOKE ALL ON FUNCTION public.get_my_devices() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_my_devices() TO authenticated;

-- 8. Operator Fleet Observability View
CREATE OR REPLACE VIEW public.admin_fleet_overview AS
SELECT
  app_version,
  version_code,
  platform,
  build_flavor,
  count(*) AS active_devices,
  count(DISTINCT user_id) AS distinct_users,
  max(last_active_at) AS latest_activity
FROM public.user_devices
WHERE NOT is_revoked
GROUP BY app_version, version_code, platform, build_flavor;
