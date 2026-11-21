import { parentPort } from 'node:worker_threads';

/**
 * Balanced-brace scanner to slice a nested JSON object out of a large JSON string.
 * Avoids parsing the entire 150 KiB player payload on the V8 main loop.
 * 
 * @param {string} raw
 * @param {string} key - e.g. '"streamingData"' or '"playabilityStatus"'
 * @returns {string|null} - Subtree substring (e.g. '{"adaptiveFormats":...}')
 */
export function sliceObject(raw, key) {
  const keyIdx = raw.indexOf(key);
  if (keyIdx === -1) return null;

  const braceStart = raw.indexOf('{', keyIdx + key.length);
  if (braceStart === -1) return null;

  let depth = 0;
  let inString = false;
  let escape = false;

  for (let i = braceStart; i < raw.length; i++) {
    const ch = raw[i];

    if (escape) {
      escape = false;
      continue;
    }

    if (ch === '\\') {
      escape = true;
      continue;
    }

    if (ch === '"') {
      inString = !inString;
      continue;
    }

    if (!inString) {
      if (ch === '{') {
        depth++;
      } else if (ch === '}') {
        depth--;
        if (depth === 0) {
          return raw.slice(braceStart, i + 1);
        }
      }
    }
  }

  return null;
}

/**
 * Selective extraction of player payload:
 * - streamingData (~15 KiB)
 * - playabilityStatus (~1 KiB)
 * - videoDetails (~2 KiB)
 * - visitorData (~100 B)
 * 
 * @param {string} rawJson
 * @returns {Object}
 */
export function extractPlayerSubtrees(rawJson) {
  if (!rawJson || typeof rawJson !== 'string') {
    throw new Error('Invalid rawJson string');
  }

  // 1. visitorData (Regex extraction)
  const vdMatch = rawJson.match(/"visitorData"\s*:\s*"([^"]+)"/);
  const visitorData = vdMatch ? vdMatch[1] : null;

  // 2. playabilityStatus (Selective slice)
  const psStr = sliceObject(rawJson, '"playabilityStatus"');
  const playabilityStatus = psStr ? JSON.parse(psStr) : null;

  // 3. streamingData (Selective slice)
  const sdStr = sliceObject(rawJson, '"streamingData"');
  const streamingData = sdStr ? JSON.parse(sdStr) : null;

  // 4. videoDetails (Selective slice)
  const vdObjStr = sliceObject(rawJson, '"videoDetails"');
  const videoDetails = vdObjStr ? JSON.parse(vdObjStr) : null;

  return {
    visitorData,
    playabilityStatus,
    streamingData,
    videoDetails,
  };
}

if (parentPort) {
  parentPort.on('message', async (msg) => {
    const { id, type, payload } = msg || {};
    try {
      if (type === 'PING') {
        parentPort.postMessage({ id, ok: true, data: 'PONG' });
        return;
      }

      if (type === 'EXTRACT_PLAYER') {
        const { rawText } = payload || {};
        const result = extractPlayerSubtrees(rawText);
        parentPort.postMessage({ id, ok: true, data: result });
        return;
      }

      throw new Error(`Unknown parser worker type: ${type}`);
    } catch (err) {
      parentPort.postMessage({ id, ok: false, error: err.message });
    }
  });
}
