# Third-party notices

Streamify includes adapted copies of the following third-party work.

## Ponytail

- **Project:** [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail)
- **Version:** 5.3.3
- **Pinned commit:** `ebd8654c2c627f721eb8f9c0d1bd4090a29f9e10` (2026-11-22)
- **License:** MIT (reproduced below)
- **Files adapted:**
  - `.claude/skills/ponytail/SKILL.md`
  - `.claude/skills/ponytail-review/SKILL.md`
  - `.claude/skills/ponytail-audit/SKILL.md`
  - `.claude/skills/ponytail-debt/SKILL.md`
  - `.claude/skills/ponytail-gain/SKILL.md`
  - `.claude/skills/ponytail-help/SKILL.md`
  - `.opencode/commands/ponytail*.md`
  - `.claude/commands/ponytail*.md`

The Streamify copies are adapted for this repository: project precedence and
non-negotiables, the no-auto-build rule, subsystem scoping, the `core`
submodule in the debt scan, the `shortcut:` marker convention, and removal of
plugin-specific persistent mode switching. The upstream benchmark figures in
`ponytail-gain` are a static snapshot and are not Streamify measurements.

See [docs/playbooks/PONYTAIL.md](docs/playbooks/PONYTAIL.md) for the contract
and update procedure.

### MIT License

```
MIT License

Copyright (c) 2026 DietrichGebert

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
