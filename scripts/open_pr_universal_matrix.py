#!/usr/bin/env python3
"""Open the PR for feat/gpu-universal-android-matrix via the GitHub REST API."""
import json
import urllib.request

import os
TOKEN = os.environ["GITHUB_TOKEN"]
REPO = "meraxes89/streamify-apk"

body = open("/home/z/my-project/streamify-apk/scripts/pr_body_universal_matrix.md").read()

payload = {
    "title": "feat(graphics): universal Android GPU & version spectrum matrix (Tracks A-D)",
    "head": "feat/gpu-universal-android-matrix",
    "base": "integration/sovereign-graphics",
    "body": body,
}

req = urllib.request.Request(
    f"https://api.github.com/repos/{REPO}/pulls",
    data=json.dumps(payload).encode(),
    headers={
        "Authorization": f"token {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    },
    method="POST",
)

with urllib.request.urlopen(req) as resp:
    data = json.load(resp)

print("PR #", data.get("number"))
print("URL:", data.get("html_url"))
print("state:", data.get("state"))
print("base:", (data.get("base") or {}).get("ref"))
print("head:", (data.get("head") or {}).get("ref"))
