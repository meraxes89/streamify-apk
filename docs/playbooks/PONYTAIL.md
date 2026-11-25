# Ponytail — minimal-change contract

Streamify vendors all six [Ponytail](https://github.com/DietrichGebert/ponytail)
skills so a fresh clone gets the same minimal-change workflow in every agent.
The skills are guidance, not a CI gate, and they add no build dependency.

- Upstream: `DietrichGebert/ponytail`, v5.3.3, commit
  `ebd8654c2c627f721eb8f9c0d1bd4090a29f9e10` (2026-11-22).
- License: MIT. Attribution and the full text are in
  [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
- Canonical copies: `.claude/skills/ponytail*`. Commands:
  `.opencode/commands/ponytail*.md` and `.claude/commands/ponytail*.md`.
- Default mode: **full**. There is no persistent `lite`/`ultra`/`off` switch
  in the vendored setup; that requires the upstream plugin, which this repo
  does not install.

## Precedence

When instructions disagree, this order wins:

1. The user's current request.
2. [CONTRIBUTING.md](../../CONTRIBUTING.md) and the
   [documentation contract](../README.md), including `Sxx` pages,
   ADRs, and RFCs.
3. Root [AGENTS.md](../../AGENTS.md) and this playbook.
4. The vendored Ponytail skills.

If a Ponytail rule conflicts with a project rule, follow the project rule and
name the conflict in one line. Ponytail never authorises skipping tests, docs,
security, accessibility, platform parity, or audio calibration.

## What full mode means here

Take the smallest change that fully solves the task:

1. Does it need to exist? Skip features nobody asked for.
2. Reuse the helper, component, service, or pattern already in the repo.
3. Use the standard library or platform feature before writing a helper.
4. Use an installed dependency; never add one for a few lines.
5. Prefer one line a reader understands at a glance.
6. Otherwise, write the minimum code that works.

Keep the existing Clean Architecture layers, `commonMain`/platform boundaries,
and module conventions. Do not add wrappers, config, or "for later" code
nobody asked for. Deletion beats addition.

A non-trivial change (a branch, a loop, a parser, money or security logic, a
new script) leaves one small test. Write the test; do not run Gradle or cargo
unless the user explicitly asks. The repo's no-auto-build rule still applies.

End a Ponytail reply with:

```
Skipped: <what you deliberately did not build, or none>
Not checked: <what you did not verify, including tests/builds not run>
Risk: <the one thing the user must know, or none>
```

## Never cut

- Validation at trust boundaries and error handling that prevents data loss.
- Security: auth, Supabase RLS, secrets, JNI/wire parity.
- Accessibility, platform parity, and `commonMain`/platform boundaries.
- Audio calibration and real-device behavior.
- Tests required by [CONTRIBUTING.md](../../CONTRIBUTING.md), docs and `Sxx`
  pins, ADR/RFC decisions, and the `CHANGELOG.md` entry when behaviour ships.
- Anything the user asked for.

Architectural decisions go to an ADR or RFC, not a `shortcut:` comment.

## Skills

| Skill | Use it for |
|---|---|
| `ponytail` | Core full-mode contract; explicit `/ponytail` requests and minimal-change work. |
| `ponytail-review` | Read-only review of uncommitted, staged, branch, PR, or file changes. |
| `ponytail-audit` | Read-only audit of one subsystem (`Sxx`) or folder; whole repo only when asked. |
| `ponytail-debt` | Read-only ledger of `shortcut:` and legacy `ponytail:` markers. |
| `ponytail-gain` | Static upstream benchmark card; never a per-repo savings number. |
| `ponytail-help` | Command and loading reference. |

Start an audit from [SYSTEMS.md](../SYSTEMS.md), not from a random folder.
Cap the report at 20 findings and say what you did not read. Defer Compose
performance to `compose-performance-audit`, test patterns to `android-testing`,
and documentation decisions to `documentation-and-adrs`.

## Commands

| Command | Target |
|---|---|
| `/ponytail` | Apply the full-mode contract to the current task. |
| `/ponytail-review [target]` | Uncommitted changes by default; or staged, branch, PR, files. |
| `/ponytail-audit [path\|Sxx]` | One subsystem or folder; whole repo only when asked. |
| `/ponytail-debt [marker]` | Main repo plus the `core` submodule when initialized. |
| `/ponytail-gain` | Static benchmark card. |
| `/ponytail-help` | Reference card. |

The review, audit, and debt commands are read-only: they change no code and
run no Gradle or cargo task.

## Shortcut markers

A deliberate simplification with a known ceiling gets one code comment:

```kotlin
// shortcut: <the limit>, <when to upgrade>
```

- Name the ceiling and the upgrade trigger. A marker without a trigger is
  tagged `no-trigger` by `/ponytail-debt` and is treated as rot.
- The main tree already contains six legacy `ponytail:` markers; the `core`
  submodule adds more. They are valid history; do not rewrite them just to
  change the prefix.
- Do not use a marker for an architectural decision, a security gap, a missing
  test, or a docs omission. Those need an ADR/RFC or the actual work.
- `/ponytail-debt` scans both prefixes. It also scans `core` as a separate Git
  tree, because the main `git grep` does not cross the submodule boundary.

## How each agent loads it

- **OpenCode** auto-discovers `.claude/skills/` and `.opencode/commands/`.
  After a clone, start a new session or run `/reload`.
- **Claude Code** auto-discovers `.claude/skills/` and `.claude/commands/`.
- **Other `AGENTS.md` agents** read the root [AGENTS.md](../../AGENTS.md), then
  read the matching `.claude/skills/ponytail*/SKILL.md` directly. Do not copy
  the skills into `.agents/skills/`: OpenCode scans that path too and gives it
  precedence, so a stale copy would shadow the canonical tree.

No global install, `opencode.json`, environment variable, or mirror is
required. Run `python3 scripts/check_agent_skills.py` to verify the six skills
and both command copies; it is not a CI gate yet.

## Optional upstream plugin

The upstream OpenCode plugin adds persistent `lite`/`ultra`/`off` levels and a
global mode file. Do not install it alongside these vendored skills: it
duplicates skill IDs and adds a second ruleset. If an engineer wants the
levels, they must remove or disable the vendored copies for that workspace and
keep the repository default at full.

## Updating the vendored copy

1. Pick a new upstream tag or commit and read its changelog.
2. Diff `skills/ponytail*` and `.opencode/command/ponytail*` against the pinned
   commit.
3. Re-apply the Streamify overrides. Every adapted file names them with a
   `Streamify override` or `Streamify note` block.
4. Update the commit, version, and date here and in
   [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
5. Run `python3 scripts/check_agent_skills.py` and
   `python3 scripts/verify_docs.py`.
6. Open one docs/chore PR. Do not mix the update with behaviour changes.

The benchmark card in `ponytail-gain` is a static snapshot. Do not compute or
report per-repo savings from it; the unbuilt version was never written, so
there is no real baseline.
