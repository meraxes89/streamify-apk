# Backend DB Sync — Runbook & 2026-11-10 Execution Record

> How to reconcile the live Supabase project with the `supabase/` repo
> contract, how the app is wired to the backend, and what the 2026-11-10
> sync found and fixed. Audience: whoever touches the backend next.

## Why this exists

The repository's `supabase/` folder is the database contract — but nothing
in CI enforces that the **live** project matches it. The SQL test suite
(`supabase/sql/tests/`) proves `schema.sql` on a *disposable* container; a
production database drifts silently every time a migration lands in the
tree and nobody hand-applies it. That happened: on 2026-11-10 the live
project was three migrations and one security posture behind.

## What the 2026-11-10 audit found

Live project: `dbgtvzgwixerhnwvhtil` ("zephyr4289's Project", ap-south-1).
Method: schema/column/policy/function diff of `information_schema` +
`pg_policies` + `pg_publication_tables` against `schema.sql`, then
behavioral probes with `SET ROLE` + `request.jwt.claims` (the same
mechanism PostgREST uses).

| Missing layer | Consequence for the app |
|---|---|
| `sql/jam_rpc.sql` (Phase 2) — no `jam_ops` table, no `jam_mutation()` RPC, table absent from `supabase_realtime` | Jam queue mutations stayed on the spoofable broadcast path |
| `sql/jam_lease.sql` (Phase 3) — no lease columns (`host_lease_expires_at`, `last_tick_pos_ms`, `last_tick_mono_ms`, `host_epoch`, `participant_last_seen`), no `jam_heartbeat()` / `jam_takeover()` / `jam_touch_presence()` | Host succession & fencing had nothing to bind to |
| `migrations/2026_01_stats_overhaul.sql` — no `user_track_plays` / `user_play_events`, no `increment_user_track_play()` / `upsert_user_telemetry()` / `get_admin_top_tracks()` | Cross-device Wrapped stats + top-songs leaderboard dead |
| `migrations/2026_11_sec10_rls_hardening.sql` — `is_admin()` still carried the hardcoded-email bypass; permissive pre-SEC-10 policies (`Anyone can insert sessions`, `Users can upsert tracks`) still live | The SEC-10 hardening had never reached production |
| Column drift — `profiles.bio/favorite_genre/is_private`, `listening_sessions.host_clock_timestamp`, `playlists.likes_count` missing | Profile-editor PATCHes 400'd (PGRST204); Jam drift math broken |
| Realtime publication empty | No Postgres Changes delivery for jam rooms |

Plus two defects in the **tree itself**, found while syncing (both fixed
in the same PR as this doc):

1. **`profiles.top_track` never existed.**
   `upsert_user_telemetry()` (stats overhaul) UPDATEs it and
   `SupabaseStatsClient` SELECTs it, but no schema file created it — the
   RPC failed at runtime with `column "top_track" does not exist` on
   *every* install, fresh or live. CI never saw it because the
   `supabase-rls` job applies `schema.sql` only and never calls the stats
   RPCs. Fixed: column added to `schema.sql` + retro-added to live DBs by
   `migrations/2026_11_drift_catchup.sql`.

2. **The `is_admin` guard trigger was neutered by its own `SECURITY DEFINER`.**
   Inside a DEFINER function `current_user` is the *owner* (postgres), so
   the `current_user IN ('postgres', 'supabase_admin', 'service_role')`
   allowlist matched for **every** caller — any authenticated user could
   `PATCH /rest/v1/profiles {"is_admin": true}` and self-promote. The CI
   test meant to pin this (rls_test.sql §8) caught its own regression
   marker inside `EXCEPTION WHEN raise_exception`, so it could never
   fail — that's how it shipped green. Fixed: `guard_is_admin_flag()` is
   now `SECURITY INVOKER` (allowlist still admits `set_user_admin_role`'s
   definer context and DBA/service_role sessions), and the three
   tautological test blocks (§8, §9a, §9b) were restructured so the
   regression `RAISE` sits *outside* the handler that expects the denial.
   Verified live: authenticated self-promotion now raises
   `is_admin is server-managed (SEC-10)`.

## The runbook (apply order matters)

```bash
# Management API equivalent of the SQL Editor, one file at a time:
1. supabase/migrations/2026_11_drift_catchup.sql   # column catch-up (before stats)
2. supabase/schema.sql                              # idempotent base contract
3. supabase/migrations/2026_01_stats_overhaul.sql
4. supabase/migrations/2026_11_sec10_rls_hardening.sql
5. supabase/sql/jam_rpc.sql                         # creates jam_ops + publication
6. supabase/sql/jam_lease.sql                       # lease columns + RPCs + publication
```

