-- WS-W web canary (2026-11-12) — repo-contract registration of
-- front-end/canary/migrations/001_canary_results.sql (the reviewed W0.1b
-- artifact, merged in PR #159). Same table, same anon-INSERT-only policy —
-- the two files must not drift in shape; this copy adds re-run guards so the
-- live-DB apply order in README.md stays fire-and-forget.
--
-- Registered here because the live project is drift-audited against supabase/
-- (docs/playbooks/BACKEND_DB_SYNC.md): canary_results is about to exist on the
-- live project dbgtvzgwixerhnwvhtil, so the contract must know it even though
-- the flagship app never reads it (WS-W PO reads it with the service role).

create table if not exists public.canary_results (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  kind text not null check (kind in ('matrix','rcondition')),
  client text not null,
  video text,
  endpoint text,
  status integer,
  ok boolean,
  audio_fmts integer,
  fmt_251 boolean,
  total_fmts integer,
  ciphered integer,
  reason text,
  latency_ms integer,
  notes text,
  r_id text, r_name text, r_result text, r_detail text,
  user_agent text, timezone text, page_url text
);

create index if not exists canary_results_created_at_idx
  on public.canary_results (created_at);

alter table public.canary_results enable row level security;

drop policy if exists "anon can insert canary_results" on public.canary_results;
create policy "anon can insert canary_results"
  on public.canary_results for insert to anon with check (true);

-- no select policy for anon by design (PO's call to add authenticated read later)
