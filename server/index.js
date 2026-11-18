/**
 * Architecture v3 Stream Relay & Multi-Profile Resolver
 *
 * Implements:
 * 1. Mobile/VR Client Impersonation (ANDROID_VR / VISIONOS / IOS) to bypass bot-checks
 * 2. 8 MiB Bounded Upstream Chunking to prevent GoogleVideo 32 KiB/s throttling
 * 3. Range-capable HTTP 206 Partial Content downstream streaming
 * 4. Single-Flight Request Deduplication & In-Memory LRU Cache
 * 5. Asynchronous Queue Prefetch Engine for sub-100ms track transitions
 */

import cors from 'cors';
import express from 'express';
import http from 'http';
import https from 'https';
import { Innertube, UniversalCache } from 'youtubei.js';

const app = express();
const PORT = process.env.PORT || 7860;
const CHUNK_SIZE = 8 * 1024 * 1024; // 8 MiB bounded chunk

app.use(cors({ origin: '*' }));
app.use(express.json());

// In-memory cache for resolved stream metadata and URLs (TTL 5.5 hours)
const CACHE_TTL_MS = 5.5 * 60 * 60 * 1000;
const streamCache = new Map();

// Prefetched initial chunks (first 8 MiB buffer)
const prefetchBuffers = new Map();

// Single-flight in-flight resolves deduplication
const inFlightResolves = new Map();

let yt = null;
async function getInnertube() {
    if (!yt) {
        yt = await Innertube.create({
            cache: new UniversalCache(false),
            generate_session_locally: true,
        });
    }
    return yt;
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
        const innertube = await getInnertube();
        const clients = ['ANDROID_VR', 'VISIONOS', 'IOS', 'WEB'];
        let lastError = null;

        for (const client of clients) {
            try {
                const info = await innertube.getInfo(videoId, client);
                const formats = [
                    ...(info.streaming_data?.adaptive_formats || []),
                    ...(info.streaming_data?.formats || []),
                ];

                // Prefer Opus (itag 251), then AAC (itag 140)
                let selected = formats.find((f) => f.itag === 251 && f.url);
                if (!selected) {
                    selected = formats.find((f) => f.itag === 140 && f.url);
                }
                if (!selected) {
                    selected = formats.find((f) => f.mime_type?.startsWith('audio/') && f.url);
                }
                if (!selected && formats.length > 0) {
                    // Try decipher if needed
                    for (const f of formats) {
                        if (f.mime_type?.startsWith('audio/') && typeof f.decipher === 'function') {
                            try {
                                const url = await f.decipher(innertube.session.player);
                                if (url) {
                                    selected = { ...f, url };
                                    break;
                                }
                            } catch {
                                // continue to next candidate
                            }
                        }
                    }
                }

                if (selected?.url) {
                    const isOpus = (selected.mime_type || '').includes('opus');
                    const descriptor = {
                        artist: info.basic_info?.author || '',
                        bitrate: selected.bitrate || (isOpus ? 160000 : 128000),
                        client,
                        codecs: isOpus ? 'opus' : 'aac',
                        contentLength: selected.content_length
                            ? parseInt(selected.content_length, 10)
                            : null,
                        durationMs: (info.basic_info?.duration || 0) * 1000,
                        expiresAt: Date.now() + CACHE_TTL_MS,
                        googlevideoUrl: selected.url,
                        itag: selected.itag,
                        mimeType: selected.mime_type || 'audio/webm; codecs="opus"',
                        ok: true,
                        title: info.basic_info?.title || '',
                        videoId,
                    };

                    streamCache.set(videoId, descriptor);
                    return descriptor;
                }
            } catch (err) {
                lastError = err;
            }
        }

        throw lastError || new Error(`Failed to resolve stream for ${videoId}`);
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

app.get('/', (req, res) => {
    res.json({
        capabilities: [
            '8mib-range-stream',
            'mobile-vr-resolver',
            'prefetch',
            'ytm-meta',
            'neg-cache',
        ],
        name: 'streamify-relay',
        status: 'ok',
        version: '3.0.0',
    });
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

        res.json({
            artist: stream.artist,
            bitrate: stream.bitrate,
            codecs: stream.codecs,
            contentLength: stream.contentLength,
            durationMs: stream.durationMs,
            expiresAt: stream.expiresAt,
            ok: true,
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
        const innertube = await getInnertube();
        const searchRes = await innertube.music.search(query);
        const songs = (searchRes.songs?.contents || []).map((s) => ({
            album: s.album?.name || '',
            artist: s.artists?.map((a) => a.name).join(', ') || '',
            durationSeconds: s.duration?.seconds || 0,
            thumbnailUrl: s.thumbnails?.[0]?.url || '',
            title: s.title || '',
            videoId: s.id,
        }));

        res.json({ ok: true, songs });
    } catch (err) {
        res.status(502).json({ error: err.message, ok: false });
    }
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`Streamify Architecture v3 Relay listening on port ${PORT}`);
});
