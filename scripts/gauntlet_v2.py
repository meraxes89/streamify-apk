#!/usr/bin/env python3
"""Gauntlet v2 — every untried anonymous angle."""
import json, re, gzip, urllib.request, urllib.error

VID = "jNQXAC9IVRw"
UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

def fetch(url, headers=None, data=None, timeout=12):
    h = {"User-Agent": UA_DESK}
    if data: h["Content-Type"] = "application/json"
    if headers: h.update(headers)
    req = urllib.request.Request(url, headers=h, data=data)
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip": raw = gzip.decompress(raw)
        return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return -1, str(e).encode()

def player(client_name, version, extra_ctx=None, headers=None, key=True, url="https://www.youtube.com/youtubei/v1/player"):
    # extra_headers is aliased to headers for caller convenience

    ctx = {"clientName": client_name, "clientVersion": version, "hl": "en", "gl": "US"}
    if extra_ctx: ctx.update(extra_ctx)
    payload = {"context": {"client": ctx}, "videoId": VID,
               "contentCheckOk": True, "racyCheckOk": True}
    u = url + "?prettyPrint=false" + (f"&key={key}" if isinstance(key, str) else "")
    st, body = fetch(u, headers=headers, data=json.dumps(payload).encode())
    if st != 200: return f"HTTP {st}", None
    try: j = json.loads(body)
    except Exception: return f"badjson {body[:60]}", None
    ps = j.get("playabilityStatus", {}).get("status", "?")
    reason = j.get("playabilityStatus", {}).get("reason", "")[:40]
    fmts = j.get("streamingData", {}).get("adaptiveFormats") or []
    wu = [f for f in fmts if f.get("url")]
    aud = [f for f in wu if str(f.get("mimeType","")).startswith("audio/")]
    sabr = bool(j.get("streamingData", {}).get("serverAbrStreamingUrl"))
    info = f"ps={ps}({reason}) fmts={len(fmts)} direct={len(wu)} audio_direct={len(aud)} sabr={sabr}"
    return info, aud[0]["url"] if aud else None

def stream_probe(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA_DESK, "Range": "bytes=0-999"})
        r = urllib.request.urlopen(req, timeout=10)
        return f"{r.status} OK ct={r.headers.get('Content-Type','?')[:24]}"
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}"
    except Exception as e:
        return f"ERR {str(e)[:35]}"

# get visitorId first
_, home = fetch(f"https://www.youtube.com/watch?v={VID}")
vid_m = re.search(rb'"visitorData":"([^"]+)"', home or b"")
visitor = vid_m.group(1).decode() if vid_m else None
print(f"visitorData: {'len '+str(len(visitor)) if visitor else 'none'}\n")

tests = [
    # label, fn
    ("T1 TVHTML5 v7.20250312 (non-embed!)", lambda: player(
        "TVHTML5", "7.20250312.16.00",
        headers={"User-Agent": "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"})),
    ("T2 MEDIA_CONNECT_FRONTEND", lambda: player(
        "MEDIA_CONNECT_FRONTEND", "0.1",
        headers={"User-Agent": "Mozilla/5.0"})),
    ("T3 WEB_REMIX + visitor hdr", lambda: player(
        "WEB_REMIX", "1.20240401.01.00",
        extra_ctx={"originalUrl": f"https://music.youtube.com/watch?v={VID}"},
        headers={"Origin": "https://music.youtube.com",
                 "Referer": "https://music.youtube.com/",
                 **({"X-Goog-Visitor-Id": visitor} if visitor else {})},
        url="https://music.youtube.com/youtubei/v1/player")),
    ("T4 WEB_REMIX keyless", lambda: player(
        "WEB_REMIX", "1.20240401.01.00", key=False,
        headers={"Origin": "https://music.youtube.com"},
        url="https://music.youtube.com/youtubei/v1/player")),
    ("T5 WEB_EMBEDDED_PLAYER 1.20250310", lambda: player(
        "WEB_EMBEDDED_PLAYER", "1.20250310.01.00",
        extra_ctx={"thirdPartyEmbedUrl": "https://google.com"},
        headers={"Referer": "https://www.youtube.com/"})),
    ("T6 ANDROID_VR 1.62.27 (newer)", lambda: player(
        "ANDROID_VR", "1.62.27",
        extra_ctx={"androidSdkVersion": 34},
        headers={"User-Agent": "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 14; eureka-user Build/UQ1A.240105.002) gzip"})),
    ("T7 IOS 19.29.1 (older)", lambda: player(
        "IOS", "19.29.1",
        extra_ctx={"deviceModel": "iPhone16,2"},
        headers={"User-Agent": "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"})),
]

for label, fn in tests:
    info, url = fn()
    print(f"{label}\n   -> {info}")
    if url:
        print(f"   ↳ stream: {stream_probe(url)}")
    print()

# T8: music.youtube.com PAGE scrape (SABR check for music web)
print("T8 music.youtube.com page scrape")
st, body = fetch(f"https://music.youtube.com/watch?v={VID}", 
                 headers={"Cookie": "CONSENT=YES+"})
if st == 200:
    html = body.decode("utf-8","ignore")
    has_pr = '"playerResponse"' in html or 'ytInitialPlayerResponse' in html
    m = re.search(r'ytInitialPlayerResponse\s*=\s*(\{.+?\});', html, re.S)
    if m:
        try:
            pr = json.loads(m.group(1))
            ps = pr.get("playabilityStatus",{}).get("status")
            sd = pr.get("streamingData",{})
            print(f"   -> ps={ps} adaptiveFmt={len(sd.get('adaptiveFormats') or [])} sabr={'serverAbrStreamingUrl' in sd}")
        except Exception as e:
            print("   -> parse fail:", str(e)[:50])
    else:
        print(f"   -> PR present={has_pr} (no inline response)")
else:
    print(f"   -> HTTP {st}")

# T9: SABR url naked GET (from earlier extreme run we know shape exists on watch page)
print("\nT9 serverAbrStreamingUrl naked GET probe")
st, body = fetch(f"https://www.youtube.com/watch?v={VID}")
m = re.search(rb'"serverAbrStreamingUrl":"([^"]+)"', body or b"")
if m:
    sabr_url = m.group(1).decode().replace("\\u0026","&")
    res = stream_probe(sabr_url[:200])  # truncated probe anyway
    print(f"   -> got sabr url ({len(sabr_url)}b); naked probe: {res}")
    # also POST empty
    st2, b2 = fetch(sabr_url.split("?")[0], data=b"{}",
                    headers={"Content-Type":"application/x-www-form-urlencoded"})
    print(f"   -> POST: HTTP {st2} first bytes: {b2[:40]!r}")
else:
    print("   -> no sabr url found")

# T10: cobalt community instances
print("\nT10 cobalt instances")
for host in ["https://cobalt-backend.canine.tools", "https://capi.oak.li", "https://cobalt-api.kwiatekmiki.com"]:
    st, body = fetch(f"{host}/", timeout=8)
    print(f"   {host} -> HTTP {st} {body[:60]!r}")

# T11: more invidious (fresh list)
print("\nT11 invidious round 2")
for inst in ["https://invidious.privacydev.net","https://iv.datura.network","https://invidious.dhusch.de","https://inv.us.projectsegfau.lt"]:
    st, body = fetch(f"{inst}/api/v1/videos/{VID}?fields=adaptiveFormats", timeout=8)
    txt = body[:80].decode('utf-8','ignore')
    ok = st==200 and 'audio' in txt.lower() or st==200 and 'format' in txt.lower()
    print(f"   {inst} -> HTTP {st} {txt[:70]!r}")
