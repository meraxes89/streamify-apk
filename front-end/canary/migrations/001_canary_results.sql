-- WS-W W0.1b — PO reviews + applies. anon INSERT only; no anon SELECT (service role reads).
-- Table behind front-end/canary: one row per matrix probe line (kind='matrix',
-- 10 clients x 2 videos per run) plus one row per R-C condition result
-- (kind='rcondition', R1-R4). Populated by the residential canary page via the
-- Supabase REST insert (anon key as apikey/Bearer).
--
-- Apply note: `create table if not exists` makes the table idempotent, but the index
-- and policy are not IF NOT EXISTS-able in plain SQL — drop them first if you ever
-- re-run this migration (canary_results_created_at_idx / "anon can insert canary_results").

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

create index on public.canary_results (created_at);

alter table public.canary_results enable row level security;

create policy "anon can insert canary_results" on public.canary_results for insert to anon with check (true);

-- no select policy for anon by design (PO's call to add authenticated read later)
