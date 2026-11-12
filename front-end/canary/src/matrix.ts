// matrix.ts — the W0.1b probe matrix, transcribed field-for-field from
// tools/probe-clients.sh (values sourced from yt-dlp master yt_dlp/extractor/youtube/_base.py
// INNERTUBE_CLIENTS, retrieved <2026-08-24> per that script's header — versions rot
// monthly; re-verify before trusting).
//
// Shell row format (pipe-separated):
//   name|host|clientNumber|clientName|clientVersion|userAgent|extraClientJson|extraTopLevelJson
// where an empty extraClientJson/extraTopLevelJson field means null. The 7th field
// (extraClientJson) merges INTO context.client of the player payload; the 8th
// (extraTopLevelJson) merges at the TOP LEVEL of the payload — exactly how the jq in
// probe-clients.sh consumes them ($extraCtx → context.client, $extraTop → top level).
//
// clientNumber/clientVersion double as the X-YouTube-Client-Name / X-YouTube-Client-Version
// request headers; userAgent only ever travels inside the JSON body (context.client.userAgent)
// because fetch cannot set the User-Agent header (see relayFetch.ts / README).

export interface ClientSpec {
  /** col 1 — matrix row label, e.g. 'VR_1.60.19' */
  name: string;
  /** col 2 — innertube host the player endpoint is hit on */
  host: string;
  /** col 3 — X-YouTube-Client-Name value */
  clientNumber: string;
  /** col 4 — context.client.clientName */
  clientName: string;
  /** col 5 — context.client.clientVersion + X-YouTube-Client-Version value */
  clientVersion: string;
  /** col 6 — matrix UA; travels in the JSON BODY only (fetch cannot set UA headers) */
  userAgent: string;
  /** col 7 — extra context.client fields, or null */
  extraClientJson: Record<string, unknown> | null;
  /** col 8 — extra top-level payload fields, or null */
  extraTopLevelJson: Record<string, unknown> | null;
}

export const CLIENTS: ClientSpec[] = [
  {
    name: 'VR_1.60.19',
    host: 'www.youtube.com',
    clientNumber: '28',
    clientName: 'ANDROID_VR',
    clientVersion: '1.60.19',
    userAgent:
      'com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
    extraClientJson: {
      deviceMake: 'Oculus',
      deviceModel: 'Quest 3',
      androidSdkVersion: 32,
      osName: 'Android',
      osVersion: '12L'
    },
    extraTopLevelJson: null
  },
  {
    name: 'VR_1.65.10',
    host: 'www.youtube.com',
    clientNumber: '28',
    clientName: 'ANDROID_VR',
    clientVersion: '1.65.10',
    userAgent:
      'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
    extraClientJson: {
      deviceMake: 'Oculus',
      deviceModel: 'Quest 3',
      androidSdkVersion: 32,
      osName: 'Android',
      osVersion: '12L'
    },
    extraTopLevelJson: null
  },
  {
    name: 'ANDROID',
    host: 'www.youtube.com',
    clientNumber: '3',
    clientName: 'ANDROID',
    clientVersion: '21.26.364',
    userAgent: 'com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip',
    extraClientJson: {
      androidSdkVersion: 30,
      osName: 'Android',
      osVersion: '11'
    },
    extraTopLevelJson: null
  },
  {
    name: 'IOS',
    host: 'www.youtube.com',
    clientNumber: '5',
    clientName: 'IOS',
    clientVersion: '21.26.4',
    userAgent: 'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)',
    extraClientJson: {
      deviceMake: 'Apple',
      deviceModel: 'iPhone16,2',
      osName: 'iPhone',
      osVersion: '18.3.2.22D82'
    },
    extraTopLevelJson: null
  },
  {
    name: 'VISIONOS',
    host: 'www.youtube.com',
    clientNumber: '101',
    clientName: 'VISIONOS',
    clientVersion: '1.02',
    userAgent:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
    extraClientJson: {
      deviceMake: 'Apple',
      deviceModel: 'RealityDevice17,1',
      osName: 'visionOS',
      osVersion: '26.5.23O471'
    },
    extraTopLevelJson: null
  },
  {
    name: 'MWEB',
    host: 'www.youtube.com',
    clientNumber: '2',
    clientName: 'MWEB',
    clientVersion: '2.20260708.05.00',
    userAgent:
      'Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)',
    extraClientJson: null,
    extraTopLevelJson: null
  },
  {
    name: 'TVHTML5',
    host: 'www.youtube.com',
    clientNumber: '7',
    clientName: 'TVHTML5',
    clientVersion: '7.20260707.07.00',
    userAgent:
      'Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)',
    extraClientJson: null,
    extraTopLevelJson: null
  },
  {
    name: 'TV_DOWNGRADED',
    host: 'www.youtube.com',
    clientNumber: '7',
    clientName: 'TVHTML5',
    clientVersion: '5.20260707',
    userAgent: 'Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version',
    extraClientJson: null,
    extraTopLevelJson: null
  },
  {
    name: 'WEB_EMBEDDED',
    host: 'www.youtube.com',
    clientNumber: '56',
    clientName: 'WEB_EMBEDDED_PLAYER',
    clientVersion: '2.20260708.00.00',
    userAgent: 'Mozilla/5.0',
    extraClientJson: null,
    extraTopLevelJson: {
      thirdParty: {
        embedUrl: 'https://www.reddit.com/'
      }
    }
  },
  {
    name: 'WEB_REMIX',
    host: 'music.youtube.com',
    clientNumber: '67',
    clientName: 'WEB_REMIX',
    clientVersion: '1.20260707.12.00',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
    extraClientJson: null,
    extraTopLevelJson: null
  }
];

/** default video ids: dQw4w9WgXcQ (standard content), 4NRXx6U8ABQ (licensed/official) */
export const VIDEOS = ['dQw4w9WgXcQ', '4NRXx6U8ABQ'];

/**
 * YouTube Music search `params` from tools/probe-search-resolver.sh
 * (WEB_REMIX search filter). Exported for reference/documentation of the
 * search→videoId leg of that script's chain — the R-C phase resolves the
 * licensed video directly, so this is not used by the browser probe itself.
 */
export const SEARCH_PARAMS = 'egWKAQIIAWoMEAMQBBAJEAoQBRAV';

/** innertube signatureTimestamp used by the ANDROID player call in tools/probe-search-resolver.sh */
export const SIGNATURE_TIMESTAMP = 19850;
