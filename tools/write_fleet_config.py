#!/usr/bin/env python3
"""tools/write_fleet_config.py — turn canary probe output into fleet-config.json.

Reads probe-clients.sh tabular output (single pass, both content classes) and
writes fleet-config.json for the surviving fleet-ladder clients.

Survivorship policy (since 2026-10, bot-wall aware):
  * A survivor = ladder client that resolves STANDARD content (dQw4w9WgXcQ)
    with audio formats (OK + itag 251/140 present). Standard-content health
    is the gating signal — it is IP-reputation-independent on CI runners.
  * Licensed/official content (4NRXx6U8ABQ) is recorded per-client under
    `licensedProbe` but is NOT part of survivorship: YouTube bot-walls
    licensed content for entire datacenter IP ranges (LOGIN_REQUIRED
    "Sign in to confirm you're not a bot") regardless of client identity,
    so a licensed miss on CI says nothing about the registry. Residential
    users are unaffected; a real client-side licensed regression surfaces
    first as standard-content rot, which gates.
  * Preserves all other keys already present in fleet-config.json
    (searchClients, signatureTimestamp, ...) — the rotation only touches
    what it owns.

Exits non-zero when no ladder client resolves standard content (true
registry rot — CI must surface it, not ship an empty fleet).
"""
import datetime
import json
import sys

STANDARD_VIDEO = "dQw4w9WgXcQ"
LICENSED_VIDEO = "4NRXx6U8ABQ"

# label in probe table -> full Innertube client spec (must mirror baked defaults)
KNOWN_SPECS = {
    "ANDROID": {
        "clientName": "ANDROID",
        "clientVersion": "21.26.364",
        "clientNumber": "3",
        "userAgent": "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
        "osName": "Android",
        "osVersion": "11",
    },
    "IOS": {
        "clientName": "IOS",
        "clientVersion": "21.26.4",
        "clientNumber": "5",
        "userAgent": "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        "deviceMake": "Apple",
        "deviceModel": "iPhone16,2",
        "osName": "iPhone",
        "osVersion": "18.3.2.22D82",
    },
    "VR_1.60.19": {
        "clientName": "ANDROID_VR",
        "clientVersion": "1.60.19",
        "clientNumber": "28",
        "userAgent": "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
        "deviceMake": "Oculus",
        "deviceModel": "Quest 3",
        "osName": "Android",
        "osVersion": "12",
    },
}

# Fleet ladder order: the app's fallback preference.
ORDER = ["ANDROID", "VR_1.60.19", "IOS"]


def parse_probe(probe_path):
    """Return {label: {video: (status, audio_count, reason)}}."""
    rows = {}
    with open(probe_path) as f:
        for line in f:
            parts = line.split()
            # CLIENT VIDEO STATUS AUDIO FMTS CIPHERED [REASON...]
            if len(parts) < 6 or parts[0] == "CLIENT":
                continue
            label, vid, status, audio = parts[0], parts[1], parts[2], parts[3]
            reason = " ".join(parts[6:]) if len(parts) > 6 else "-"
            try:
                audio_n = int(audio.split("/")[0])
            except (ValueError, IndexError):
                audio_n = 0
            rows.setdefault(label, {})[vid] = (status, audio_n, reason)
    return rows


def classify_licensed(entry):
    """Map a licensed probe row to a stable verdict token."""
    status, audio_n, reason = entry
    if status == "OK" and audio_n > 0:
        return "ok"
    if status == "LOGIN_REQUIRED":
        return "bot-gated" if "bot" in reason.lower() else "login-required"
    return "degraded:{}".format(status.lower())


def main():
    probe_path, out_path = sys.argv[1], sys.argv[2]
    rows = parse_probe(probe_path)

    # Survivors: ladder clients OK-with-audio on STANDARD content.
    winners = [
        label
        for label in ORDER
        if rows.get(label, {}).get(STANDARD_VIDEO, ("", 0, ""))[0] == "OK"
        and rows[label][STANDARD_VIDEO][1] > 0
    ]
    if not winners:
        print("::error::no ladder client resolves standard content — refusing to write an empty fleet (true registry rot)")
        sys.exit(1)

    # Licensed verdict per winning client (informational, non-gating).
    licensed = {label: classify_licensed(rows[label][LICENSED_VIDEO]) for label in winners if LICENSED_VIDEO in rows.get(label, {})}
    if licensed and all(v == "ok" for v in licensed.values()):
        licensed_verdict = "ok"
    elif licensed and all(v == "bot-gated" for v in licensed.values()):
        licensed_verdict = "bot-gated (datacenter-IP wall, environmental)"
    elif licensed:
        licensed_verdict = "degraded"
    else:
        licensed_verdict = "unknown"

    # Rotation owns only its keys; preserve everything else (searchClients, ...).
    try:
        with open(out_path) as f:
            config = json.load(f)
    except (OSError, ValueError):
        config = {}
    config.update(
        {
            "version": int(datetime.datetime.now().strftime("%Y%m%d")),
            "updated": datetime.date.today().isoformat(),
            "note": (
                "Auto-maintained by resolver-canary CI. Merged over baked defaults by FleetConfig. "
                "Survivorship = standard-content resolution (bot-wall aware since 2026-10); "
                "licensedProbe is informational (CI datacenter IPs are bot-walled by YouTube)."
            ),
            "audioClients": [KNOWN_SPECS[w] for w in winners],
            "licensedProbe": {"verdict": licensed_verdict, "perClient": licensed},
        }
    )
    with open(out_path, "w") as f:
        json.dump(config, f, indent=2)
        f.write("\n")
    print(
        "fleet-config.json written with winners: {} (licensed: {} — {})".format(
            winners, licensed_verdict, licensed
        )
    )
    if licensed_verdict != "ok":
        print(
            "::warning::licensed content probe degraded ({}) — recorded, not gating "
            "(datacenter-IP bot wall; see workflow comment)".format(licensed_verdict)
        )


if __name__ == "__main__":
    main()
