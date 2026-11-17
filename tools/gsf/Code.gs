/**
 * Streamify GSF — the Google-ASN egress (G5, tools/gsf).
 *
 * THE PROBLEM THIS SCRIPT SOLVES (front-end/decisions/G5-gsf-unburnable-lane.md):
 * a foreign-origin browser page cannot call youtubei.googleapis.com directly
 * (its CORS allowlist is youtube.com / music.youtube.com / script.google.com /
 * docs.google.com / sites.google.com — probe-measured 2026-11-14), so every
 * control-plane call funnels through the shared Cloudflare Worker relay —
 * AS13335, one datacenter egress for all users, bot-walled for licensed music
 * after 1–2 plays. NO amount of hygiene fixes an IP class.
 *
 * THE LEVER: script.google.com IS on that CORS allowlist, and an Apps Script
 * web app (a) serves cross-origin JSON to any browser (deployed "Anyone") and
 * (b) makes its outbound calls through UrlFetchApp — GOOGLE'S OWN ASN 15169,
 * the egress class YouTube's own first-party traffic rides, quota'd per
 * deploying account (20,000 UrlFetch calls/day on a free consumer account —
 * hundreds of tracks/day, not a shared abusive pool). Deploy once, paste the
 * /exec URL into Streamify's "Engine Egress" panel: every player/config/search
 * call now egresses from Google, and media keeps flowing direct from the
 * user's own IP (googlevideo authorizes cross-IP fetches — the W2.0
 * measurement).
 *
 * THE CONTRACT (v1 — GET-envelope, no preflight anywhere):
 *   GET {execUrl}?ping=1
 *       → { ok: true, eip: "<egress ip>", ts: <ms> }
 *   GET {execUrl}?q=<urlencoded JSON>
 *       envelope: { u: "<https url>", m: "get"|"post",
 *                   h: { "<header>": "<value>" },
 *                   b: "<body>", e: "t"|"b64" }   // e: body encoding
 *       → { ok: true, s: <status>, h: {headers}, b: "<body>", e: "t"|"b64",
 *           eip: "<egress ip>" }
 *       → { ok: false, error: "<reason>" }       // envelope/allowlist errors
 *   The response body is TEXT when the upstream content-type is textual
 *   (json/text/xml/javascript), base64 otherwise — binary-safe for the SABR
 *   UMP protobufs.
 *
 * SECURITY POSTURE: a deliberately DUMB, allowlisted pipe — Google hosts
 * only (youtubei/jnn-pa googleapis, *.googlevideo, www.youtube.com/youtubei),
 * GET+POST only, no auth material is ever accepted or attached, response
 * bodies capped. It cannot be used as an open proxy, a spam relay, or a
 * credential forwarder.
 */

// ---------------------------------------------------------------------------
// Policy
// ---------------------------------------------------------------------------

/** Exact hosts the proxy will fetch (plus *.googlevideo.com by suffix). */
var ALLOWED_HOSTS = [
  'youtubei.googleapis.com',
  'jnn-pa.googleapis.com',
  'www.youtube.com',
  'music.youtube.com'
];

/** Apps Script V8 has no WHATWG `URL` — parse the envelope target with a
 * regex. Returns { host, path } for an absolute https URL, or null. */
