/**
 * Architecture v3 Stream Relay & Multi-Profile Resolver
 *
 * Implements:
 * 1. Mobile/VR Client Impersonation (VISIONOS / IOS / ANDROID_VR) via direct GAPI player endpoint
 * 2. 8 MiB Bounded Upstream Chunking to prevent GoogleVideo 32 KiB/s throttling
 * 3. Range-capable HTTP 206 Partial Content downstream streaming
 * 4. Single-Flight Request Deduplication & In-Memory LRU Cache
 * 5. Asynchronous Queue Prefetch Engine for sub-100ms track transitions
 */

import cors from 'cors';
import express from 'express';
import fs from 'fs';
import http from 'http';
import https from 'https';
import path from 'path';
import { Readable } from 'node:stream';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const publicDir = path.join(__dirname, 'public');

// Streamify Skin (mobile YTM-style shell): serve the monorepo-owned source
// directly at /skin so edits to front-end/skin/ytm.css show up on the next
// reload with no rebuild. This tree is NEVER vendored into front-end/feishin/,
// so upstream Feishin tag merges stay conflict-free. The generated copy in
// server/public/skin/ (written by scripts/inject-skin.mjs) is the fallback for
// deployments that ship only server/. Registered BEFORE express.static(publicDir)
// so the live source wins over the generated copy when both exist.
const skinSourceDir = path.resolve(__dirname, '../front-end/skin');
const skinDir = fs.existsSync(skinSourceDir)
    ? skinSourceDir
    : path.join(publicDir, 'skin');

const app = express();
const PORT = process.env.PORT || 3000;
const CHUNK_SIZE = 8 * 1024 * 1024; // 8 MiB bounded chunk

// Support Private Network Access (PNA) and CORS preflights from any origin
app.use((req, res, next) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS, HEAD');
    res.setHeader('Access-Control-Allow-Headers', '*');
    res.setHeader('Access-Control-Allow-Private-Network', 'true');
    if (req.method === 'OPTIONS') {
        return res.sendStatus(204);
    }
    next();
});
app.use(cors({ origin: '*' }));

// Parse raw bodies for binary / protobuf / json forwarding in /r proxy
app.use(express.raw({ limit: '50mb', type: '*/*' }));

// Live request logger middleware for Render dashboard
app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
        console.log(`[${new Date().toISOString()}] ${req.method} ${req.originalUrl || req.url} ${res.statusCode} (${Date.now() - start}ms)`);
    });
    next();
});

// Skin assets must be cache-busted independently of the SPA: index.html is
// no-store, but the skin files are versioned by this repo, not by Vite hashes.
// Mounted after the request logger (so /skin hits show up in Render logs) and
// before express.static(publicDir) (so live source wins over the generated copy).
app.use(
    '/skin',
    express.static(skinDir, {
        setHeaders: (res) => res.setHeader('Cache-Control', 'no-cache'),
    }),
);

if (fs.existsSync(publicDir)) {
    app.use(express.static(publicDir, {
        setHeaders: (res, filePath) => {
            if (filePath.endsWith('index.html')) {
                res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
                res.setHeader('Pragma', 'no-cache');
                res.setHeader('Expires', '0');
            }
            if (filePath.endsWith('.wasm')) {
                res.setHeader('Content-Type', 'application/wasm');
            }
        },
    }));
    // Cleanly redirect any legacy GitHub Pages subpaths to root
    app.use('/streamify-apk', (req, res) => {
        const target = req.url === '/' || req.url === '/preview' || req.url === '/preview/' ? '/' : req.url.replace(/^\/preview/, '');
        res.redirect(301, target || '/');
    });
}

// In-memory cache for resolved stream metadata and URLs (TTL 5.5 hours)
const CACHE_TTL_MS = 5.5 * 60 * 60 * 1000;
const streamCache = new Map();

// Prefetched initial chunks (first 8 MiB buffer)
const prefetchBuffers = new Map();

// Single-flight in-flight resolves deduplication
const inFlightResolves = new Map();

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
            },
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
    } catch {
        // Fallback to cached
    }
    return cachedVisitor;
}

/**
 * Resolve video stream using mobile/VR client profiles that don't trigger bot gates
 */
