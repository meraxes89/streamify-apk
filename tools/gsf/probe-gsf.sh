#!/usr/bin/env bash
# tools/gsf/probe-gsf.sh — the GSF lane posture probe (G5).
#
# Measures the whole G5 bet against a deployed tools/gsf Apps Script:
#   1. ping  — the script answers + its UrlFetch egress IP (Google ASN);
#   2. play  — one tokenless IOS player POST for a LICENSED track carried
#              THROUGH the script: does Google's own ASN egress serve
#              licensed playability where the shared Cloudflare relay gets
#              LOGIN_REQUIRED after 1-2 plays?
#
# Usage: bash tools/gsf/probe-gsf.sh <gsf-exec-url> [videoId]
#   default videoId: 4NRXx6U8ABQ (the canary's licensed/official sample)
# Exit codes: 0 = probed (verdicts printed; the CALLER decides gating),
#             1 = usage error / the script itself is unreachable.
set -u
GSF_URL="${1:?usage: probe-gsf.sh <gsf-exec-url> [videoId]}"
VIDEO_ID="${2:-4NRXx6U8ABQ}"

jqf() { jq -r "$1" 2>/dev/null <<<"$2" || printf 'PARSE-ERR'; }

echo "== GSF ping =="
if ! PING=$(curl -sSL --max-time 20 "${GSF_URL}?ping=1"); then
  echo "PING-FAIL — the /exec URL did not answer (deployment not 'Anyone'? offline?)"
  exit 1
fi
echo "$PING"
PING_OK=$(jqf '.ok // false' "$PING")
EIP=$(jqf '.eip // "?"' "$PING")
echo "ping: ok=${PING_OK} egress=${EIP}"

echo
echo "== GSF licensed player (IOS, tokenless, through the script) =="
BODY=$(jq -nc --arg id "$VIDEO_ID" '{
  context:{client:{clientName:"IOS",clientVersion:"21.26.4",hl:"en",gl:"US",
    userAgent:"com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
    deviceMake:"Apple",deviceModel:"iPhone16,2",osName:"iPhone",osVersion:"18.3.2.22D82"}},
  videoId:$id, contentCheckOk:true, racyCheckOk:true}')
ENVELOPE=$(jq -nc \
  --arg u 'https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&alt=json' \
  --arg b "$BODY" \
  '{u:$u, m:"post",
    h:{"content-type":"application/json","x-youtube-client-name":"5","x-youtube-client-version":"21.26.4"},
    b:$b, e:"t"}')
if ! WIRE=$(curl -sSL -G --max-time 30 "$GSF_URL" --data-urlencode "q=$ENVELOPE"); then
  echo "ENVELOPE-FAIL — the script refused/errored the envelope"
  exit 1
fi
WIRE_OK=$(jqf '.ok // false' "$WIRE")
WIRE_STATUS=$(jqf '.s // "?"' "$WIRE")
WIRE_ERROR=$(jqf '.error // ""' "$WIRE")
PLAYER=$(jqf '.b // ""' "$WIRE")
STATUS=$(jqf '.playabilityStatus.status // "NO-RESPONSE"' "$PLAYER")
REASON=$(jqf '.playabilityStatus.reason // "-"' "$PLAYER")
FMTS=$(jqf '[.streamingData.adaptiveFormats[]?] | length' "$PLAYER")
PROG=$(jqf '[.streamingData.adaptiveFormats[]? | select(.url != null)] | length' "$PLAYER")
printf '%-8s http=%-4s status=%-16s progUrls=%-3s fmts=%-3s egress=%s\n' \
  "ok=${WIRE_OK}" "${WIRE_STATUS:0:4}" "${STATUS:0:16}" "${PROG:0:3}" "${FMTS:0:3}" "$EIP"
echo "reason: ${REASON}"
if [ -n "$WIRE_ERROR" ]; then
  echo "script error: ${WIRE_ERROR}"
fi

echo
if [ "$STATUS" = "OK" ]; then
  echo "VERDICT: the unburnable lane SERVES licensed content (Google-ASN egress ${EIP})."
elif [ "$STATUS" = "LOGIN_REQUIRED" ]; then
  echo "VERDICT: LOGIN_REQUIRED through the script — Google's egress gated THIS call."
  echo "Check whether progressive URLs exist for standard content (the licensed"
  echo "gate may be envelope-level, not egress-level); record and compare nightly."
else
  echo "VERDICT: ${STATUS} — record and compare nightly (environmental drift)."
fi
