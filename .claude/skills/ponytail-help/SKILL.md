---
name: ponytail-help
description: >
  Quick reference for the vendored Ponytail skills and commands in Streamify.
  One-shot display. Use for /ponytail-help, "ponytail help", "how do I use
  ponytail".
license: MIT
---

# Ponytail Help

> **Streamify note.** This repository vendors the six upstream Ponytail skills
> and runs **full** mode by default through `AGENTS.md`. Persistent
> `lite`/`ultra`/`off` levels come only from the upstream plugin, which this
> repository does not install. Project rules in `CONTRIBUTING.md` and `docs/`
> always win.

Display this reference card when invoked. One-shot: do NOT change mode, write
flag files, or persist anything.

## Default mode

**Full.** The contract is always on through `AGENTS.md` and
`docs/playbooks/PONYTAIL.md`. There is no per-session off switch in the
vendored setup; the upstream plugin adds one.

## Skills

| Skill | Trigger | What it does |
|-------|---------|--------------|
| **ponytail** | `/ponytail` | Lazy senior dev mode: least new code, clear replies that name skipped work and risks. |
| **ponytail-review** | `/ponytail-review` | Quality review of a diff: bugs, security, load, missing tests, speed, what to cut. Read-only. |
| **ponytail-audit** | `/ponytail-audit` | The same quality review for a subsystem or the whole repo, ranked. Read-only. |
| **ponytail-debt** | `/ponytail-debt` | Harvest `shortcut:` and legacy `ponytail:` comments into a debt ledger. Read-only. |
| **ponytail-gain** | `/ponytail-gain` | Static upstream benchmark scoreboard; never a per-repo number. |
| **ponytail-help** | `/ponytail-help` | This card. |

## Commands

| Command | Use |
|---------|-----|
| `/ponytail` | Apply the full-mode contract to the current task. |
| `/ponytail-review [target]` | Review uncommitted changes, staged changes, a branch, a PR, or files. |
| `/ponytail-audit [path\|Sxx]` | Audit one subsystem or folder; the whole repo only when asked. |
| `/ponytail-debt [marker]` | Produce the ledger; scans the main repo and the `core` submodule. |
| `/ponytail-gain` | Display the static benchmark card. |
| `/ponytail-help` | This card. |

Commands live in `.opencode/commands/` for OpenCode and `.claude/commands/` for
Claude Code. The skills live in `.claude/skills/ponytail*`.

## How it loads

- **OpenCode** auto-discovers `.claude/skills/` and `.opencode/commands/`.
- **Claude Code** auto-discovers `.claude/skills/` and `.claude/commands/`.
- **Other `AGENTS.md` agents** read the root `AGENTS.md`, then the matching
  `.claude/skills/ponytail*/SKILL.md` directly. Do not copy the skills into
  `.agents/skills/`: OpenCode scans that path too and the copy would shadow
  the canonical tree.

No global install, `opencode.json`, environment variable, or mirror is
required. Run `python3 scripts/check_agent_skills.py` to verify the set.

## Optional upstream plugin

If you want the upstream plugin's persistent `lite`/`ultra`/`off` levels, add
`"plugins": ["@dietrichgebert/ponytail"]` to a local `opencode.json` and do not
combine it with the vendored skills. The repository default stays full; the
project rules still win.

## Update

The vendored copies are pinned to upstream commit
`ebd8654c2c627f721eb8f9c0d1bd4090a29f9e10` (v5.3.3). To update, diff the
upstream `skills/ponytail*` files, re-apply the Streamify overrides, and update
`THIRD_PARTY_NOTICES.md`. See `docs/playbooks/PONYTAIL.md`.

## More

Upstream docs and examples: https://github.com/DietrichGebert/ponytail
