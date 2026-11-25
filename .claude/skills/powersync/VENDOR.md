# Vendored upstream skill — provenance

- **Skill:** `powersync` (upstream `powersync-ja/agent-skills`)
- **Pinned commit:** `dbea3871618aa8fbccd75625561bb7ee7de08f5b`
- **Declared skill version:** `1.6.6`
- **Licence:** MIT — copy at `LICENSE` (Copyright (c) 2025 PowerSync)
- **Vendored:** 2026-11-26 into `.claude/skills/powersync/`
- **skills.sh page:** https://skills.sh/powersync-ja/agent-skills/powersync

## Why vendored instead of installed

`npx skills add -g` installs into each developer's home and does not travel with
a clone. This repo's convention is tracked skills under `.claude/skills/`, which
OpenCode and Claude Code auto-discover (see `docs/playbooks/PONYTAIL.md`). A
fresh clone therefore gets the same skill, version, and guardrails.

## Local deviations from upstream

- Upstream `AGENTS.md` is renamed to `POWERSYNC_RULES.md` so it cannot be
  confused with the repository root `AGENTS.md`. All internal pointers were
  updated.
- Upstream `CLAUDE.md` (a one-line pointer) was removed; the repository root
  `.claude/CLAUDE.md` is the authority for this project.
- `REPO_OVERLAY.md` was added with repo-specific hard stops (no mutating CLI
  commands without approval, no credentials through the model, no dependency or
  `core/` changes without a decision record, no build to verify).
- `SKILL.md`'s "First Use: Offer to Trim" section was replaced with a vendored
  note. The full upstream reference set was kept: the upstream
  `scripts/trim.mjs` detected no `com.powersync` dependency in this repo and
  declined to trim. Re-run it with operator approval only after adoption.
- `scripts/` (upstream trim script) is kept for that future re-trim; it is not
  used by this repo's build or CI.
- A thin command wrapper is tracked in both `.claude/commands/powersync.md` and
  `.opencode/commands/powersync.md`.

## Re-vendor procedure (developers)

1. `git clone https://github.com/powersync-ja/agent-skills.git` and
   `git checkout dbea3871618aa8fbccd75625561bb7ee7de08f5b`.
2. Copy `skills/powersync/` over `.claude/skills/powersync/`.
3. Re-apply the local deviations above (rename `AGENTS.md`, drop `CLAUDE.md`,
   restore `VENDOR.md`, `REPO_OVERLAY.md`, and the four edited pointer lines in
   `SKILL.md`, `POWERSYNC_RULES.md`, `references/powersync-cli.md`).
4. Run `python3 scripts/check_agent_skills.py`.

Do not run `npx skills update` against this tracked copy; it would silently
replace the pinned version and drop the overlay.

## Security audit caveat (skills.sh, as of 2026-11-26)

The upstream skill is Gen Agent Trust Hub: Pass, Socket: WARN, Snyk: **FAIL
(HIGH)**. Snyk's findings: insecure credential handling in the onboarding path
(credentials pass through model context into `.env`), an unpinned community
Helm chart URL, and CLI commands that mutate/destroy cloud services
(`destroy`, `stop`, `docker stop --remove-volumes`). `REPO_OVERLAY.md` hard-stops
each of these for this repo. Re-check
https://skills.sh/powersync-ja/agent-skills/powersync/security before updating
the pin.
