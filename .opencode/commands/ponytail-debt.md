---
description: Harvest shortcut: and ponytail: comments into a debt ledger
agent: plan
---

Load the `ponytail-debt` skill and produce the ledger for $ARGUMENTS.
Scan both `shortcut:` and legacy `ponytail:` markers in the main repository
and, if initialized, the `core` submodule. Do not rewrite comments. Report
every row and tag markers with no upgrade trigger as `no-trigger`.
