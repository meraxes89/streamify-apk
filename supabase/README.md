# `supabase/` — Backend Schema

The database contract consumed by `app/.../data/supabase/`.

| Path | What it is |
|---|---|
| `schema.sql` | Base schema. |
| `migrations/2026_01_stats_overhaul.sql` | Stats tables for `YtStatsTelemetryEngine`. |
| `migrations/2026_11_sec10_rls_hardening.sql` | SEC-10 RLS hardening delta for pre-existing projects. |
| `migrations/2026_11_drift_catchup.sql` | Column catch-up for live projects predating `schema.sql` column additions (incl. the `profiles.top_track` fix). |
| `migrations/2026_11_ws_w_canary_results.sql` | WS-W web canary results table (anon INSERT-only; `front-end/canary/` owns the client + the source-of-truth copy). |
| `sql/jam_lease.sql` / `sql/jam_rpc.sql` | Jam lease + RPC functions for `SupabaseJamClient`. |
| `supabase.md` | Ops notes. |

Client ↔ schema changes must land in the same PR.

## Applying to a live project

Order matters where noted — see `docs/playbooks/BACKEND_DB_SYNC.md` for the
full runbook (apply order, verification queries, app-side configuration):

1. `migrations/2026_11_drift_catchup.sql` (column catch-up; before the stats migration)
2. `schema.sql` (idempotent base contract)
3. `migrations/2026_01_stats_overhaul.sql`
4. `migrations/2026_11_sec10_rls_hardening.sql`
5. `sql/jam_rpc.sql`, then `sql/jam_lease.sql`
6. `migrations/2026_11_ws_w_canary_results.sql` (WS-W web canary table — independent of the app schema; see `front-end/canary/README.md`)

