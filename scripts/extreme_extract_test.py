#!/usr/bin/env python3
"""EXTREME unauthenticated YouTube extraction gauntlet — find ANY working path."""
import json, re, urllib.request, gzip, io, sys

VID = "jNQXAC9IVRw"
UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
UA_ANDROID = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"

def fetch(url, headers=None, timeout=15, data=None):
    h = {"User-Agent": UA_DESKTOP, "Accept-Language": "en-US,en;q=0.9"}
    if headers: h.update(headers)
    req = urllib.request.Request(url, headers=h, data=data)
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        return r.status, dict(r.headers), raw
    except Exception as e:
        return -1, {}, str(e).encode()

def probe_stream(url):
    """Range-GET like ExoPlayer would. Returns status code."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA_DESKTOP, "Range": "bytes=0-999"})
        r = urllib.request.urlopen(req, timeout=12)
        return f"{r.status} ct={r.headers.get('Content-Type','?')[:30]}"
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}"
    except Exception as e:
        return f"ERR {str(e)[:40]}"

results = []

# ── A. WATCH PAGE SCRAPE (how real browsers do it) ──
print("═" * 60)
for label, hdrs in [
    ("A1 watchpage: bare desktop UA", {}),
    ("A2 watchpage: +CONSENT/SOCS cookies", {"Cookie": "CONSENT=YES+cb.20210328-17-p0.en+FX+678; SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjMwODI5LjA3X3AxGgJlbiACGgYIgLC_pwY"}),
    ("A3 watchpage: android UA", {"User-Agent": UA_ANDROID}),
]:
    st, hdrs_r, body = fetch(f"https://www.youtube.com/watch?v={VID}&hl=en", hdrs)
    if st != 200:
        results.append((label, f"page HTTP {st}")); print(label, "->", st); continue
    html = body.decode("utf-8", "ignore")
    m = re.search(r"ytInitialPlayerResponse\s*=\s*({.+?});(?:\s*var\s|\s*</script>)", html, re.S)
    if not m:
        # newer embedding
        m = re.search(r'ytInitialPlayerResponse\s*=\s*(\{.+?\})\s*;', html, re.S)
    pr = None
    if m:
        try: pr = json.loads(m.group(1))
        except Exception:
            try: pr = json.loads(m.group(1).encode().decode('unicode_escape'))
            except Exception: pr = None
    if not pr:
        results.append((label, "no ytInitialPlayerResponse parsed")); print(label, "-> no PR"); continue
    ps   = pr.get("playabilityStatus", {}).get("status", "?")
    reason = pr.get("playabilityStatus", {}).get("reason", "")[:50]
    sd   = pr.get("streamingData", {})
    fmts = sd.get("adaptiveFormats", []) or []
    with_url = [f for f in fmts if f.get("url")]
    ciphered = len(fmts) - len(with_url)
    audio = [f for f in with_url if str(f.get("mimeType","")).startswith("audio/")]
    info = f"ps={ps} {reason!r} fmts={len(fmts)} direct={len(with_url)} cipher={ciphered} audio_direct={len(audio)}"
    print(label, "->", info)
    results.append((label, info))
    if audio:
        u = audio[0]["url"]
        # url from page is already escaped
        u = u.replace("\\u0026", "&")
        res = probe_stream(u)
        print("      ↳ stream probe:", res)
        results.append((label + " STREAM", res))

# extract visitorData for later tests
visitor = None
st, _, body = fetch(f"https://www.youtube.com/watch?v={VID}")
if st == 200:
    mm = re.search(rb'"visitorData":"([^"]+)"', body)
    if mm:
        visitor = mm.group(1).decode()
print("\nvisitorData:", ("found len=" + str(len(visitor))) if visitor else "NOT FOUND")

# ── B. youtubei WEB + visitorId header ──
def innertube(client, version, extra_headers=None, extra_ctx=None, url="https://www.youtube.com/youtubei/v1/player"):
    ctx = {"client": {"clientName": client, "clientVersion": version, "hl": "en", "gl": "US"}}
    if extra_ctx: ctx["client"].update(extra_ctx)
    body = {"context": {"ctx_key_placeholder": True}, "videoId": VID, "contentCheckOk": True, "racyCheckOk": True}
    payload = {"context": {"client": ctx["client"]}, "videoId": VID, "contentCheckOk": True, "racyCheckOk": True}
    if extra_ctx and "thirdParty" in extra_ctx:
        payload["context"]["thirdParty"] = extra_ctx["thirdParty"]
    h = {"Content-Type": "application/json"}
    if extra_headers: h.update(extra_headers)
    data = json.dumps(payload).encode()
    st, _, resp = fetch(url, h, data=data)
    if st != 200: return f"HTTP {st}", {}
    try: return "200", json.loads(resp)
    except Exception as e: return "parse err", {}

tests = []
if visitor:
    vh = {"X-Goog-Visitor-Id": visitor}
    tests += [
        ("B1 WEB + visitorId", "WEB", "2.20250312.04.00", {**vh, "Origin": "https://www.youtube.com", "Referer": f"https://www.youtube.com/watch?v={VID}"}, None),
    ]
tests += [
    ("B2 TVEMBED + visitor", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0",
     {"User-Agent": UA_DESKTOP, **({"X-Goog-Visitor-Id": visitor} if visitor else {})},
     {"thirdParty": {"embedUrl": "https://www.youtube.com/"}}),
]

for label, cname, cver, hdrs, xtra in tests:
    st, j = innertube(cname, cver, hdrs, xtra)
    if isinstance(j, dict) and j:
        ps = j.get("playabilityStatus", {}).get("status", "?")
        reason = j.get("playabilityStatus", {}).get("reason", "")[:45]
        fmts = j.get("streamingData", {}).get("adaptiveFormats", []) or []
        wu_list = [f for f in fmts if f.get("url")]
        au = len([f for f in wu_list if str(f.get("mimeType","")).startswith("audio/")])
        info = f"ps={ps} {reason!r} fmts={len(fmts)} direct={len(wu_list)} audio={au}"
        print(label, "->", info); results.append((label, info))
    else:
        print(label, "->", st); results.append((label, st))

# ── C. INVIDIOUS INSTANCES ──
print()
INVIDIOUS = ["https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be",
             "https://invidious.f5.si", "https://iv.melmac.space"]
for inst in INVIDIOUS:
    st, _, body = fetch(f"{inst}/api/v1/videos/{VID}?fields=adaptiveFormats,title", timeout=10)
    if st != 200:
        print(f"C {inst} -> HTTP {st}"); results.append(("INVIDIOUS " + inst, f"HTTP {st}")); continue
    try:
        j = json.loads(body)
    except Exception:
        print(f"C {inst} -> bad json"); continue
    fmts = j.get("adaptiveFormats") or []
    audio = [f for f in fmts if str(f.get("type","")).startswith("audio/") and f.get("url")]
    info = f"fmts={len(fmts)} audio_urls={len(audio)}"
    print(f"C {inst} ->", info)
    results.append(("INVIDIOUS " + inst, info))
    if audio:
        res = probe_stream(audio[0]["url"])
        print("      ↳ stream probe:", res)
        results.append((f"INVIDIOUS {inst} STREAM", res))

# ── D. PIPED INSTANCES ──
PIPED = ["https://pipedapi.kavin.rocks", "https://api.piped.private.coffee", "https://pipedapi.reallyaweso.me"]
for api in PIPED:
    st, _, body = fetch(f"{api}/streams/{VID}", timeout=10,
                        headers={"User-Agent": UA_DESKTOP})
    if st != 200:
        print(f"D {api} -> HTTP {st}"); results.append(("PIPED " + api, f"HTTP {st}")); continue
    try:
        j = json.loads(body)
    except Exception:
        print(f"D {api} -> bad json"); continue
    auds = j.get("audioStreams") or []
    ok_auds = [a for a in auds if a.get("url")]
    info = f"audioStreams={len(auds)} with_url={len(ok_auds)}"
    print(f"D {api} ->", info)
    results.append(("PIPED " + api, info))
    if ok_auds:
        res = probe_stream(ok_auds[0]["url"])
        print("      ↳ stream probe:", res)
        results.append((f"PIPED {api} STREAM", res))

print("\n" + "═" * 60)
print("SUMMARY")
winners = [(n, r) for n, r in results if "STREAM" in n and ("20" in r)]
for n, r in results:
    mark = "✅" if "STREAM" in n and ("206" in r or " ct=" in r) else ("🟡" if "fmts" in r or "direct=" in r and "direct=0" not in r else "·")
    print(f"{mark} {n}: {r[:100]}")
