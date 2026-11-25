#!/usr/bin/env python3
"""Poll PR #322 check runs; print a compact status table."""
import json
import sys
import time
import urllib.request

import os
TOKEN = os.environ["GITHUB_TOKEN"]
REPO = "meraxes89/streamify-apk"
PR = 322


def api(url):
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"token {TOKEN}", "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.load(resp), None
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}: {e.read().decode()[:200]}"


def status_line(r):
    return f"  {r.get('status','?'):11} {r.get('conclusion') or '':8} {r.get('name','?')}"


def main():
    pr, err = api(f"https://api.github.com/repos/{REPO}/pulls/{PR}")
    if err:
        print("PR fetch failed:", err)
        return 2
    sha = pr["head"]["sha"]
    print(f"head sha: {sha}")

    runs, err = api(f"https://api.github.com/repos/{REPO}/commits/{sha}/check-runs?per_page=100")
    if err:
        print("check-runs failed:", err)
        return 2
    total = runs.get("total_count", 0)
    print(f"check runs: {total}")
    for r in runs.get("check_runs", []):
        print(status_line(r))

    # also statuses API for older status-style checks
    sts, err = api(f"https://api.github.com/repos/{REPO}/commits/{sha}/status")
    if not err:
        print(f"combined: {sts.get('state')} ({len(sts.get('statuses', []))} statuses)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
