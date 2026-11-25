#!/usr/bin/env python3
"""Fetch failing check-run logs for PR #322's head sha."""
import json
import sys
import urllib.request

import os
TOKEN = os.environ["GITHUB_TOKEN"]
REPO = "meraxes89/streamify-apk"
SHA = "1682c9cf62d288ada2fff6682d6b0af38d9d30ee"
TARGETS = sys.argv[1:] or ["Graphics", "Wasm"]


def api(url, raw=False):
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"token {TOKEN}", "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return (resp.read() if raw else json.load(resp)), None
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}: {e.read().decode()[:300]}"


runs, err = api(f"https://api.github.com/repos/{REPO}/commits/{SHA}/check-runs?per_page=100")
if err:
    print("check-runs failed:", err)
    sys.exit(2)

for r in runs.get("check_runs", []):
    name = r.get("name", "")
    if r.get("conclusion") == "failure" and any(t.lower() in name.lower() for t in TARGETS):
        print("=" * 72)
        print("FAILED:", name, "|", r.get("details_url"))
        details = r.get("details_url")
        if details:
            # check-run details URL is an HTML page; fetch the annotation +
            # attempt log via the check-run API itself
            cid = r.get("id")
            ann, aerr = api(f"https://api.github.com/repos/{REPO}/check-runs/{cid}/annotations")
            if not aerr:
                for a in ann or []:
                    print(f"  annotation: {a.get('message')} ({a.get('annotation_level')})")
            logs, lerr = api(f"https://api.github.com/repos/{REPO}/check-runs/{cid}")
            # raw log needsChecks UI; try the raw endpoint:
        print()
