# AGENTS.md — Streamify agent entry point

This is the cross-agent entry point for AI coding agents working in this
repository. It is intentionally short; the project documents are the authority.

## Read first

1. [CONTRIBUTING.md](CONTRIBUTING.md) — workflow, tests, docs, review rules.
2. [docs/README.md](docs/README.md) — the four document types and the
   decision table for what to update.
3. [docs/SYSTEMS.md](docs/SYSTEMS.md) — the 23 subsystem index (S01–S23).
   Find the `Sxx` page before changing a subsystem.
4. [docs/playbooks/PONYTAIL.md](docs/playbooks/PONYTAIL.md) — the vendored
   minimal-change contract and the six Ponytail skills.
5. [.claude/CLAUDE.md](.claude/CLAUDE.md) — inherited module and API detail.
   Treat it as reference; `CONTRIBUTING.md` and `docs/` win on conflict.

Respond in English.

## Non-negotiables

- **Project rules win over generic agent rules.** If they conflict, follow
  `CONTRIBUTING.md` and `docs/`, and name the conflict in one line.
- **Do not build to verify.** Write the tests and docs the change needs, but
  do not run Gradle, cargo, or a full build unless the user explicitly asks.
  Say "not run" instead of implying verification.
- **Tests and docs are part of the change.** New logic ships with a test;
  behaviour changes update the `Sxx` page and pin in the same PR. Irreversible
  choices need a new ADR; proposals need an RFC. Shipped behaviour gets a
  `CHANGELOG.md` entry.
- **Never cut security, validation at trust boundaries, error handling that
  prevents data loss, accessibility, platform parity, `commonMain`/platform
  boundaries, or audio calibration.**
- **No new dependency without a documented rationale** in the PR description.
- **Do not commit secrets, keystores, local `local.properties`, or local
  `.agents/` workspaces.**
- **Do not touch `core/`.** `core/` is an external/upstream submodule; never
  edit, stage, or commit changes inside `core/` unless the user explicitly
  instructs you to.

## Ponytail

The repository vendors all six Ponytail skills in `.claude/skills/ponytail*`
and runs **full** mode by default. OpenCode and Claude Code discover them
automatically. Other `AGENTS.md` agents read the matching
`.claude/skills/ponytail*/SKILL.md` directly when a Ponytail command is
requested. Do not copy the skills into `.agents/skills/`: OpenCode scans that
path too and the copy would shadow the canonical tree.

Use `/ponytail-review` for a change review, `/ponytail-audit` for one
subsystem, and `/ponytail-debt` for the shortcut ledger. These are read-only
and never run a build. Do not install the upstream plugin alongside the
vendored skills. Run `python3 scripts/check_agent_skills.py` to verify the
set. See [docs/playbooks/PONYTAIL.md](docs/playbooks/PONYTAIL.md) for
precedence, markers, and the update procedure.

## Vendored PowerSync skill

`.claude/skills/powersync/` is a pinned, tracked copy of the official PowerSync
skill for S11 (local SQLite ↔ Supabase sync) work. Both agents discover it
automatically; `/powersync` works in OpenCode and Claude Code. Load its
`REPO_OVERLAY.md` first: it forbids mutating cloud commands, credential handling
through the model, new dependencies or `core/` edits without approval, and
builds to verify. The repo has **not** adopted PowerSync — S11 still uses
`StreamifySyncEngine`. See `VENDOR.md` there for the pin and audit caveats.

## Working agreement

- Keep changes small and single-purpose; one finding per PR.
- Reuse existing helpers, components, and patterns before adding new ones.
- Preserve the surrounding code's style, naming, and layering.
- Do not rewrite unrelated user changes or generated files.
- When a claim depends on something you did not check, say so.
