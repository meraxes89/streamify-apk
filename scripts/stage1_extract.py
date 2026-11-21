#!/usr/bin/env python3
"""Stage 1 v2: extract REAL sig+n transforms using yt-dlp-canonical patterns."""
import json, re, gzip
from urllib.parse import parse_qs
import urllib.request

VID = "jNQXAC9IVRw"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})
    r = urllib.request.urlopen(req, timeout=15)
    raw = r.read()
    if r.headers.get("Content-Encoding") == "gzip":
        raw = gzip.decompress(raw)
    return raw.decode("utf-8", "ignore")

html = fetch(f"https://www.youtube.com/watch?v={VID}&hl=en")

# PROVEN pattern (worked in extreme test):
m = re.search(r"ytInitialPlayerResponse\s*=\s*(\{.+?\});(?:\s*var\s|\s*</script>)", html, re.S)
pr = json.loads(m.group(1))
fmts = pr["streamingData"]["adaptiveFormats"]
ciphered = [f for f in fmts if f.get("signatureCipher")]
direct = [f for f in fmts if f.get("url")]
print(f"formats={len(fmts)} ciphered={len(ciphered)} direct={len(direct)}")
assert ciphered, "no ciphered formats?"
q = parse_qs(ciphered[0]["signatureCipher"])
s_val = q["s"][0]; sp = q.get("sp", ["sig"])[0]; base_url = q["url"][0]
print(f"s_len={len(s_val)} sp={sp}")

# player js
mj = re.search(r'"jsUrl":\s*"(/s/player/[A-Za-z0-9_/-]+/[\w.-]+\.js)"', html) \
  or re.search(r'"js":\s*"(/s/player/[^"]+)"', html) \
  or re.search(r'<script[^>]*src="(/s/player/[^"]+\.js)"', html)
js_path = mj.group(1).replace("\\/", "/")
js = fetch("https://www.youtube.com" + js_path)
open("/data/data/com.termux/files/usr/tmp/opencode/base.js", "w").write(js)
print(f"player_js={js_path} ({len(js)}b)")

# ── SIG: caller → fn name → flat body → helper obj ──
mc = re.search(
    r'\bc\s*&&\s*d\s*&&\s*[a-zA-Z0-9$]+\.set\([a-zA-Z0-9$"\'\[\]]+\s*,\s*encodeURIComponent\((?P<fn>[A-Za-z0-9$]+)\(',
    js)
fn_name = mc.group("fn") if mc else None
print("\nSIG caller fn:", fn_name)

if fn_name:
    mf = re.search(re.escape(fn_name) + r'\s*=\s*function\((\w+)\)\s*\{([^{}]+)\}', js)
    if mf:
        arg, body = mf.group(1), mf.group(2)
        print("SIG FN BODY:")
        print(fn_name, "= function(", arg, ") {")
        print(body.strip())
        print("}")
        ops = re.findall(r'([A-Za-z0-9$]+)\.([A-Za-z0-9$]{1,3})\(' + arg, body)
        print("ops:", ops)
        if ops:
            objname = ops[0][0]
            mo = re.search(
                r'(?:var|const|let)?\s*' + re.escape(objname) + r'\s*=\s*\{(.*?)\}\s*;',
                js, re.S)
            if mo:
                open("/data/data/com.termux/files/usr/tmp/opencode/sigobj.txt","w").write(mo.group(0))
                print(f"\nHELPER {objname} (saved, len={len(mo.group(1))}):")
                print(mo.group(0)[:800])

# ── N FN: locate via .get("n") dispatch ──
mn_call = re.search(r'\.get\("n"\)\)\s*&&\s*\(\s*(?P<v>\w+)\s*=\s*(?P<fn>[A-Za-z0-9$]+)\(', js)
if not mn_call:
    mn_call = re.search(r'"n"\]\s*\|\|\s*[A-Za-z0-9$]+\(\w+\)\s*&&\s*\(\s*\w+=\s*(?P<fn>[A-Za-z0-9$]+)\(', js)
n_name = mn_call.group("fn") if mn_call else None
print("\nN dispatcher fn:", n_name)

if n_name:
    # the named fn usually wraps an inner worker; dump both
    mw = re.search(re.escape(n_name) + r'\s*=\s*function\((\w+)\)\s*\{(.{0,400}?)return\s+(\w+)\.join\(""\)', js, re.S)
    if not mw:
        mw = re.search(re.escape(n_name) + r'\s*=\s*function\s*\((\w+)\)\s*\{(.+)\}', js, re.S)
    if mw:
        snippet = mw.group(0)
        print(f"N FN {n_name} len={len(snippet)}:")
        print(snippet[:600])
        # inner worker name?
        inner = re.search(r'=\s*([A-Za-z0-9$]+)\(\s*\w+\s*,\s*\w+\s*\)', snippet)
        if inner and inner.group(1) != n_name:
            wname = inner.group(1)
            mworker = re.search(re.escape(wname) + r'\s*=\s*function\s*\((\w+)\s*,\s*(\w+)\)\s*\{(.{100,}?)\n\s*\}\s*;', js, re.S)
            if mworker:
                code = mworker.group(0)
                open("/data/data/com.termux/files/usr/tmp/opencode/nfn.txt","w").write(code)
                print(f"\nWORKER {wname} (saved {len(code)}b):")
                print(code[:700])