async function resolveStream(videoId) {
    const cached = streamCache.get(videoId);
    if (cached && Date.now() < cached.expiresAt) {
        return cached;
    }

    if (inFlightResolves.has(videoId)) {
        return inFlightResolves.get(videoId);
    }

    const resolvePromise = (async () => {
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
                    context: {
                        client: clientContext,
                    },
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

                if (!res.ok) {
                    console.log('[resolve]', videoId, spec.name, 'HTTP', res.status);
                    continue;
                }
                const data = await res.json();
                const playability = data.playabilityStatus?.status;
                console.log('[resolve]', videoId, spec.name, 'playability:', playability, 'reason:', data.playabilityStatus?.reason);
                if (playability !== 'OK') {
                    lastError = new Error(data.playabilityStatus?.reason || `Playability ${playability}`);
                    continue;
                }

                const formats = [
                    ...(data.streamingData?.adaptiveFormats || []),
                    ...(data.streamingData?.formats || []),
                ];

                // Prefer Opus (itag 251), then AAC (itag 140), then any audio
                let selected = formats.find((f) => f.itag === 251 && f.url);
                if (!selected) {
                    selected = formats.find((f) => f.itag === 140 && f.url);
                }
                if (!selected) {
                    selected = formats.find((f) => (f.mimeType || '').startsWith('audio/') && f.url);
                }
                if (!selected) {
                    // Fallback to muxed video format with audio (itag 18)
                    selected = formats.find((f) => f.itag === 18 && f.url);
                }

                if (selected?.url) {
                    const isOpus = (selected.mimeType || '').includes('opus');
                    const descriptor = {
                        artist: data.videoDetails?.author || '',
                        bitrate: selected.bitrate || (isOpus ? 160000 : 128000),
                        client: spec.name,
                        codecs: isOpus ? 'opus' : 'aac',
                        contentLength: selected.contentLength ? parseInt(selected.contentLength, 10) : null,
                        durationMs: (parseInt(data.videoDetails?.lengthSeconds, 10) || 0) * 1000,
                        expiresAt: Date.now() + CACHE_TTL_MS,
                        googlevideoUrl: selected.url,
                        isSabr: false,
                        itag: selected.itag,
                        mimeType: selected.mimeType || 'audio/webm; codecs="opus"',
                        ok: true,
                        protocol: 'progressive',
                        title: data.videoDetails?.title || '',
                        videoId,
                    };

                    streamCache.set(videoId, descriptor);
                    return descriptor;
                }

                if (data.streamingData?.serverAbrStreamingUrl) {
                    const ustreamerConfig =
                        data.playerConfig?.mediaCommonConfig
                            ?.mediaUstreamerRequestConfig
                            ?.videoPlaybackUstreamerConfig;
                    const descriptor = {
                        artist: data.videoDetails?.author || '',
                        client: spec.name,
                        durationMs: (parseInt(data.videoDetails?.lengthSeconds, 10) || 0) * 1000,
                        expiresAt: Date.now() + CACHE_TTL_MS,
                        isSabr: true,
                        ok: true,
                        protocol: 'sabr',
                        sabr: {
                            clientInfo: {
                                clientName: parseInt(spec.number, 10) || 5,
                                clientVersion: spec.version,
                            },
                            formats,
                            serverAbrStreamingUrl: data.streamingData.serverAbrStreamingUrl,
                            ustreamerConfig: ustreamerConfig || null,
                        },
                        title: data.videoDetails?.title || '',
                        videoId,
                    };

                    streamCache.set(videoId, descriptor);
                    return descriptor;
                }
            } catch (err) {
                lastError = err;
            }
        }

        throw lastError || new Error(`No playable stream found for ${videoId}`);
    })();

    inFlightResolves.set(videoId, resolvePromise);
    try {
        return await resolvePromise;
    } finally {
        inFlightResolves.delete(videoId);
    }
}

// ---------------------------------------------------------------------------
// 1. Root Capability Echo & Health
// ---------------------------------------------------------------------------

const echoCapabilities = (req, res) => {
    res.json({
        capabilities: [
            '8mib-range-stream',
            'mobile-vr-resolver',
            'prefetch',
            'ytm-meta',
            'neg-cache',
            'r-proxy',
        ],
        name: 'streamify-relay',
        status: 'ok',
        version: '3.0.0',
    });
};

app.get('/api/capabilities', echoCapabilities);

const sendNoCacheIndex = (res) => {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
    return res.sendFile(path.join(publicDir, 'index.html'));
};

app.get('/', (req, res) => {
    if (fs.existsSync(path.join(publicDir, 'index.html')) && req.accepts(['html', 'json']) !== 'json') {
        return sendNoCacheIndex(res);
    }
    echoCapabilities(req, res);
});

app.get('/api/health', (req, res) => {
    res.json({
        cache: {
            prefetched: prefetchBuffers.size,
            resolved: streamCache.size,
        },
        memory: process.memoryUsage(),
        status: 'ok',
        uptime: Math.floor(process.uptime()),
        version: '3.0.0',
    });
});

// ---------------------------------------------------------------------------
// 2. Stream Resolver
// ---------------------------------------------------------------------------

app.get('/api/resolve', async (req, res) => {
    const videoId = req.query.videoId;
    if (!videoId || typeof videoId !== 'string') {
        return res.status(400).json({ error: 'videoId query parameter required', ok: false });
    }

    try {
        const stream = await resolveStream(videoId);
        const host = req.get('host');
        const proto = req.get('x-forwarded-proto') || 'https';
        const baseUrl = `${proto}://${host}`;

        if (stream.isSabr) {
            return res.json({
                artist: stream.artist,
                durationMs: stream.durationMs,
                expiresAt: stream.expiresAt,
                isSabr: true,
                ok: true,
                protocol: 'sabr',
                sabr: stream.sabr,
                title: stream.title,
                videoId,
            });
        }

        res.json({
            artist: stream.artist,
            bitrate: stream.bitrate,
            codecs: stream.codecs,
            contentLength: stream.contentLength,
            durationMs: stream.durationMs,
            expiresAt: stream.expiresAt,
            isSabr: false,
            ok: true,
            protocol: 'progressive',
            rangeSupport: true,
            streamUrl: `${baseUrl}/api/stream/${videoId}`,
            title: stream.title,
            videoId,
        });
    } catch (err) {
        res.status(502).json({
            error: err.message || 'Stream resolution failed',
            ok: false,
            videoId,
        });
    }
});

// ---------------------------------------------------------------------------
// 3. 8 MiB Range Stream Relay
// ---------------------------------------------------------------------------

