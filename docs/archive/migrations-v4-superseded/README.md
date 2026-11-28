# Superseded v4 Sync Migrations

These five `2026_12_*` migration files were never applied to the live Supabase
project (`dbgtvzgwixerhnwvhtil`, which stopped at `2026_11_p8_v4_server_contract_and_lww_hardening`).

They have been archived out of `supabase/migrations/` because the Server Contract v4
single-changelog trigger architecture is superseded by the **Sync v5 6-Plane Architecture**
([ADR-011](../../adr/ADR-011-sync-v5-six-plane-architecture.md)):

- `2026_12_p9_rls_and_hlc_hardening.sql`
- `2026_12_p10_hlc_profiles_history_search.sql`
- `2026_12_p11_s11_p0_changelog_playlist_track_delete.sql`
- `2026_12_p12_s11_p1_server_hardening.sql`
- `2026_12_w1b_item_favorites.sql`

All new migrations after `2026_11_p8` use 14-digit UTC timestamp prefixes (`YYYYMMDDHHMMSS_*.sql`).
