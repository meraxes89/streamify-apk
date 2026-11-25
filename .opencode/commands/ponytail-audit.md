---
description: Audit a Streamify subsystem or folder with the Ponytail method
agent: plan
---

Load the `ponytail-audit` skill and audit $ARGUMENTS.
If no target is named, choose one subsystem or folder from `docs/SYSTEMS.md`;
do not audit the whole repo unless explicitly asked. Read the `Sxx` page,
sources, tests, CI config, and docs contract. Do not edit files. Do not run
Gradle or cargo. Cap the report at 20 findings.
