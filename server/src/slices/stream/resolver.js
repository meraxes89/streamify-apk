import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';

const GAPI_ENDPOINT =
  'https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false';

const CLIENTS = [
  {
    name: 'ANDROID',
    number: '3',
    version: '21.26.364',
    ua: 'com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip',
    extra: {
      androidSdkVersion: 30,
      osName: 'Android',
      osVersion: '11',
    },
  },
  {
    name: 'IOS',
    number: '5',
    version: '21.26.4',
    ua: 'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)',
    extra: {
      deviceMake: 'Apple',
      deviceModel: 'iPhone16,2',
      osName: 'iPhone',
      osVersion: '18.3.2.22D82',
    },
  },
  {
    name: 'VISIONOS',
    number: '101',
    version: '1.02',
    ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
    extra: {
      deviceMake: 'Apple',
      deviceModel: 'RealityDevice17,1',
      osName: 'visionOS',
      osVersion: '26.5.23O471',
    },
  },
  {
    name: 'ANDROID_VR',
    number: '28',
    version: '1.65.10',
    ua: 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
    extra: {
      deviceMake: 'Oculus',
      deviceModel: 'Quest 2',
      osName: 'Android',
      osVersion: '12L',
    },
  },
];

let cachedVisitor = null;
let visitorExpiresAt = 0;

async function getVisitorData() {
  if (cachedVisitor && Date.now() < visitorExpiresAt) {
    return cachedVisitor;
  }
  try {
    const res = await fetch(
      'https://youtubei.googleapis.com/youtubei/v1/visitor_id?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk',
      {
        body: JSON.stringify({
          context: {
            client: {
              clientName: 'WEB',
              clientVersion: '2.20240101.01.00',
              gl: 'IN',
              hl: 'en',
            },
          },
        }),
        headers: { 'Content-Type': 'application/json' },
        method: 'POST',
        signal: AbortSignal.timeout(5000),
      }
    );
    if (res.ok) {
      const data = await res.json();
      const vd = data.responseContext?.visitorData;
      if (vd) {
        cachedVisitor = vd;
        visitorExpiresAt = Date.now() + 24 * 60 * 60 * 1000;
        return vd;
      }
    }
  } catch {}
  return null;
}

export class StreamResolver {
  constructor({ cache = new LruCache({ maxItems: 500 }) } = {}) {
    this.cache = cache;
    this.singleFlight = new SingleFlight();
  }

  async resolve(videoId) {
    if (!videoId || typeof videoId !== 'string') {
      throw new Error('videoId required');
    }

    const cached = this.cache.get(videoId);
    if (cached) {
      return cached;
    }

    return this.singleFlight.do(videoId, async () => {
      let lastError = null;
      const visitorData = await getVisitorData();

      for (const spec of CLIENTS) {
        try {
          const clientContext = {
            clientName: spec.name,
            clientVersion: spec.version,
            gl: 'IN',
            hl: 'en',
            userAgent: spec.ua,
            ...spec.extra,
          };
          if (visitorData) {
            clientContext.visitorData = visitorData;
          }

          const body = {
            contentCheckOk: true,
            context: { client: clientContext },
            racyCheckOk: true,
            videoId,
          };

          const headers = {
            'Content-Type': 'application/json',
            'User-Agent': spec.ua,
            'x-youtube-client-name': spec.number,
            'x-youtube-client-version': spec.version,
          };
          if (visitorData) {
            headers['x-goog-visitor-id'] = visitorData;
          }

          const res = await fetch(GAPI_ENDPOINT, {
            body: JSON.stringify(body),
            headers,
            method: 'POST',
            signal: AbortSignal.timeout(8000),
          });

          if (!res.ok) continue;
          const data = await res.json();
          const playability = data.playabilityStatus?.status;
          if (playability !== 'OK') {
            lastError = new Error(data.playabilityStatus?.reason || `Playability ${playability}`);
            continue;
          }

          const formats = [
            ...(data.streamingData?.adaptiveFormats || []),
            ...(data.streamingData?.formats || []),
          ];

          // Strict audio-only format policy: Opus (251) -> AAC (140) -> audio/* (No muxed video fallback)
          let selected = formats.find((f) => f.itag === 251 && f.url);
          if (!selected) selected = formats.find((f) => f.itag === 140 && f.url);
          if (!selected) selected = formats.find((f) => (f.mimeType || '').startsWith('audio/') && f.url);

          if (selected?.url) {
            const isOpus = (selected.mimeType || '').includes('opus');
            const parsedLength = selected.contentLength ? parseInt(selected.contentLength, 10) : null;
            const mime = selected.mimeType && selected.mimeType.startsWith('audio/')
              ? selected.mimeType
              : isOpus
                ? 'audio/webm; codecs="opus"'
                : 'audio/mp4; codecs="mp4a.40.2"';

            const descriptor = {
              videoId,
              targetUrl: selected.url,
              itag: selected.itag,
              mimeType: mime,
              contentLength: parsedLength,
              durationMs: (parseInt(data.videoDetails?.lengthSeconds, 10) || 0) * 1000,
              title: data.videoDetails?.title || '',
              artist: data.videoDetails?.author || '',
              client: spec.name,
              expiresAt: Date.now() + 5 * 3600 * 1000, // 5 hours
            };

            this.cache.set(videoId, descriptor, 5 * 3600 * 1000);
            return descriptor;
          }
        } catch (err) {
          lastError = err;
        }
      }

      throw lastError || new Error(`No playable progressive stream found for ${videoId}`);
    });
  }
}