app.get(['/api/stream', '/api/stream/:videoId'], async (req, res) => {
    const videoId = req.params.videoId || req.query.id || req.query.videoId;
    if (!videoId) {
        return res.status(400).send('videoId required');
    }

    let streamDesc;
    try {
        streamDesc = await resolveStream(videoId);
    } catch (err) {
        return res.status(502).send(`Resolve error: ${err.message}`);
    }

    const targetUrl = streamDesc.googlevideoUrl;
    const totalLength = streamDesc.contentLength;

    // Check prefetch buffer for range starting at 0
    const rangeHeader = req.headers.range;
    let start = 0;
    let end = totalLength ? totalLength - 1 : undefined;

    if (rangeHeader) {
        const parts = rangeHeader.replace(/bytes=/, '').split('-');
        start = parseInt(parts[0], 10);
        if (parts[1]) {
            end = parseInt(parts[1], 10);
        }
    }

    // SSRF lock: strictly ensure upstream URL belongs to *.googlevideo.com
    try {
        const parsedUrl = new URL(targetUrl);
        if (!parsedUrl.hostname.endsWith('.googlevideo.com')) {
            return res.status(403).send('Forbidden upstream domain');
        }
    } catch {
        return res.status(400).send('Invalid stream target');
    }

    // Upstream 8 MiB Bounded Chunking:
    // Unbounded or >= 12 MiB requests are throttled by YouTube to 32 KiB/s.
    // We bound each upstream fetch to at most 8 MiB.
    const chunkEnd = end !== undefined ? Math.min(start + CHUNK_SIZE - 1, end) : start + CHUNK_SIZE - 1;

    // Serve from prefetch buffer if available and matching start = 0
    if (start === 0 && prefetchBuffers.has(videoId)) {
        const buffered = prefetchBuffers.get(videoId);
        if (buffered && buffered.length > 0) {
            const sendBuffer = end ? buffered.subarray(0, end + 1) : buffered;
            const headers = {
                'Accept-Ranges': 'bytes',
                'Content-Length': sendBuffer.length,
                'Content-Range': `bytes 0-${sendBuffer.length - 1}/${totalLength || '*'}`,
                'Content-Type': streamDesc.mimeType,
            };
            res.writeHead(206, headers);
            return res.end(sendBuffer);
        }
    }

    const upstreamHeaders = {
        'Accept': '*/*',
        'Range': `bytes=${start}-${chunkEnd}`,
        'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    };

    const protocol = targetUrl.startsWith('https') ? https : http;
    const proxyReq = protocol.get(targetUrl, { headers: upstreamHeaders }, (upstreamRes) => {
        const statusCode = upstreamRes.statusCode === 206 ? 206 : upstreamRes.statusCode === 200 ? 206 : upstreamRes.statusCode;
        const downstreamHeaders = {
            'Accept-Ranges': 'bytes',
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
            'Content-Type': streamDesc.mimeType,
        };

        if (upstreamRes.headers['content-range']) {
            downstreamHeaders['Content-Range'] = upstreamRes.headers['content-range'];
        } else if (totalLength) {
            downstreamHeaders['Content-Range'] = `bytes ${start}-${chunkEnd}/${totalLength}`;
        }

        if (upstreamRes.headers['content-length']) {
            downstreamHeaders['Content-Length'] = upstreamRes.headers['content-length'];
        }

        res.writeHead(statusCode, downstreamHeaders);
        upstreamRes.pipe(res);
    });

    proxyReq.on('error', (err) => {
        if (!res.headersSent) {
            res.status(502).send(`Upstream stream error: ${err.message}`);
        }
    });

    req.on('close', () => {
        proxyReq.destroy();
    });
});

// ---------------------------------------------------------------------------
// 4. Predictive Queue Prefetch
// ---------------------------------------------------------------------------

app.post('/api/prefetch', async (req, res) => {
    const { nextVideoId } = req.body || {};
    if (!nextVideoId || typeof nextVideoId !== 'string') {
        return res.status(400).json({ error: 'nextVideoId required', ok: false });
    }

    // Acknowledge immediately (fire-and-forget from client perspective)
    res.json({ ok: true, prefetching: nextVideoId });

    // Background warming of first 8 MiB chunk into memory buffer
    (async () => {
        try {
            if (prefetchBuffers.has(nextVideoId)) return;

            const streamDesc = await resolveStream(nextVideoId);
            const targetUrl = streamDesc.googlevideoUrl;
            const firstChunkEnd = CHUNK_SIZE - 1;

            const protocol = targetUrl.startsWith('https') ? https : http;
            protocol.get(
                targetUrl,
                {
                    headers: {
                        'Range': `bytes=0-${firstChunkEnd}`,
                        'User-Agent':
                            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
                    },
                },
                (upstreamRes) => {
                    const chunks = [];
                    upstreamRes.on('data', (d) => chunks.push(d));
                    upstreamRes.on('end', () => {
                        const buffer = Buffer.concat(chunks);
                        prefetchBuffers.set(nextVideoId, buffer);
                        // Auto-expire prefetch buffer after 10 minutes to save memory
                        setTimeout(() => prefetchBuffers.delete(nextVideoId), 10 * 60 * 1000);
                    });
                },
            );
        } catch {
            // Non-fatal background prefetch error
        }
    })();
});

// ---------------------------------------------------------------------------
// 5. Search Proxy
// ---------------------------------------------------------------------------

app.get('/api/search', async (req, res) => {
    const query = req.query.q;
    if (!query || typeof query !== 'string') {
        return res.status(400).json({ error: 'q query parameter required', ok: false });
    }

    try {
        const suggestUrl = `https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&q=${encodeURIComponent(query)}`;
        const suggestRes = await fetch(suggestUrl);
        const text = await suggestRes.text();
        const jsonMatch = text.match(/window\.google\.ac\.h\((.*)\)/);
        let suggestions = [];
        if (jsonMatch && jsonMatch[1]) {
            const parsed = JSON.parse(jsonMatch[1]);
            suggestions = (parsed[1] || []).map((item) => item[0]);
        }

        res.json({ ok: true, suggestions });
    } catch (err) {
        res.status(502).json({ error: err.message, ok: false });
    }
});

// ---------------------------------------------------------------------------
// 6. Universal CORS Passthrough Proxy (/r?u=<target>)
// ---------------------------------------------------------------------------

const CLIENT_NAME_UA = new Map([
    ['3', 'com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip'],
    ['5', 'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)'],
    ['28', 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'],
    ['101', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'],
]);

const UA_OVERRIDES = new Map([
    ['safari-mac', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'],
    ['safari-ios', 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1'],
]);

app.all('/r', async (req, res) => {
    const targetUrl = req.query.u;
    if (!targetUrl || typeof targetUrl !== 'string') {
        return res.status(400).send('u query parameter required');
    }

    try {
        const parsed = new URL(targetUrl);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
            return res.status(400).send('Invalid protocol');
        }

        const clientName = req.headers['x-youtube-client-name'];
        const namedUa = req.headers['x-relay-ua'];
        let matchedUa = null;
        if (clientName && CLIENT_NAME_UA.has(String(clientName))) {
            matchedUa = CLIENT_NAME_UA.get(String(clientName));
        } else if (namedUa && UA_OVERRIDES.has(String(namedUa).toLowerCase())) {
            matchedUa = UA_OVERRIDES.get(String(namedUa).toLowerCase());
        }

        const upstreamHeaders = {};
        for (const [key, value] of Object.entries(req.headers)) {
            const lower = key.toLowerCase();
            if (['connection', 'content-length', 'cookie', 'host', 'origin', 'referer', 'x-relay-ua'].includes(lower)) continue;
            if (matchedUa && lower.startsWith('sec-ch-ua')) continue;
            upstreamHeaders[key] = value;
        }

        if (matchedUa) {
            upstreamHeaders['user-agent'] = matchedUa;
        } else if (!upstreamHeaders['user-agent']) {
            upstreamHeaders['user-agent'] =
                'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36';
        }

        const fetchOptions = {
            headers: upstreamHeaders,
            method: req.method,
        };

        if (req.method !== 'GET' && req.method !== 'HEAD') {
            if (Buffer.isBuffer(req.body) && req.body.length > 0) {
                fetchOptions.body = req.body;
            } else if (typeof req.body === 'string' && req.body.length > 0) {
                fetchOptions.body = req.body;
            } else if (typeof req.body === 'object' && req.body && Object.keys(req.body).length > 0) {
                fetchOptions.body = JSON.stringify(req.body);
            }
        }

        const upstreamRes = await fetch(targetUrl, fetchOptions);

        res.status(upstreamRes.status);
        res.set('Access-Control-Allow-Origin', '*');
        res.set('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS, HEAD');
        res.set('Access-Control-Allow-Headers', '*');
        res.set('Access-Control-Expose-Headers', '*');
        res.set('Access-Control-Allow-Private-Network', 'true');

        for (const [k, v] of upstreamRes.headers.entries()) {
            const lk = k.toLowerCase();
            if (['connection', 'content-encoding', 'transfer-encoding'].includes(lk)) continue;
            res.set(k, v);
        }

        if (upstreamRes.body) {
            const nodeStream = Readable.fromWeb(upstreamRes.body);
            nodeStream.pipe(res);
            res.on('close', () => {
                nodeStream.destroy();
            });
        } else {
            res.end();
        }
    } catch (err) {
        console.error('[/r proxy error]:', err.message);
        res.status(502).send(`Proxy error: ${err.message}`);
    }
});

// ---------------------------------------------------------------------------
// 7. Metadata Edge Gateway (/ytm/meta)
// ---------------------------------------------------------------------------

const GAPI_SEARCH_ENDPOINT =
    'https://youtubei.googleapis.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false';

async function searchYtm(query) {
    const body = {
        context: {
            client: {
                clientName: 'WEB_REMIX',
                clientVersion: '1.20240101.01.00',
                gl: 'IN',
                hl: 'en',
            },
        },
        query,
    };

    const res = await fetch(GAPI_SEARCH_ENDPOINT, {
        body: JSON.stringify(body),
        headers: { 'Content-Type': 'application/json' },
        method: 'POST',
        signal: AbortSignal.timeout(8000),
    });

    if (!res.ok) return [];
    const data = await res.json();
    const sections =
        data.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content
            ?.sectionListRenderer?.contents || [];
    const results = [];

    for (const s of sections) {
        const card = s.musicCardShelfRenderer;
        if (card) {
            const title = card.title?.runs?.map((r) => r.text).join('') || '';
            const rawSub = card.subtitle?.runs?.map((r) => r.text).join('') || '';
            const subtitle = rawSub.replace(/^(Song|Video|Single|Album|EP)\s*•\s*/i, '');
            const videoId =
                card.buttons?.[0]?.buttonRenderer?.command?.watchEndpoint?.videoId ||
                card.title?.runs?.[0]?.navigationEndpoint?.watchEndpoint?.videoId ||
                null;
            if (title && videoId) {
                results.push({
                    durationMs: null,
                    subtitle: subtitle || null,
                    thumbnailUrl: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                    title,
                    videoId,
                });
            }
        }

        const shelf = s.musicShelfRenderer || s.itemSectionRenderer;
        const items = shelf?.contents || [];
        for (const raw of items) {
            const item = raw.musicResponsiveListItemRenderer;
            if (!item) continue;
            const flexCols = item.flexColumns || [];
            const titleRuns = flexCols[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
            const title = titleRuns.map((r) => r.text).join('') || '';
            const subRuns = flexCols[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
            const rawSub = subRuns.map((r) => r.text).join('') || '';
            const subtitle = rawSub.replace(/^(Song|Video|Single|Album|EP)\s*•\s*/i, '');

            let videoId = item.playlistItemData?.videoId || null;
            if (!videoId) {
                const playNav =
                    item.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint;
                videoId = playNav?.watchEndpoint?.videoId || null;
            }
            if (!videoId) {
                for (const col of flexCols) {
                    for (const run of col.musicResponsiveListItemFlexColumnRenderer?.text?.runs || []) {
                        if (run.navigationEndpoint?.watchEndpoint?.videoId) {
                            videoId = run.navigationEndpoint.watchEndpoint.videoId;
                            break;
                        }
                    }
                    if (videoId) break;
                }
            }

            if (title && videoId) {
                results.push({
                    durationMs: null,
                    subtitle: subtitle || null,
                    thumbnailUrl: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
                    title,
                    videoId,
                });
            }
        }
    }

    const seen = new Set();
    const deduped = [];
    for (const r of results) {
        if (seen.has(r.videoId)) continue;
        seen.add(r.videoId);
        deduped.push(r);
    }
    return deduped;
}

app.get('/ytm/meta', async (req, res) => {
    const endpoint = req.query.endpoint;
    const query = req.query.q || req.query.query;
    const filter = req.query.filter;

    try {
        if (endpoint === 'search') {
            if (!query || typeof query !== 'string') {
                return res.json({
                    cache: { state: 'fresh' },
                    payload: { continuation: null, filter: filter || null, kind: 'search', query: '', results: [] },
                });
            }
            const results = await searchYtm(query);
            return res.json({
                cache: { state: 'fresh' },
                payload: {
                    continuation: null,
                    filter: filter || null,
                    kind: 'search',
                    query,
                    results,
                },
            });
        }

        if (endpoint === 'search-suggest') {
            if (!query || typeof query !== 'string') {
                return res.json({
                    cache: { state: 'fresh' },
                    payload: { input: '', kind: 'search-suggest', suggestions: [] },
                });
            }
            const suggestUrl = `https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&q=${encodeURIComponent(query)}`;
            const suggestRes = await fetch(suggestUrl);
            const text = await suggestRes.text();
            const jsonMatch = text.match(/window\.google\.ac\.h\((.*)\)/);
            let suggestions = [];
            if (jsonMatch && jsonMatch[1]) {
                const parsed = JSON.parse(jsonMatch[1]);
                suggestions = (parsed[1] || []).map((item) => item[0]);
            }
            return res.json({
                cache: { state: 'fresh' },
                payload: {
                    input: query,
                    kind: 'search-suggest',
                    suggestions,
                },
            });
        }

        // For chapters, counterpart, credits, lyrics, etc. not yet cached on relay:
        // Return 200 with missing: true so edgeMetaCall maps cleanly to not-found / fallback
        res.json({
            cache: { state: 'fresh' },
            missing: true,
        });
    } catch (err) {
        console.error('[/ytm/meta error]:', err.message);
        res.status(502).json({
            error: err.message,
            ok: false,
        });
    }
});

// Image proxy & canonical YouTube thumbnail helper
function formatThumbnails(rawThumbnails, videoId, baseUrl = '') {
    const list = (rawThumbnails || []).map(t => {
        let url = t.url || "";
        if (url.startsWith("//")) url = "https:" + url;
        if (url && (url.includes('googleusercontent.com') || url.includes('ggpht.com'))) {
            const prefix = baseUrl ? baseUrl.replace(/\/+$/, '') : '';
            url = `${prefix}/api/yt/thumb?url=${encodeURIComponent(url)}`;
        }
        return {
            url: url,
            width: t.width || 120,
            height: t.height || 120
        };
    });
    if (videoId) {
        list.push({
            url: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
            width: 480,
            height: 360
        });
    }
    return list;
}

function parseDurationToSeconds(durationStr) {
    if (!durationStr || typeof durationStr !== 'string') return 0;
    const parts = durationStr.trim().split(':');
    if (parts.length === 2) {
        return (parseInt(parts[0], 10) || 0) * 60 + (parseInt(parts[1], 10) || 0);
    } else if (parts.length === 3) {
        return (parseInt(parts[0], 10) || 0) * 3600 + (parseInt(parts[1], 10) || 0) * 60 + (parseInt(parts[2], 10) || 0);
    }
    return parseInt(durationStr, 10) || 0;
}

// Web UI Thumbnail Proxy (bypasses browser CORS / 429 rate-limiting on Google/YouTube CDNs)
app.get('/api/yt/thumb', async (req, res) => {
    try {
        const rawUrl = req.query.url;
        if (!rawUrl) return res.status(400).send("url parameter required");
        const upstream = await fetch(rawUrl, {
            headers: {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            }
        });
        if (!upstream.ok) {
            return res.status(upstream.status).send("Failed to fetch image");
        }
        const contentType = upstream.headers.get("content-type") || "image/jpeg";
        res.setHeader("Content-Type", contentType);
        res.setHeader("Cache-Control", "public, max-age=86400, no-transform");
        res.setHeader("Access-Control-Allow-Origin", "*");
        const buffer = await upstream.arrayBuffer();
        res.send(Buffer.from(buffer));
    } catch (e) {
        res.status(500).send(e.message);
    }
});

// Web UI API: YouTube Music Home Feed
app.get('/api/yt/home', async (req, res) => {
    try {
        const gl = req.query.gl || req.headers['x-region'] || "IN";
        const baseUrl = `http://${req.headers.host || 'localhost:3000'}`;
        const payload = {
            context: {
                client: {
                    clientName: "WEB_REMIX",
                    clientVersion: "1.20240401.01.00",
                    hl: "en",
                    gl: gl
                }
            },
            browseId: "FEmusic_home"
        };
        const response = await fetch("https://music.youtube.com/youtubei/v1/browse", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            },
            body: JSON.stringify(payload)
        });
        const data = await response.json();
        const tabs = data?.contents?.singleColumnBrowseResultsRenderer?.tabs;
        const sectionList = tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

        const shelves = [];
        for (const s of sectionList) {
            const shelf = s.musicCarouselShelfRenderer || s.musicImmersiveCarouselShelfRenderer;
            if (!shelf) continue;
            let title = shelf.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.map(r => r.text).join("") || "Featured";
            const numItemsPerColumn = shelf.numItemsPerColumn ? parseInt(shelf.numItemsPerColumn, 10) : null;
            const contents = [];
            for (const c of (shelf.contents || [])) {
                const item = c.musicTwoRowItemRenderer;
                if (item) {
                    const itemTitle = item.title?.runs?.map(r => r.text).join("") || "";
                    const itemSubtitle = item.subtitle?.runs?.map(r => r.text).join("") || "";
                    const videoId = item.navigationEndpoint?.watchEndpoint?.videoId || null;
                    const browseId = item.navigationEndpoint?.browseEndpoint?.browseId || null;
                    const playlistId = item.navigationEndpoint?.watchEndpoint?.playlistId || null;
                    const thumbnails = formatThumbnails(item.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoId, baseUrl);
                    contents.push({
                        title: itemTitle,
                        description: itemSubtitle,
                        videoId: videoId,
                        browseId: browseId,
                        playlistId: playlistId,
                        thumbnails: thumbnails,
                        artists: itemSubtitle ? [{ id: null, name: itemSubtitle }] : null,
                        album: null,
                        isExplicit: false,
                        views: null
                    });
                }
                const list = c.musicResponsiveListItemRenderer;
                if (list) {
                    const flexCols = list.flexColumns || [];
                    const itemTitle = flexCols[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || "";
                    const itemSubtitle = flexCols[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.map(r => r.text).join("") || "";
                    const videoId = list.playlistItemData?.videoId || list.doubleTapCommand?.watchEndpoint?.videoId || null;
                    const thumbnails = formatThumbnails(list.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoId, baseUrl);
                    contents.push({
                        title: itemTitle,
                        description: itemSubtitle,
                        videoId: videoId,
                        browseId: null,
                        playlistId: null,
                        thumbnails: thumbnails,
                        artists: itemSubtitle ? [{ id: null, name: itemSubtitle }] : null,
                        album: null,
                        isExplicit: false,
                        views: null
                    });
                }
            }
            if (contents.length > 0) {
                const isQuickPicks = numItemsPerColumn === 4 ||
                    title.toLowerCase().includes("quick pick") ||
                    title.toLowerCase().includes("song mix");
                if (isQuickPicks) {
                    title = "Quick picks";
                }
                shelves.push({
                    title: title,
                    subtitle: null,
                    contents: contents,
                    thumbnail: contents[0]?.thumbnails || null,
                    itemsPerColumn: isQuickPicks ? 4 : numItemsPerColumn
                });
            }
        }

        // Pinned Quick Picks guarantee: ensure Quick Picks is at index 0 and never shuffles
        const qpIdx = shelves.findIndex(s => s.itemsPerColumn === 4 || s.title === "Quick picks");
        if (qpIdx > 0) {
            const qp = shelves.splice(qpIdx, 1)[0];
            shelves.unshift(qp);
        } else if (qpIdx === -1 && shelves.length > 0) {
            const songShelfIdx = shelves.findIndex(s => s.contents && s.contents.length >= 8 && s.contents.every(c => c.videoId));
            if (songShelfIdx !== -1) {
                shelves[songShelfIdx].itemsPerColumn = 4;
                shelves[songShelfIdx].title = "Quick picks";
                if (songShelfIdx > 0) {
                    const qp = shelves.splice(songShelfIdx, 1)[0];
                    shelves.unshift(qp);
                }
            }
        }

        res.json({
            items: shelves,
            continuation: null
        });
    } catch (err) {
        console.error('[/api/yt/home error]:', err.message);
        res.status(500).json({ error: err.message, items: [] });
    }
});

// Web UI API: YouTube Music Songs Search
app.get('/api/yt/search', async (req, res) => {
    try {
        const query = req.query.q || '';
        if (!query.trim()) {
            return res.json([]);
        }
        const baseUrl = `http://${req.headers.host || 'localhost:3000'}`;
        const payload = {
            context: {
                client: {
                    clientName: "WEB_REMIX",
                    clientVersion: "1.20240401.01.00",
                    hl: "en",
                    gl: "IN"
                }
            },
            query: query,
            params: "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D"
        };
        const response = await fetch("https://music.youtube.com/youtubei/v1/search", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            },
            body: JSON.stringify(payload)
        });
        const data = await response.json();
        const contents = data?.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents;
        const shelf = contents?.[0]?.musicShelfRenderer;
        const songs = [];
        for (const item of (shelf?.contents || [])) {
            const r = item.musicResponsiveListItemRenderer;
            if (!r) continue;
            const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
            if (!videoId) continue;
            const flexColumns = r.flexColumns || [];
            const title = flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || "";
            const col2Runs = flexColumns[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
            let artistName = "Unknown Artist";
            let albumName = "";
            let duration = "";
            if (col2Runs.length > 0) {
                artistName = col2Runs[0]?.text || "Unknown Artist";
            }
            if (col2Runs.length >= 3) {
                albumName = col2Runs[2]?.text || "";
            }
            if (col2Runs.length >= 5) {
                duration = col2Runs[4]?.text || "";
            }
            const thumbs = formatThumbnails(r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoId, baseUrl);
            songs.push({
                videoId,
                title,
                artists: [{ id: null, name: artistName }],
                album: albumName ? { id: "", name: albumName } : null,
                duration,
                durationSeconds: parseDurationToSeconds(duration),
                thumbnails: thumbs,
                category: "Song",
                resultType: "Song",
                isExplicit: false,
                videoType: null,
                feedbackTokens: null,
                year: ""
            });
        }
        res.json(songs);
    } catch (err) {
        console.error('[/api/yt/search error]:', err.message);
        res.status(500).json({ error: err.message, songs: [] });
    }
});

// Web UI API: YouTube Music Next / Radio Queue
app.get('/api/yt/next', async (req, res) => {
    try {
        const videoId = req.query.videoId || null;
        let playlistId = req.query.playlistId || null;
        const continuation = req.query.continuation || null;
        const gl = req.query.gl || req.headers['x-region'] || "IN";
        const baseUrl = `http://${req.headers.host || 'localhost:3000'}`;

        if (!playlistId && videoId) {
            playlistId = "RDAMVM" + videoId;
        }

        const payload = {
            context: {
                client: {
                    clientName: "WEB_REMIX",
                    clientVersion: "1.20240401.01.00",
                    hl: "en",
                    gl: gl
                }
            },
            isAudioOnly: true
        };
        if (videoId) payload.videoId = videoId;
        if (playlistId) payload.playlistId = playlistId;
        if (continuation) payload.continuation = continuation;

        const response = await fetch("https://music.youtube.com/youtubei/v1/next", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            },
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        let rawContents = [];
        let nextContinuation = null;

        if (continuation) {
            const contQueue = data?.continuationContents?.playlistPanelContinuation;
            if (contQueue) {
                rawContents = contQueue.contents || [];
                nextContinuation = contQueue.continuations?.[0]?.nextRadioContinuationData?.continuation 
                    || contQueue.continuations?.[0]?.nextContinuationData?.continuation || null;
            }
        } else {
            const tabs = data?.contents?.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs;
            const queue = tabs?.[0]?.tabRenderer?.content?.musicQueueRenderer?.content?.playlistPanelRenderer;
            rawContents = queue?.contents || [];
            nextContinuation = queue?.continuations?.[0]?.nextRadioContinuationData?.continuation 
                || queue?.continuations?.[0]?.nextContinuationData?.continuation || null;
        }

        const tracks = [];
        for (const c of rawContents) {
            const item = c.playlistPanelVideoRenderer;
            if (!item) continue;
            const vId = item.videoId;
            if (!vId) continue;
            const title = item.title?.runs?.[0]?.text || "";
            const runs = item.longBylineText?.runs || item.shortBylineText?.runs || [];
            const artists = [];
            let album = null;
            let year = null;
            for (const r of runs) {
                const text = r.text?.trim();
                if (!text || text === "•" || text === "," || text === "&") continue;
                const browseId = r.navigationEndpoint?.browseEndpoint?.browseId;
                const pageType = r.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;
                if (pageType === "MUSIC_PAGE_TYPE_ARTIST" || (browseId && browseId.startsWith("UC"))) {
                    artists.push({ id: browseId, name: text });
                } else if (pageType === "MUSIC_PAGE_TYPE_ALBUM" || (browseId && browseId.startsWith("MPRE"))) {
                    album = { id: browseId, name: text };
                } else if (/^\d{4}$/.test(text)) {
                    year = text;
                } else if (artists.length === 0) {
                    artists.push({ id: null, name: text });
                }
            }
            const duration = item.lengthText?.runs?.[0]?.text || "";
            const rawThumbs = item.thumbnail?.thumbnails || [];
            const thumbnails = formatThumbnails(rawThumbs, vId, baseUrl);

            tracks.push({
                videoId: vId,
                title: title,
                artists: artists,
                album: album,
                duration: duration,
                durationSeconds: parseDurationToSeconds(duration),
                thumbnails: thumbnails,
                year: year
            });
        }

        res.json({
            tracks: tracks,
            continuation: nextContinuation
        });
    } catch (err) {
        console.error('[/api/yt/next error]:', err.message);
        res.status(500).json({ error: err.message, tracks: [], continuation: null });
    }
});

// Web UI API: Search Suggestions
app.get('/api/yt/suggest', async (req, res) => {
    try {
        const query = req.query.q || '';
        if (!query.trim()) {
            return res.json([]);
        }
        const response = await fetch(`https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&hl=en&gl=US&q=${encodeURIComponent(query)}&ds=yt`);
        const text = await response.text();
        const jsonMatch = text.match(/^[^(]*\((.*)\);?$/);
        const data = jsonMatch ? JSON.parse(jsonMatch[1]) : [];
        const queries = (data[1] || []).map(item => item[0]);
        res.json(queries);
    } catch (err) {
        console.error('[/api/yt/suggest error]:', err.message);
        res.status(500).json([]);
    }
});

// Web UI API: YouTube Music Browse (Playlists, Albums, Artists)
app.get('/api/yt/browse', async (req, res) => {
    try {
        const browseId = req.query.id || req.query.browseId;
        if (!browseId) {
            return res.status(400).json({ error: "browseId required" });
        }
        const baseUrl = `http://${req.headers.host || 'localhost:3000'}`;
        const payload = {
            context: {
                client: {
                    clientName: "WEB_REMIX",
                    clientVersion: "1.20240401.01.00",
                    hl: "en",
                    gl: "IN"
                }
            },
            browseId: browseId
        };
        const response = await fetch("https://music.youtube.com/youtubei/v1/browse", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/123.0.0.0"
            },
            body: JSON.stringify(payload)
        });
        const data = await response.json();

        // 1. Check for Artist page (musicImmersiveHeaderRenderer / musicVisualHeaderRenderer)
        const immersiveHeader = data.header?.musicImmersiveHeaderRenderer || data.header?.musicVisualHeaderRenderer;
        if (immersiveHeader) {
            const name = immersiveHeader.title?.runs?.map(r => r.text).join("") || "";
            const subs = immersiveHeader.subscriptionButton?.subscribeButtonRenderer?.subscriberCountText?.runs?.map(r => r.text).join("") || null;
            const thumbs = formatThumbnails(immersiveHeader.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, null, baseUrl);
            const sec = data?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];
            const songs = [];
            for (const s of sec) {
                const shelf = s.musicShelfRenderer || s.musicCarouselShelfRenderer;
                for (const item of (shelf?.contents || [])) {
                    const r = item.musicResponsiveListItemRenderer;
                    if (!r) continue;
                    const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
                    if (!videoId) continue;
                    const title = r.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || "";
                    const col2Runs = r.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
                    const artistName = col2Runs[0]?.text || name;
                    const albumName = col2Runs[2]?.text || "";
                    const duration = col2Runs[4]?.text || "";
                    const itemThumbs = formatThumbnails(r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoId, baseUrl);
                    songs.push({
                        videoId,
                        title,
                        artists: [{ id: null, name: artistName }],
                        album: albumName ? { id: "", name: albumName } : null,
                        duration,
                        durationSeconds: parseDurationToSeconds(duration),
                        thumbnails: itemThumbs.length > 0 ? itemThumbs : thumbs,
                        isExplicit: false,
                        isAvailable: true,
                        likeStatus: "INDIFFERENT",
                        category: "Song",
                        resultType: "Song",
                        videoType: null,
                        feedbackTokens: null,
                        year: null,
                        views: null
                    });
                }
            }
            return res.json({
                type: "artist",
                id: browseId,
                name: name,
                description: null,
                subscribers: subs,
                thumbnails: thumbs,
                songs: songs
            });
        }

        // 2. Playlist or Album (twoColumnBrowseResultsRenderer)
        const twoCol = data.contents?.twoColumnBrowseResultsRenderer;
        const headerSec = twoCol?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents?.[0];
        const respHeader = headerSec?.musicResponsiveHeaderRenderer;
        const title = respHeader?.title?.runs?.map(r => r.text).join("") || data.header?.musicDetailHeaderRenderer?.title?.runs?.map(r => r.text).join("") || "";
        const subtitle = respHeader?.subtitle?.runs?.map(r => r.text).join("") || "";
        const description = respHeader?.description?.musicDescriptionShelfRenderer?.description?.runs?.map(r => r.text).join("") || null;
        const thumbs = formatThumbnails(respHeader?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, null, baseUrl);

        const secContents = twoCol?.secondaryContents?.sectionListRenderer?.contents || [];
        const shelf = secContents[0]?.musicShelfRenderer || secContents[0]?.musicPlaylistShelfRenderer;
        const tracks = [];

        for (const item of (shelf?.contents || [])) {
            const r = item.musicResponsiveListItemRenderer;
            if (!r) continue;
            const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
            if (!videoId) continue;
            const flexColumns = r.flexColumns || [];
            const trackTitle = flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || "";
            const col2Runs = flexColumns[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
            let artistName = subtitle.split("•")[0]?.trim() || "Unknown Artist";
            let albumName = title;
            let duration = "";
            if (col2Runs.length > 0) {
                artistName = col2Runs[0]?.text || artistName;
            }
            if (col2Runs.length >= 3) {
                albumName = col2Runs[2]?.text || albumName;
            }
            if (col2Runs.length >= 5) {
                duration = col2Runs[4]?.text || "";
            }
            const itemThumbs = formatThumbnails(r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoId, baseUrl);
            tracks.push({
                videoId,
                title: trackTitle,
                artists: [{ id: null, name: artistName }],
                album: albumName ? { id: "", name: albumName } : null,
                duration,
                durationSeconds: parseDurationToSeconds(duration),
                thumbnails: itemThumbs.length > 0 ? itemThumbs : thumbs,
                isExplicit: false,
                isAvailable: true,
                likeStatus: "INDIFFERENT",
                category: "Song",
                resultType: "Song",
                videoType: null,
                feedbackTokens: null,
                year: null,
                views: null
            });
        }

        const isAlbum = browseId.startsWith("MPREb_");
        res.json({
            type: isAlbum ? "album" : "playlist",
            id: browseId,
            title,
            description,
            subtitle,
            thumbnails: thumbs,
            trackCount: tracks.length,
            tracks
        });
    } catch (err) {
        console.error('[/api/yt/browse error]:', err.message);
        res.status(500).json({ error: err.message, tracks: [] });
    }
});

app.get('/api/yt/lyrics', async (req, res) => {
    try {
        const { track, artist, duration } = req.query;
        if (!track && !artist) {
            return res.status(400).json({ error: true, message: 'track or artist query param required' });
        }

        const cleanTrack = (track || '').replace(/\s*\(.*?\)\s*/g, ' ').replace(/\s*\[.*?\]\s*/g, ' ').trim();
        const cleanArtist = (artist || '').split(/[,&]/)[0]?.trim() || (artist || '').trim();

        let data = null;
        const durSec = parseInt(duration, 10) || 0;
        let getUrl = `https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanTrack || track)}&artist_name=${encodeURIComponent(cleanArtist || artist)}`;
        if (durSec > 0) {
            getUrl += `&duration=${durSec}`;
        }

        try {
            const resp = await fetch(getUrl, {
                headers: { 'User-Agent': 'Streamify/2.6.0 (https://github.com/maxrave-dev/SimpMusic)' },
                signal: AbortSignal.timeout(6000)
            });
            if (resp.ok) {
                data = await resp.json();
            }
        } catch (_) {}

        if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
            try {
                const searchUrl = `https://lrclib.net/api/search?q=${encodeURIComponent((cleanTrack || track) + ' ' + (cleanArtist || artist))}`;
                const searchResp = await fetch(searchUrl, {
                    headers: { 'User-Agent': 'Streamify/2.6.0 (https://github.com/maxrave-dev/SimpMusic)' },
                    signal: AbortSignal.timeout(6000)
                });
                if (searchResp.ok) {
                    const searchData = await searchResp.json();
                    if (Array.isArray(searchData) && searchData.length > 0) {
                        data = searchData.find(item => item.syncedLyrics) || searchData[0];
                    }
                }
            } catch (_) {}
        }

        if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
            return res.json({
                error: true,
                syncType: null,
                lines: null
            });
        }

        if (data.syncedLyrics) {
            const timeRegex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/;
            const parsed = [];
            const rawLines = data.syncedLyrics.split('\n');
            for (const line of rawLines) {
                const m = timeRegex.exec(line);
                if (m) {
                    const min = parseInt(m[1], 10);
                    const sec = parseInt(m[2], 10);
                    const frac = m[3] || '0';
                    const ms = frac.length === 2 ? parseInt(frac, 10) * 10 : parseInt(frac, 10);
                    const totalMs = (min * 60 + sec) * 1000 + ms;
                    const words = m[4].trim();
                    parsed.push({ timeMs: totalMs, words });
                }
            }

            const lines = [];
            for (let i = 0; i < parsed.length; i++) {
                const current = parsed[i];
                const next = parsed[i + 1];
                const endMs = next ? next.timeMs : current.timeMs + 4000;
                lines.push({
                    startTimeMs: String(current.timeMs),
                    endTimeMs: String(endMs),
                    syllables: null,
                    words: current.words
                });
            }

            return res.json({
                error: false,
                syncType: 'LINE_SYNCED',
                lines
            });
        }

        const plainLines = (data.plainLyrics || '').split('\n').map(line => ({
            startTimeMs: '0',
            endTimeMs: '0',
            syllables: null,
            words: line.trim()
        })).filter(l => l.words.length > 0);

        return res.json({
            error: false,
            syncType: 'UNSYNCED',
            lines: plainLines
        });
    } catch (err) {
        console.error('[/api/yt/lyrics error]:', err.message);
        res.status(500).json({ error: true, syncType: null, lines: null });
    }
});

if (fs.existsSync(publicDir)) {
    app.get('*', (req, res, next) => {
        if (req.path.startsWith('/api/') || req.path.startsWith('/ytm/') || req.path.startsWith('/r')) {
            return next();
        }
        sendNoCacheIndex(res);
    });
}

app.listen(PORT, '0.0.0.0', () => {
    console.log(`Streamify Architecture v3 Relay listening on port ${PORT}`);
});