var HTTPS_URL_RE = /^https:\/\/([a-zA-Z0-9.-]+)(:[0-9]+)?([\/?#][^\s]*)?$/;
function parseHttpsUrl_(u) {
  if (typeof u !== 'string') return null;
  var m = HTTPS_URL_RE.exec(u);
  if (!m) return null;
  var host = m[1].toLowerCase();
  var path = m[3] || '/';
  return { host: host, path: path };
}

/** The one non-Google host fetched — the egress-IP echo for diagnostics. */
var IP_ECHO_URL = 'https://api.ipify.org';

/** Envelope guard: the /exec GET query stays comfortably under URL limits. */
var MAX_ENVELOPE_CHARS = 60000;

/** Upstream response guard: player JSON is tens of KB; SABR bursts ~1.5 MB. */
var MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

/** Methods the pipe will execute. */
var ALLOWED_METHODS = { get: 'get', post: 'post' };

// ---------------------------------------------------------------------------
// The web-app entry point
// ---------------------------------------------------------------------------

function doGet(e) {
  try {
    if (e && e.parameter && e.parameter.ping !== undefined) {
      return json({ ok: true, eip: egressIp_(), ts: Date.now() });
    }
    if (e && e.parameter && typeof e.parameter.q === 'string') {
      if (e.parameter.q.length > MAX_ENVELOPE_CHARS) {
        return json({ ok: false, error: 'envelope too large (' + e.parameter.q.length + ' chars)' });
      }
      var req;
      try {
        req = JSON.parse(e.parameter.q);
      } catch (parseErr) {
        return { ok: false, error: 'envelope is not valid JSON' };
      }
      return json(proxy_(req));
    }
    return json({
      ok: false,
      error: 'usage: ?ping=1 | ?q={"u":..,"m":..,"h":..,"b":..,"e":"t|b64"}',
      eip: egressIp_()
    });
  } catch (outer) {
    return json({ ok: false, error: 'gsf: ' + String(outer) });
  }
}

// ---------------------------------------------------------------------------
// The proxy core
// ---------------------------------------------------------------------------

function proxy_(req) {
  if (req === null || typeof req !== 'object') {
    return { ok: false, error: 'envelope must be an object' };
  }
  if (typeof req.u !== 'string' || req.u.length === 0) {
    return { ok: false, error: 'envelope missing u (url)' };
  }
  var parsed = parseHttpsUrl_(req.u);
  if (parsed === null) {
    return { ok: false, error: 'envelope u is not a URL' };
  }
  if (!hostAllowed_(parsed)) {
    return { ok: false, error: 'host not allowed: ' + parsed.host };
  }
  var method = ALLOWED_METHODS[String(req.m || 'get').toLowerCase()];
  if (method === undefined) {
    return { ok: false, error: 'method not allowed (get|post)' };
  }
  var headers = {};
  if (req.h !== undefined && req.h !== null) {
    if (typeof req.h !== 'object') {
      return { ok: false, error: 'envelope h must be an object' };
    }
    for (var name in req.h) {
      if (Object.prototype.hasOwnProperty.call(req.h, name)) {
        var lower = String(name).toLowerCase();
        // Authorization/credentialed headers are refused outright — this
        // pipe is zero-credential by design and stays that way.
        if (lower === 'authorization' || lower === 'cookie') {
          return { ok: false, error: 'credential header refused: ' + name };
        }
        headers[name] = String(req.h[name]);
      }
    }
  }
  var payload = null;
  if (req.b !== undefined && req.b !== null) {
    if (typeof req.b !== 'string') {
      return { ok: false, error: 'envelope b must be a string' };
    }
    if (String(req.e || 't') === 'b64') {
      try {
        payload = Utilities.base64Decode(req.b);
      } catch (b64Err) {
        return { ok: false, error: 'envelope b is not valid base64' };
      }
    } else {
      payload = req.b;
    }
  }
  var options = {
    method: method,
    headers: headers,
    muteHttpExceptions: true,
    followRedirects: false, // 3xx surfaces honestly (Location rides the headers)
    validateHttpsCertificates: true
  };
  if (payload !== null) {
    options.payload = payload;
  }
  var resp;
  try {
    resp = UrlFetchApp.fetch(req.u, options);
  } catch (fetchErr) {
    return { ok: false, error: 'urlfetch: ' + String(fetchErr) };
  }
  var outHeaders = {};
  var raw = resp.getHeaders() || {};
  for (var hname in raw) {
    if (Object.prototype.hasOwnProperty.call(raw, hname)) {
      outHeaders[String(hname)] = String(raw[hname]);
    }
  }
  var content = resp.getContent() || [];
  if (content.length > MAX_RESPONSE_BYTES) {
    return { ok: false, error: 'upstream body too large (' + content.length + ' bytes)' };
  }
  var ctype = String(
    outHeaders['Content-Type'] || outHeaders['content-type'] || ''
  ).toLowerCase();
  var textual = /json|text|javascript|xml/.test(ctype);
  var body;
  var enc;
  if (textual) {
    body = resp.getContentText('UTF-8');
    enc = 't';
  } else {
    body = Utilities.base64Encode(content);
    enc = 'b64';
  }
  return {
    ok: true,
    s: resp.getResponseCode(),
    h: outHeaders,
    b: body,
    e: enc,
    eip: egressIp_()
  };
}

/** The allowlist: exact googleapis hosts, *.googlevideo.com, www /youtubei/*. */
function hostAllowed_(parsed) {
  var host = parsed.host;
  for (var i = 0; i < ALLOWED_HOSTS.length; i++) {
    if (host === ALLOWED_HOSTS[i]) {
      // www.youtube.com and music.youtube.com are allowed for the /youtubei/* API surface only.
      if (host === 'www.youtube.com' || host === 'music.youtube.com') {
        return parsed.path.indexOf('/youtubei/') === 0;
      }
      return true;
    }
  }
  return host === 'googlevideo.com' || host.lastIndexOf('.googlevideo.com') === host.length - '.googlevideo.com'.length;
}

// ---------------------------------------------------------------------------
// The egress-IP echo (diagnostics: "which world am I in")
// ---------------------------------------------------------------------------

/** The script's UrlFetch egress IP, cached 10 minutes (quota hygiene). */
function egressIp_() {
  var cache = CacheService.getScriptCache();
  var hit = cache.get('gsf.eip');
  if (hit !== null && hit !== '') {
    return hit;
  }
  try {
    var resp = UrlFetchApp.fetch(IP_ECHO_URL, { muteHttpExceptions: true });
    var ip = resp.getContentText().trim();
    if (/^[0-9a-fA-F.:]{3,45}$/.test(ip)) {
      cache.put('gsf.eip', ip, 600); // 10 min
      return ip;
    }
    return 'unknown';
  } catch (ipErr) {
    return 'unknown';
  }
}

/** Serialize one JSON response (the /exec content type is application/json). */
function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
