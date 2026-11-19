# `data/telemetry/` — Listening Stats

Cross-device listening statistics — the engine behind the Wrapped-style
analytics screens.

## Files

| File | What it is |
|---|---|
| `YtStatsTelemetryEngine.kt` | Single source of truth for play counts, `WrappedStats` aggregation, and cloud merge (`SupabaseStatsClient`). |

## Notes

`YtStatsTelemetryEngine` merges locally observed plays with the
`stats_overhaul` Supabase tables (see `supabase/migrations/`) using a
last-write-wins strategy. UI consumers read `WrappedStats` snapshots;
never write telemetry from the UI directly.
