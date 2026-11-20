import { parentPort } from 'node:worker_threads';
import vm from 'node:vm';

// In-worker cache for compiled/parsed player transforms
const playerCache = new Map();

/**
 * Execute signature deciphering operations.
 * @param {string} sig - Input signature
 * @param {Array<{op: string, arg?: number}>} ops - Transformation operations
 * @returns {string} Deciphered signature
 */
function applySignatureOps(sig, ops) {
  let chars = sig.split('');
  for (const { op, arg } of ops) {
    if (op === 'reverse') {
      chars.reverse();
    } else if (op === 'splice') {
      chars.splice(0, arg || 0);
    } else if (op === 'swap') {
      const idx = (arg || 0) % chars.length;
      const tmp = chars[0];
      chars[0] = chars[idx];
      chars[idx] = tmp;
    }
  }
  return chars.join('');
}

/**
 * Solve nsig using sandboxed VM.
 * @param {string} n - Input n parameter
 * @param {string} nsigCode - JavaScript function source taking 'n' and returning solved string
 * @returns {string} Solved n parameter
 */
function solveNsigInVm(n, nsigCode) {
  // Hardened sandbox context without process, require, or globals
  const sandbox = {
    n,
    result: null,
  };
  const context = vm.createContext(sandbox);
  const script = new vm.Script(`
    try {
      const fn = (${nsigCode});
      result = fn(n);
    } catch (e) {
      result = n;
    }
  `);

  script.runInContext(context, {
    timeout: 1500, // 1.5s hard execution timeout
    breakOnSigint: true,
  });

  return sandbox.result || n;
}

/**
 * Extract signature transformation functions from player JS source.
 * @param {string} playerJs
 * @returns {Array<{op: string, arg?: number}>|null}
 */
function extractSignatureOperations(playerJs) {
  if (!playerJs) return null;
  try {
    // Standard YouTube pattern: a=a.split("");XYZ.reverse(a,2);XYZ.swap(a,14);...return a.join("")
    const fnMatch = playerJs.match(
      /\b[a-zA-Z0-9_$]+\s*=\s*function\([a-zA-Z0-9_$]+\)\s*\{\s*[a-zA-Z0-9_$]+\s*=\s*[a-zA-Z0-9_$]+\.split\(""\);([\s\S]*?)\.join\(""\)\s*\}/
    );
    if (!fnMatch) return null;

    const body = fnMatch[1];
    const objMatch = body.match(/([a-zA-Z0-9_$]+)\.[a-zA-Z0-9_$]+\(/);
    if (!objMatch) return null;
    const objName = objMatch[1];

    // Find object definition: var XYZ={reverse:function(a){a.reverse()},...};
    const objDefRegex = new RegExp(`var\\s+${objName}\\s*=\\s*\\{([\\s\\S]*?)\\};`);
    const objDefMatch = playerJs.match(objDefRegex);
    if (!objDefMatch) return null;

    const objBody = objDefMatch[1];
    const methods = {};
    const methodMatches = objBody.matchAll(/([a-zA-Z0-9_$]+)\s*:\s*function\([^)]*\)\s*\{([^}]*)\}/g);
    for (const m of methodMatches) {
      const name = m[1];
      const mBody = m[2];
      if (mBody.includes('reverse')) {
        methods[name] = 'reverse';
      } else if (mBody.includes('splice')) {
        methods[name] = 'splice';
      } else {
        methods[name] = 'swap';
      }
    }

    // Now extract the sequence of calls
    const ops = [];
    const callMatches = body.matchAll(new RegExp(`${objName}\\.([a-zA-Z0-9_$]+)\\([^,]+(?:,\\s*(\\d+))?\\)`, 'g'));
    for (const c of callMatches) {
      const actionName = c[1];
      const arg = c[2] ? parseInt(c[2], 10) : 0;
      const op = methods[actionName] || 'swap';
      ops.push({ op, arg });
    }

    return ops.length > 0 ? ops : null;
  } catch {
    return null;
  }
}

/**
 * Extract nsig solver function from player JS source.
 * @param {string} playerJs
 * @returns {string|null}
 */
function extractNsigFunction(playerJs) {
  if (!playerJs) return null;
  try {
    // Pattern for ncode / nsig solver function
    const match = playerJs.match(
      /\b([a-zA-Z0-9_$]+)\s*=\s*function\([a-zA-Z0-9_$]+\)\s*\{\s*var\s+[a-zA-Z0-9_$]+=[a-zA-Z0-9_$]+\.split\(""\)[\s\S]*?return\s+[a-zA-Z0-9_$]+\.join\(""\)\s*\}/
    );
    if (match) {
      return match[0].substring(match[0].indexOf('=') + 1).trim();
    }
  } catch {}
  return null;
}

if (parentPort) {
  parentPort.on('message', async (msg) => {
    const { id, type, payload } = msg || {};
    try {
      if (type === 'PING') {
        parentPort.postMessage({ id, ok: true, data: 'PONG' });
        return;
      }

      if (type === 'APPLY_SIG_OPS') {
        const { sig, ops } = payload;
        const result = applySignatureOps(sig, ops);
        parentPort.postMessage({ id, ok: true, data: result });
        return;
      }

      if (type === 'SOLVE_NSIG') {
        const { n, nsigCode } = payload;
        const result = solveNsigInVm(n, nsigCode);
        parentPort.postMessage({ id, ok: true, data: result });
        return;
      }

      if (type === 'PARSE_PLAYER') {
        const { hash, playerJs } = payload;
        const ops = extractSignatureOperations(playerJs);
        const nsigCode = extractNsigFunction(playerJs);
        playerCache.set(hash, { ops, nsigCode });
        parentPort.postMessage({
          id,
          ok: true,
          data: { hash, ops, nsigCode: Boolean(nsigCode) },
        });
        return;
      }

      if (type === 'DECIPHER_URL') {
        const { cipherString, ops, nsigCode } = payload;
        const params = new URLSearchParams(cipherString);
        let targetUrl = params.get('url');
        const s = params.get('s');
        const sp = params.get('sp') || 'sig';

        if (targetUrl && s && ops) {
          const sig = applySignatureOps(s, ops);
          const parsed = new URL(targetUrl);
          parsed.searchParams.set(sp, sig);

          const n = parsed.searchParams.get('n');
          if (n && nsigCode) {
            const solvedN = solveNsigInVm(n, nsigCode);
            parsed.searchParams.set('n', solvedN);
          }
          targetUrl = parsed.toString();
        }

        parentPort.postMessage({ id, ok: true, data: targetUrl });
        return;
      }

      throw new Error(`Unknown message type: ${type}`);
    } catch (err) {
      parentPort.postMessage({ id, ok: false, error: err.message });
    }
  });
}
