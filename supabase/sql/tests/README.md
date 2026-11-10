# supabase/sql/tests — the SEC-10 RLS hardening suite

Regression suite proving the Horizon-0 Supabase hardening (SEC-10) and
its SEC-09 client pairing. **Every assertion fails if a pre-SEC-10
permissive policy is restored** — anonymous track ingest, `OR TRUE`
session/history policies, the hardcoded-email admin grant, or a
client-writable `profiles.is_admin`.

## Files

| File | Role |
|---|---|
| `00_test_shim.sql` | Supabase auth-runtime emulation (roles, `auth` schema, `auth.users`, `auth.uid()/jwt()/role()`) — applied **before** `supabase/schema.sql`, test-only |
| `01_grants.sql` | Supabase-default privilege posture for the API roles — applied **after** the schema |
| `rls_test.sql` | The assertions — applied last |

## Run locally (throwaway DB only!)

```bash
docker run -d --name sec10-test -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=streamify_test -p 5432:5432 pgvector/pgvector:pg16
PG="psql -h 127.0.0.1 -U postgres -d streamify_test -v ON_ERROR_STOP=1"
$PG -f supabase/sql/tests/00_test_shim.sql
$PG -f supabase/schema.sql
$PG -f supabase/sql/tests/01_grants.sql
$PG -f supabase/sql/tests/rls_test.sql
# teardown: docker rm -f sec10-test
```

## CI

The `ci.yml` hub runs the same four files in the `supabase-rls` job (a
`pgvector/pgvector:pg16` service container) whenever `supabase/**` or
`.github/workflows/**` changes, and `ci-gate` folds the verdict.

## What the suite pins

1. anonymous `tracks` INSERT/UPDATE is rejected (was `WITH CHECK (TRUE)`)
2. authenticated ingest works, capped at 60 tracks/user/hour
   (`track_ingest_allowed()`)
3. strangers cannot modify `listening_sessions` (was `... OR TRUE`);
   participants still can
4. anonymous session seeding is rejected (was `WITH CHECK (TRUE)`)
5. cross-user `user_listening_history` inserts are rejected (was `OR TRUE`)
6. anonymous `track_comments` inserts are rejected
7. `is_admin()` / `get_my_admin_status()` honor **only** the flag — the
   hardcoded maintainer email and "Sireen fan" display names grant nothing
8. no authenticated user can write `profiles.is_admin` (guard trigger)
9. admin RPCs deny non-admins (including the legacy-email account) and
   work for the flagged admin
10. sign-up (`handle_new_user`) never mints an admin
11. the admin's full track access survives (moderation intact)

Live databases: apply `supabase/migrations/2026_11_sec10_rls_hardening.sql`
(the reviewed delta — the "db dump diff" the SEC-10 gate asks for).
