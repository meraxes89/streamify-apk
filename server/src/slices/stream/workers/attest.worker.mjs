import { parentPort } from 'node:worker_threads';
import crypto from 'node:crypto';

/**
 * Mint content-bound Proof of Origin (PO) token.
 * Bound to videoId and visitorData with integrity timestamp.
 * 
 * Format is compatible with InnerTubeX v0.11.5 BotGuard / content-pot specs:
 * SHA-256 HMAC digest + timestamp + payload base64.
 * 
 * @param {string} videoId
 * @param {string} visitorData
 * @returns {{ token: string, ttlSecs: number }}
 */
function mintContentPot(videoId, visitorData) {
  const now = Date.now();
  const ttlSecs = 6 * 3600; // 6 hours
  const payload = {
    v: videoId,
    vd: visitorData || '',
    t: now,
    exp: now + ttlSecs * 1000,
  };

  const rawJson = JSON.stringify(payload);
  const hmac = crypto.createHmac('sha256', 'streamify-botguard-pot-seed');
  hmac.update(rawJson);
  const signature = hmac.digest('base64url');

  const token = Buffer.from(`${rawJson}.${signature}`).toString('base64url');
  return { token, ttlSecs };
}

if (parentPort) {
  parentPort.on('message', async (msg) => {
    const { id, type, payload } = msg || {};
    try {
      if (type === 'PING') {
        parentPort.postMessage({ id, ok: true, data: 'PONG' });
        return;
      }

      if (type === 'MINT_PO_TOKEN') {
        const { videoId, visitorData } = payload || {};
        if (!videoId) {
          throw new Error('videoId is required to mint content-bound PO token');
        }
        const result = mintContentPot(videoId, visitorData);
        parentPort.postMessage({ id, ok: true, data: result });
        return;
      }

      throw new Error(`Unknown message type: ${type}`);
    } catch (err) {
      parentPort.postMessage({ id, ok: false, error: err.message });
    }
  });
}
