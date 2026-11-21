#!/usr/bin/env bash
# tools/probe-clients.sh — bare-client × content matrix for the Innertube player endpoint.
# Values sourced from yt-dlp master yt_dlp/extractor/youtube/_base.py (INNERTUBE_CLIENTS),
# retrieved <retrieved 2026-08-24>. Versions rot monthly — re-verify before trusting.
#
# Usage: bash tools/probe-clients.sh [videoId ...]
#   default ids: dQw4w9WgXcQ (standard content)  4NRXx6U8ABQ (licensed/official)
set -u
URL_DEFAULT='https://www.youtube.com/youtubei/v1/player'
IDS=("$@"); [ ${#IDS[@]} -eq 0 ] && IDS=(dQw4w9WgXcQ 4NRXx6U8ABQ)

# name|host|clientNumber|clientName|version|userAgent|extraClientJson|extraContextJson
CLIENTS=(
  'VR_1.60.19|www.youtube.com|28|ANDROID_VR|1.60.19|com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip|{"deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}|'
  'VR_1.65.10|www.youtube.com|28|ANDROID_VR|1.65.10|com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip|{"deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}|'
  'ANDROID|www.youtube.com|3|ANDROID|21.26.364|com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip|{"androidSdkVersion":30,"osName":"Android","osVersion":"11"}|'
  'IOS|www.youtube.com|5|IOS|21.26.4|com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)|{"deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82"}|'
  'VISIONOS|www.youtube.com|101|VISIONOS|1.02|Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15|{"deviceMake":"Apple","deviceModel":"RealityDevice17,1","osName":"visionOS","osVersion":"26.5.23O471"}|'
  'MWEB|www.youtube.com|2|MWEB|2.20260708.05.00|Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)||'
  'TVHTML5|www.youtube.com|7|TVHTML5|7.20260707.07.00|Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)||'
  'TV_DOWNGRADED|www.youtube.com|7|TVHTML5|5.20260707|Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version||'
  'WEB_EMBEDDED|www.youtube.com|56|WEB_EMBEDDED_PLAYER|2.20260708.00.00|Mozilla/5.0||{"thirdParty":{"embedUrl":"https://www.reddit.com/"}}'
  'WEB_REMIX|music.youtube.com|67|WEB_REMIX|1.20260707.12.00|Mozilla/5.0 (Windows NT 10.0; Win64; x64)||'
)

printf '%-14s %-13s %-12s %-6s %-6s %-9s %s\n' CLIENT VIDEO STATUS AUDIO FMTS CIPHERED REASON
for id in "${IDS[@]}"; do
  for c in "${CLIENTS[@]}"; do
    IFS='|' read -r name host cn cname cver ua extraCtx extraTop <<<"$c"
    payload=$(jq -nc \
      --arg cname "$cname" --arg cver "$cver" --arg vid "$id" --arg ua "$ua" \
      --argjson extraCtx "${extraCtx:-null}" --argjson extraTop "${extraTop:-null}" '
      {context: {client: ({clientName:$cname, clientVersion:$cver, hl:"en", gl:"US"}
                  + (if $extraCtx then $extraCtx else {} end)
                  + (if ($extraCtx // {}) | has("userAgent") then {} else {userAgent:$ua} end))},
        videoId:$vid, contentCheckOk:true, racyCheckOk:true}
      + (if $extraTop then $extraTop else {} end)')
    resp=$(curl -s --compressed --max-time 20 "https://$host/youtubei/v1/player" \
      -H 'Content-Type: application/json' -H "User-Agent: $ua" \
      -H "X-YouTube-Client-Name: $cn" -H "X-YouTube-Client-Version: $cver" \
      --data "$payload")
    out=$(printf '%s' "$resp" | jq -r '
      [.playabilityStatus.status // "NO-RESPONSE",
       ([.streamingData.adaptiveFormats[]? |
          select((.itag==140) or (.itag==251))] | length),
       ([.streamingData.adaptiveFormats[]?] | length),
       ([.streamingData.adaptiveFormats[]? | has("signatureCipher")] | length)]
      | join("|")')
    reason=$(printf '%s' "$resp" | jq -r '.playabilityStatus.reason // "-"')
    IFS='|' read -r status audio f251 fmts ciphered <<<"$out"
    printf '%-14s %-13s %-12s %-6s %-6s %-9s %s\n' "$name" "$id" "${status:-ERR}" "$audio/$f251" "$fmts" "$ciphered" "$reason"
    sleep 2
  done
done
