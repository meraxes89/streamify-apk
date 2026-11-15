# F1 — Vendoring strategy for the feishin fork

Status: ACCEPTED (P0, 2026-11-15)
Owner: Engineer A (P0), then the release engineer who performs tag syncs.

## Decision

Vendor the fork into the monorepo at `front-end/feishin/` as **single-commit
imports** (git plumbing: `git read-tree --prefix`), carrying
`git-subtree-dir` / `git-subtree-split` metadata in the commit message for
provenance and sync bookkeeping. The fork (`meraxes89/streamify-feishin`)
remains a real, pushable repository and is the **only** place where upstream
tags are merged.

### Why not `git subtree add --squash` (tried in P0, adjusted)

The initial P0 import used `git subtree add --squash`, which creates a
**parentless synthetic root commit** ("Squashed 'front-end/feishin/' content
from …"). This repo's blocking `gitleaks-action` (Tier 1 fast-fail) builds
its scan range as `<first-PR-commit>^..<head>`; `firstCommit^` does not exist
for a root commit, git fatals, and the security gate fails (observed on PR
#181's first push — "no leaks found", yet exit 1). The single-commit form
keeps every commit parented, so the range resolves **and** the vendored diff
actually gets secret-scanned, which the subtree merge form would have
silently skipped (`--no-merges --first-parent` never visits the second-parent
squash chain).

## Mechanics

- Import (P0, done):
  ```
  git fetch <fork-remote> main
  git read-tree --prefix=front-end/feishin/ -u <fork-remote>/main^{tree}
  git commit -F msg   # msg carries: git-subtree-dir: front-end/feishin
                      #             git-subtree-split: <fork head sha>
  ```
- Upstream tag sync (fork repo first!):
  1. In `streamify-feishin`: `git fetch upstream --tags && git merge v<tag>`
     (merge, never rebase), resolve conflicts using the fork's `PATCHES.md`,
     keep fork CI green.
  2. In `streamify-apk` (same shape as the import — one regular commit per
     sync):
     ```
     git fetch <fork-remote> main
     git rm -rq front-end/feishin && git read-tree --prefix=front-end/feishin/ -u <fork-remote>/main^{tree}
     git commit  # update git-subtree-split to the new fork head
     ```
- The vendored tree carries the fork's own `pnpm-lock.yaml`,
  `pnpm-workspace.yaml` and `.npmrc` (`legacy-peer-deps=true`). The monorepo
  has **no root package.json/workspace**, so nothing swallows it; installs
  happen inside `front-end/feishin/` with the fork's pinned `pnpm@11.5.2`
  (corepack) and stay isolated from every other toolchain in the repo.

## CI implications

- The monorepo `web-build.yml` triggers on `front-end/**` PRs; the vendored
  tree does not touch `front-end/web` or `front-end/crates`, so its gates
  (wasm32 compile, wasm-bindgen ping, web bundle) are unaffected. Confirmed:
  they build the same inputs as before.
- `web-deploy.yml` (Pages) is **not** triggered by the feishin line — its
  branch allowlist intentionally excludes feishin branches; the hand-rolled
  UI keeps the deploy lane until the F2 cutover decision.
- The fork's own CI (`Test` = upstream lint + commitlint; `Web CI` = our
  install → lint → `build:web`) is the gate for changes inside
  `front-end/feishin/**` content. Both are green on the P0 head
  (`d12f99f6`).
- A monorepo-side lint/typecheck gate for the vendored tree (so we catch
  breakage on monorepo PRs without opening fork PRs) is intentionally left to
  P1, where the first monorepo-level feishin CI job becomes worth its
  minutes.

## Costs & trade-offs (accepted)

- +25 MB, 1,418 files in the monorepo tree; every sync adds one large
  commit. Acceptable for a monorepo that already vendors an Android app.
- `git log --follow` across the import boundary is weaker than a submodule's
  real history. Accepted: the fork repo holds the history; PATCHES.md holds
  the semantic map.
- Renames/moves inside the fork surface as delete+add in monorepo diffs.
  Accepted.

## Local build notes (sandbox-proven)

- `ELECTRON_SKIP_BINARY_DOWNLOAD=1 pnpm install` — skips the Electron binary
  download in a web-only pipeline; postinstall still completes
  (`install-app-deps` against the pinned Electron for native rebuilds works).
- `pnpm run build:web` = `vite build --config web.vite.config.ts` → `out/web`
  (static, hash-router base `./`, PWA service worker). On a 4 GB machine the
  default Node heap OOMs during chunk rendering; upstream's electron build
  already uses `NODE_OPTIONS=--max-old-space-size=4096`, and on constrained
  boxes `--sourcemap false --minify false` CLI flags fit it in memory
  (CI runners handle the full build).
- Runtime config comes from `settings.js` (see the fork's
  `settings.js.template` + `Dockerfile`/`ng.conf.template`); in the P0
  sandbox verification it was generated with `sed` exactly like the nginx
  envsubst entrypoint does.
