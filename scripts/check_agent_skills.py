#!/usr/bin/env python3
"""check_agent_skills.py — verify the vendored Ponytail skill set.

Canonical copies live in .claude/skills/ponytail* and are read directly by
OpenCode and Claude Code. This script checks that the six skills and both
command copies exist and that each command names its skill.

It deliberately does not mirror the skills into .agents/skills/: OpenCode
scans that path too, and a copy there would shadow the canonical tree.

Usage:
    python3 scripts/check_agent_skills.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / ".claude" / "skills"
COMMAND_DIRS = (
    REPO / ".opencode" / "commands",
    REPO / ".claude" / "commands",
)
SKILLS = (
    "ponytail",
    "ponytail-review",
    "ponytail-audit",
    "ponytail-debt",
    "ponytail-gain",
    "ponytail-help",
)


def check() -> list[str]:
    problems: list[str] = []

    for name in SKILLS:
        skill = SOURCE / name / "SKILL.md"
        if not skill.is_file():
            problems.append(f"missing skill: {skill.relative_to(REPO)}")
            continue
        text = skill.read_text(encoding="utf-8", errors="replace")
        if not re.search(rf"^name:\s*{re.escape(name)}\s*$", text, re.M):
            problems.append(
                f"skill name mismatch: {skill.relative_to(REPO)} "
                f"does not declare `name: {name}`"
            )

    for directory in COMMAND_DIRS:
        for name in SKILLS:
            command = directory / f"{name}.md"
            if not command.is_file():
                problems.append(f"missing command: {command.relative_to(REPO)}")
                continue
            text = command.read_text(encoding="utf-8", errors="replace")
            if not text.strip():
                problems.append(f"empty command: {command.relative_to(REPO)}")
            elif f"`{name}`" not in text:
                problems.append(
                    f"command does not name its skill: "
                    f"{command.relative_to(REPO)}"
                )

    return problems


def main() -> int:
    problems = check()
    if problems:
        for problem in problems:
            print(f"  ERROR  {problem}")
        print(f"check_agent_skills: FAIL ({len(problems)} problems)")
        return 1

    print(f"check_agent_skills: PASS ({len(SKILLS)} skills, "
          f"{len(SKILLS) * len(COMMAND_DIRS)} commands)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