Notes, learned the hard way:

* `schema.sql` is now **fully idempotent** (every `CREATE POLICY` has a
  `DROP POLICY IF EXISTS` guard — added in this PR; previously only the
  SEC-10 tables had guards, so re-applying against a live project failed
  with `42710 policy already exists` on 29 statements).
* If you re-apply a Supabase-default-privileges grant file
  (`sql/tests/01_grants.sql`), **re-run the four jam REVOKEs afterwards**
  (`jam_mutation`, `jam_touch_presence`, `jam_heartbeat`, `jam_takeover`
  — `REVOKE ALL ... FROM PUBLIC, anon`), or you undo their
  authenticated-only execute posture.
* `jam_rpc.sql` / `jam_lease.sql` end with
  `ALTER PUBLICATION supabase_realtime ADD TABLE ...` — that errors with
  "already added" on re-runs; treat it as a skip, not a failure.

## App-side configuration

The app reads its backend binding at build time (`app/build.gradle.kts`):

| Input | Source | Notes |
|---|---|---|
| `SUPABASE_URL` | `local.properties` → env → hardcoded default `https://dbgtvzgwixerhnwvhtil.supabase.co` | Default matches the live project |
| `SUPABASE_ANON_KEY` | `local.properties` → env → **empty** | SEC-01: the service_role fallback was purged; empty = anon-degraded build |

The anon key is a *publishable* credential (RLS is the fence), but it is
JWT-shaped, so gitleaks' default rules flag it — it must never be
committed. It flows two ways:

1. **Local dev** — `local.properties` (gitignored):
   ```properties
   SUPABASE_URL=https://dbgtvzgwixerhnwvhtil.supabase.co
   SUPABASE_ANON_KEY=<anon key from Supabase dashboard → Settings → API>
   ```
2. **CI** — repo secret `SUPABASE_ANON_KEY` (set 2026-11-10), passed as
   env to the assemble jobs in `.github/workflows/_ci-android.yml`, so
   CI-built APKs talk to the real backend. Fork PRs see an empty value
   and build in the historical anon-degraded mode; nothing leaks.

Rotating the key (or moving projects) = update the dashboard value, the
repo secret, and your local `local.properties`. No code changes.

## Verification performed (2026-11-10)

All against the live project, after the sync:

* **17/17 structural checks** — tables (22), columns (drift set + lease
  set + `top_track`), functions (jam ×4, stats ×3, SEC-10 ×3, admin
  dash ×2), `is_admin()`/`handle_new_user()` email-free, guard trigger
  present, RLS on every table, hardened policy sets, realtime
  publication, API-role grants, anon denied `jam_mutation` execute,
  `upsert_user_telemetry()` executes.
* **15/15 behavioral probes** (throwaway user, fully cleaned up) —
  sign-up mints non-admin; anon writes rejected (tracks, sessions);
  own-row writes allowed, foreign-row writes rejected
  (`user_play_events`); `is_admin` self-promotion blocked by the guard;
  own profile update allowed; stats RPC round-trips
  (`increment_user_track_play`, `upsert_user_telemetry` writing
  `top_track`); `jam_mutation` authenticated-reachable / anon-denied;
  `get_my_admin_status()` false for non-admins.
* **Admin path regression check** — `set_user_admin_role()` still
  promotes (definer context passes the INVOKER guard); verified inside a
  rolled-back transaction, zero residue.
* **REST end-to-end as the app** (anon key, no user JWT) — catalog /
  jam rooms / broadcasts / `profiles.top_track` reads 200; anonymous
  `tracks` insert 401 (RLS); `jam_mutation` 401 (execute revoked).

Re-verify cheaply next time:

```sql
-- one-shot drift probe (anything > 0 is drift):
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'
 AND table_name IN ('jam_ops','user_track_plays','user_play_events');  -- want 3
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='public'
 AND routine_name IN ('jam_mutation','jam_heartbeat','jam_takeover','jam_touch_presence',
  'increment_user_track_play','upsert_user_telemetry','get_admin_top_tracks',
  'get_my_admin_status','track_ingest_allowed');                        -- want 9
SELECT COUNT(*) FROM pg_publication_tables WHERE pubname='supabase_realtime'
 AND tablename IN ('jam_ops','listening_sessions');                     -- want 2
SELECT pg_get_functiondef(oid) LIKE '%sireenyadav%' AS still_bypassed
 FROM pg_proc WHERE proname='is_admin';                                 -- want f
```
