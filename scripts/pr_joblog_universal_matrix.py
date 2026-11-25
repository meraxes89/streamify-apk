#!/usr/bin/env python3
"""Download the failing Graphics job log text from the Actions API."""
import json
import sys
import urllib.request

import os
TOKEN = os.environ["GITHUB_TOKEN"]
REPO = "meraxes89/streamify-apk"

# Job IDs discovered from the failing checks of PR #322 run 45584194161.
JOBS = {
    "graphics": 139101784177,
    "wasm": 139101784030,
}


def api(url, accept="application/vnd.github+json", auth=True):
    headers = {"Accept": accept}
    if auth:
        headers["Authorization"] = f"token {TOKEN}"
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.read(), None
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}: {e.read().decode()[:300]}"


which = sys.argv[1] if len(sys.argv) > 1 else "graphics"
job_id = JOBS[which]

# Try the jobs API (needs Actions read) for logs — it 302-redirects to an
# Azure blob URL; the redirect must be fetched WITHOUT auth (the signed
# blob URL is self-authenticating). urllib follows redirects but re-sends
# headers, so build a fresh opener that drops Authorization on redirect.
class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return urllib.request.Request(newurl)  # no auth header


def fetch_log(url):
    opener = urllib.request.build_opener(NoAuthRedirect())
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with opener.open(req) as resp:
        return resp.read()


try:
    data = fetch_log(f"https://api.github.com/repos/{REPO}/actions/jobs/{job_id}/logs")
except Exception as e:  # noqa: BLE001
    print("job log failed:", e)
    sys.exit(2)

text = data.decode("utf-8", errors="replace")
# Keep the failure-relevant tail: compile errors + test failures
lines = text.splitlines()
keep = []
for i, line in enumerate(lines):
    low = line.lower()
    if any(k in low for k in (
        "error:", "failed", "exception", "e: ", "w: ", "test", "assert",
        "compil", "FAILED", "shadow", "expect", "overload", "unresolved",
    )):
        keep.append(line)
print("\n".join(keep[-120:]))
