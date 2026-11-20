#!/usr/bin/env python3
"""GVS session-binding test: which headers does ExoPlayer NEED on the minted URL?"""
import json, re, gzip, http.cookiejar
import urllib.request, urllib.error

VID = "dQw4w9WgXcQ"  # real music video
UA_PAGE = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
UA_VR = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
UA_EXO = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def read(r):
    raw = r.read()
    if r.headers.get("Content-Encoding") in ("gzip","deflate"): raw = gzip.decompress(raw)
    return raw

# resolve via production recipe
req = urllib.request.Request(
    f"https://www.youtube.com/watch?v={VID}&bpctr=9999999999&has_verified=1&hl=en",
    headers={"User-Agent": UA_PAGE,
             "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
             "Accept-Language": "en-us,en;q=0.5",
             "Cookie": "PREF=hl=en&tz=UTC; SOCS=CAI",
             "Sec-Fetch-Mode": "navigate"})
html = read(opener.open(req, timeout=15)).decode("utf-8","ignore")
cookies = "; ".join(f"{c.name}={c.value}" for c in cj)
full_cookies = f"PREF=hl=en&tz=UTC; SOCS=CAI; {cookies}"
visitor = next(iter(re.findall(r'"visitorData":"([^"]+)"', html)), "")
sts_m = re.search(r'"signatureTimestamp":(\d+)', html)
sts = int(sts_m.group(1)) if sts_m else 20683

body = {"context": {"client": {
            "clientName": "ANDROID_VR", "clientVersion": "1.65.10",
            "deviceMake": "Oculus", "deviceModel": "Quest 3",
            "androidSdkVersion": 32, "userAgent": UA_VR,
            "osName": "Android", "osVersion": "12L",
            "hl": "en", "timeZone": "UTC", "utcOffsetMinutes": 0}},
        "videoId": VID,
        "playbackContext": {"contentPlaybackContext": {
            "html5Preference": "HTML5_PREF_WANTS", "signatureTimestamp": sts}},
        "contentCheckOk": True, "racyCheckOk": True}
hdrs = {"Content-Type": "application/json", "User-Agent": UA_VR,
        "Accept-Language": "en-us,en;q=0.5", "Sec-Fetch-Mode": "navigate",
        "Cookie": full_cookies, "X-Youtube-Client-Name": "28",
        "X-Youtube-Client-Version": "1.65.10", "Origin": "https://www.youtube.com"}
if visitor: hdrs["X-Goog-Visitor-Id"] = visitor

j = json.loads(read(opener.open(urllib.request.Request(
    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    data=json.dumps(body).encode(), headers=hdrs), timeout=15)))
assert j.get("playabilityStatus",{}).get("status") == "OK", j.get("playabilityStatus")

fmts = [f for f in j["streamingData"]["adaptiveFormats"]
        if f.get("url") and str(f.get("mimeType","")).startswith("audio/")]
fmts.sort(key=lambda f: f.get("bitrate",0), reverse=True)
url = fmts[0]["url"].replace("\\u0026","&")
print(f"resolved itag={fmts[0]['itag']} br={fmts[0]['bitrate']}\n")

def probe(label, ua=None, cookie=None, origin=False):
    h = {}
    if ua: h["User-Agent"] = ua
    if cookie: h["Cookie"] = cookie
    if origin:
        h["Origin"] = "https://music.youtube.com"
        h["Referer"] = "https://music.youtube.com/"
    try:
        req = urllib.request.Request(url, headers={"Range":"bytes=0-262143", **h})
        r = urllib.request.urlopen(req, timeout=20)
        data = r.read(262144)
        print(f"{label:<42} HTTP {r.status} got={len(data)}B {'✅' if len(data)>100_000 else '⚠️'}")
        return len(data) > 100_000
    except urllib.error.HTTPError as e:
        print(f"{label:<42} HTTP {e.code} ❌")
    except Exception as e:
        print(f"{label:<42} ERR {str(e)[:50]}")
    return False

probe("1. VR app UA (what my probes used)", UA_VR)
probe("2. ExoPlayer Chrome/Pixel UA (current factory)", UA_EXO)
probe("3. NO User-Agent at all", None)
probe("4. ExoPlayer UA + warmed cookies", UA_EXO, full_cookies)
probe("5. ExoPlayer UA + music Origin/Referer", UA_EXO, None, True)
probe("6. ExoPlayer UA + cookies + origin (full bind)", UA_EXO, full_cookies, True)
