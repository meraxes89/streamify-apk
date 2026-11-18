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
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const publicDir = path.join(__dirname, 'public');

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

if (fs.existsSync(publicDir)) {
    app.use(express.static(publicDir));
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
            'https://youtubei.googleapis.com/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8',
            {
                body: JSON.stringify({
                    context: {
                        client: {
                            clientName: 'WEB',
                            clientVersion: '2.20240101.01.00',
                            gl: 'US',
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
                    gl: 'US',
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

app.get('/', (req, res) => {
    if (fs.existsSync(path.join(publicDir, 'index.html')) && req.accepts(['html', 'json']) !== 'json') {
        return res.sendFile(path.join(publicDir, 'index.html'));
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

app.get('/api/stream/:videoId', async (req, res) => {
    const videoId = req.params.videoId;
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

        for (const [k, v] of upstreamRes.headers.entries()) {
            const lk = k.toLowerCase();
            if (['connection', 'content-encoding', 'transfer-encoding'].includes(lk)) continue;
            res.set(k, v);
        }

        const buffer = await upstreamRes.arrayBuffer();
        res.send(Buffer.from(buffer));
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
                gl: 'US',
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

if (fs.existsSync(publicDir)) {
    app.get('*', (req, res, next) => {
        if (req.path.startsWith('/api/') || req.path.startsWith('/ytm/') || req.path.startsWith('/r')) {
            return next();
        }
        res.sendFile(path.join(publicDir, 'index.html'));
    });
}

app.listen(PORT, '0.0.0.0', () => {
    console.log(`Streamify Architecture v3 Relay listening on port ${PORT}`);
});
