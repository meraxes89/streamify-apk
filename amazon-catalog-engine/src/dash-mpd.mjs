/**
 * Zero-Dependency DASH MPD Parser (Amazon Music delivery dialect)
 *
 * getDashManifestsV2 (FLAC/Opus path, musicDashVersionList SIREN_KATANA +
 * contentProtectionList TRACK_PSSH) returns the manifest INLINE:
 *
 *   { contentResponseList: [ { manifest: "<MPD xml string>", cdnType,
 *       substituteAsin, contentResponseStatusCode, contentResponseStatusMessage } ] }
 *
 * The production player (web-hornet Vinyl engine) parses that XML with its own
 * dashManifestXmlRules. Everything the solver needs was decoded from the
 * production bundle and is implemented here with zero dependencies:
 *
 *   MPD@type/@mediaPresentationDuration/@profiles, BaseURL,
 *   Period/AdaptationSet@contentType/@mimeType/@lang,
 *   AdaptationSet/Representation{@id,@codecs,@audioSamplingRate,@bandwidth,@height},
 *   SupplementalProperty{schemeIdUri,value} with the Amazon dialect:
 *       amz-music:bitDepth   = "16" | "24"
 *       amz-music:trackType   = "UHD" | "HD" | "SD" | "LD" | "3D"
 *   ContentProtection{schemeIdUri,value,cenc:default_KID, cenc:pssh base64}
 *   SegmentTemplate{@initialization,@media,@timescale,@duration,@startNumber,
 *                   @presentationTimeOffset, SegmentTimeline/S}
 *   SegmentList/SegmentURL@media, SegmentBase/Initialization@range
 *
 * Codec vocabulary (production enum): flac, opus, mp4a.40.2 (AAC-LC),
 * mp4a.40.5 (AAC-HE), ec-3, ac-4.02.02.00.
 * Quality ladder (production enum): trackType + samplingRate ->
 *   UHD192 / UHD96 / UHD48 / UHD44 (24-bit Ultra HD FLAC family)
 *   HD192 / HD96 / HD48 / HD44    (16-bit HD family)
 */

/* ------------------------------------------------------------------ */
/* Minimal XML tokenizer/parser (element + attribute + text subset)   */
/* ------------------------------------------------------------------ */

const ENTITY_MAP = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", apos2: "'" };

function decodeEntities(text) {
  if (text.indexOf('&') === -1) return text;
  return text
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&(amp|lt|gt|quot|apos);/g, (m, name) => ENTITY_MAP[name] ?? m);
}

function localName(name) {
  const i = name.indexOf(':');
  return i === -1 ? name : name.slice(i + 1);
}

/**
 * Parses an XML document into { name(local), attrs, children, text } trees.
 * Comments, PIs, doctype and CDATA boundaries are tolerated (CDATA body kept
 * as text). This subset is sufficient for DASH MPD documents.
 *
 * @param {string} xml
 * @returns {{name:string, attrs:Object<string,string>, children:Array, text:string}}
 */
export function parseXml(xml) {
  if (typeof xml !== 'string' || !xml.trim()) {
    throw new Error('parseXml: empty document');
  }
  const doc = { name: '#document', attrs: {}, children: [], text: '' };
  const stack = [doc];
  let i = 0;
  const n = xml.length;

  const skipUntil = (needle) => {
    const end = xml.indexOf(needle, i);
    i = end === -1 ? n : end + needle.length;
  };

  while (i < n) {
    const lt = xml.indexOf('<', i);
    if (lt === -1) {
      stack[stack.length - 1].text += xml.slice(i);
      break;
    }
    if (lt > i) stack[stack.length - 1].text += decodeEntities(xml.slice(i, lt));
    i = lt;

    if (xml.startsWith('<!--', i)) { skipUntil('-->'); continue; }
    if (xml.startsWith('<?', i)) { skipUntil('?>'); continue; }
    if (xml.startsWith('<!', i)) {
      if (xml.startsWith('<![CDATA[', i)) {
        const end = xml.indexOf(']]>', i);
        stack[stack.length - 1].text += xml.slice(i + 9, end === -1 ? n : end);
        i = end === -1 ? n : end + 3;
        continue;
      }
      skipUntil('>');   // DOCTYPE and friends
      continue;
    }

    // element
    const gt = xml.indexOf('>', i);
    if (gt === -1) throw new Error('parseXml: unterminated element');
    let rawTag = xml.slice(i + 1, gt);
    const selfClosing = rawTag.endsWith('/');
    if (selfClosing) rawTag = rawTag.slice(0, -1);
    i = gt + 1;

    if (rawTag.startsWith('/')) {
      // closing tag: pop (tolerate stray closes)
      if (stack.length > 1) stack.pop();
      continue;
    }

    if (rawTag.startsWith('?') || rawTag.startsWith('!')) continue; // tolerate leftovers

    const tagMatch = rawTag.match(/^([^\s/>]+)((?:\s+[^\s=]+="[^"]*"|\s+[^\s=]+='[^']*')*)\s*$/);
    if (!tagMatch) throw new Error(`parseXml: malformed tag <${rawTag}>`);
    const name = localName(tagMatch[1]);
    const attrs = {};
    if (tagMatch[2]) {
      const attrRe = /([^\s=]+)="([^"]*)"|([^\s=]+)='([^']*)'/g;
      let m;
      while ((m = attrRe.exec(tagMatch[2])) !== null) {
        const key = localName(m[1] ?? m[3]);
        attrs[key] = decodeEntities(m[2] ?? m[4] ?? '');
      }
    }

    const node = { name, attrs, children: [], text: '' };
    stack[stack.length - 1].children.push(node);
    if (!selfClosing) stack.push(node);
  }

  return doc;
}

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

function children(node, name) {
  return node.children.filter(c => c.name === name);
}
function firstChild(node, name) {
  return node.children.find(c => c.name === name) || null;
}
function toNumber(v) {
  if (v == null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

/** ISO8601 duration (PT#H#M#S.f) / plain seconds -> seconds */
export function parseDuration(value) {
  if (value == null) return null;
  const str = String(value).trim();
  if (/^-?\d+(\.\d+)?$/.test(str)) return Number(str);
  const m = str.match(/^P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/);
  if (!m) return null;
  const [, Y, Mo, D, H, Mi, S] = m;
  return (+(Y || 0) * 365.25 * 86400) + (+(Mo || 0) * 30.44 * 86400) +
    (+(D || 0) * 86400) + (+(H || 0) * 3600) + (+(Mi || 0) * 60) + (+(S || 0));
}

/* ------------------------------------------------------------------ */
/* Amazon quality ladder (decoded from production bundle)             */
/* ------------------------------------------------------------------ */

/**
 * trackType + samplingRate -> production tier label.
 * UHD/HD ladder is bit-depth independent; bitDepth rides separately.
 */
export function qualityTierLabel(trackType, samplingRateHz, bandwidthBps = null) {
  const r = samplingRateHz;
  switch (trackType) {
    case 'UHD':
      if (r == null) return 'UHD';
      if (r >= 192000) return 'UHD192';
      if (r >= 96000) return 'UHD96';
      if (r >= 48000) return 'UHD48';
      if (r >= 44100) return 'UHD44';
      return 'UHD';
    case 'HD':
      if (r == null) return 'HD';
      if (r >= 192000) return 'HD192';
      if (r >= 96000) return 'HD96';
      if (r >= 48000) return 'HD48';
      if (r >= 44100) return 'HD44';
      return 'HD';
    case 'SD':
    case 'LD':
      if (bandwidthBps == null) return 'Unknown';
      if (bandwidthBps >= 256000) return 'High';
      if (bandwidthBps > 128000) return 'Medium';
      return 'Low';
    default:
      return 'Unknown';
  }
}

/** Human spec label, e.g. "24-bit / 96 kHz FLAC (Ultra HD)" */
export function specLabelFor({ codec, bitDepth, samplingRateHz, tierLabel }) {
  const codecName = codecFamily(codec) === 'flac' ? 'FLAC' : codec.toUpperCase();
  const depth = bitDepth ? `${bitDepth}-bit` : '';
  const rate = samplingRateHz ? `${(samplingRateHz / 1000).toFixed(samplingRateHz % 1000 ? 1 : 0)} kHz` : '';
  const family = (tierLabel || '').startsWith('UHD') ? 'Ultra HD' : (tierLabel || '').startsWith('HD') ? 'HD' : '';
  return [depth, rate, codecName, family].filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
}

/** codec string -> codec family */
export function codecFamily(codecs) {
  if (!codecs) return 'unknown';
  const c = String(codecs).toLowerCase();
  if (c === 'flac' || c.startsWith('fLaC')) return 'flac';
  if (c.includes('opus')) return 'opus';
  if (c.startsWith('mp4a.40.2')) return 'aac-lc';
  if (c.startsWith('mp4a.40.5')) return 'aac-he';
  if (c.includes('ec-3')) return 'ec-3';
  if (c.includes('ac-4')) return 'ac-4';
  if (c.includes('mp4a')) return 'aac';
  return c.split('.')[0] || 'unknown';
}

const AMZ_BIT_DEPTH = 'amz-music:bitDepth';
const AMZ_TRACK_TYPE = 'amz-music:trackType';

/* ------------------------------------------------------------------ */
/* DASH normalization                                                 */
/* ------------------------------------------------------------------ */

function collectSupplementalProperties(node) {
  const props = {};
  for (const sp of children(node, 'SupplementalProperty')) {
    const uri = sp.attrs.schemeIdUri;
    if (uri) props[uri] = sp.attrs.value ?? '';
  }
  return props;
}

function collectContentProtection(node) {
  return children(node, 'ContentProtection').map(cp => {
    const pssh = children(cp, 'pssh').map(p => p.text.trim()).filter(Boolean);
    return {
      schemeIdUri: cp.attrs.schemeIdUri ?? null,
      value: cp.attrs.value ?? null,
      default_KID: cp.attrs.default_KID ?? null,
      psshList: pssh
    };
  });
}

function parseSegmentTemplate(el) {
  if (!el) return null;
  const tpl = {
    initialization: el.attrs.initialization ?? null,
    media: el.attrs.media ?? null,
    timescale: toNumber(el.attrs.timescale) ?? 1,
    duration: toNumber(el.attrs.duration),
    startNumber: toNumber(el.attrs.startNumber) ?? 0,
    presentationTimeOffset: toNumber(el.attrs.presentationTimeOffset) ?? 0,
    segmentTimeline: []
  };
  const tl = firstChild(el, 'SegmentTimeline');
  if (tl) {
    for (const s of children(tl, 'S')) {
      tpl.segmentTimeline.push({
        t: toNumber(s.attrs.t),
        d: toNumber(s.attrs.d) ?? 0,
        r: toNumber(s.attrs.r) ?? 0
      });
    }
  }
  return tpl;
}

function parseRepresentation(repEl, adaptationSet) {
  const supplemental = {
    ...collectSupplementalProperties(adaptationSet),
    ...collectSupplementalProperties(repEl)
  };
  const contentProtections = [
    ...collectContentProtection(adaptationSet),
    ...collectContentProtection(repEl)
  ];
  const segmentTemplate = parseSegmentTemplate(firstChild(repEl, 'SegmentTemplate')) ||
    parseSegmentTemplate(firstChild(adaptationSet, 'SegmentTemplate')) ||
    parseSegmentTemplate(firstChild(firstChild(adaptationSet, 'parent') ?? {}, 'SegmentTemplate'));

  return {
    id: repEl.attrs.id ?? null,
    codecs: repEl.attrs.codecs ?? adaptationSet.attrs.codecs ?? null,
    mimeType: repEl.attrs.mimeType ?? adaptationSet.attrs.mimeType ?? null,
    audioSamplingRate: toNumber(repEl.attrs.audioSamplingRate ?? adaptationSet.attrs.audioSamplingRate),
    bandwidth: toNumber(repEl.attrs.bandwidth),
    height: toNumber(repEl.attrs.height),
    lang: repEl.attrs.lang ?? adaptationSet.attrs.lang ?? null,
    supplementalProperties: supplemental,
    contentProtections,
    segmentTemplate,
    segmentList: children(repEl, 'SegmentList').map(sl => ({
      duration: toNumber(sl.attrs.duration) ?? null,
      timescale: toNumber(sl.attrs.timescale) ?? 1,
      mediaUrls: children(sl, 'SegmentURL').map(su => su.attrs.media).filter(Boolean)
    }))[0] ?? null,
    segmentBase: (() => {
      const sb = firstChild(repEl, 'SegmentBase');
      if (!sb) return null;
      const init = firstChild(sb, 'Initialization');
      return { timescale: toNumber(sb.attrs.timescale) ?? 1, initializationRange: init?.attrs.range ?? null };
    })()
  };
}

/**
 * Parses an inline DASH MPD XML string into a normalized descriptor with
 * every Amazon-dialect quality signal and the full URL graph.
 *
 * @param {string} mpdXml
 * @returns {Object} normalized manifest
 */
export function parseDashManifest(mpdXml) {
  const doc = parseXml(mpdXml);
  const mpdEl = doc.children.find(c => c.name === 'MPD');
  if (!mpdEl) throw new Error('parseDashManifest: no MPD root element');

  const baseUrls = children(mpdEl, 'BaseURL').map(b => b.text.trim() || b.attrs[''] || null).filter(Boolean);

  const periods = children(mpdEl, 'Period').map((p, periodIdx) => ({
    id: p.attrs.id ?? null,
    index: periodIdx,
    start: parseDuration(p.attrs.start ?? '0s') ?? 0,
    durationSec: parseDuration(p.attrs.duration) ?? null,
    adaptationSets: children(p, 'AdaptationSet').map(as => ({
      id: as.attrs.id ?? null,
      contentType: as.attrs.contentType ?? null,
      mimeType: as.attrs.mimeType ?? null,
      lang: as.attrs.lang ?? null,
      supplementalProperties: collectSupplementalProperties(as),
      representations: children(as, 'Representation').map(repEl => parseRepresentation(repEl, as))
    }))
  }));

  // Flatten representations for convenience, annotated with quality cards.
  const representations = [];
  for (const period of periods) {
    for (const as of period.adaptationSets) {
      for (const rep of as.representations) {
        const trackType = rep.supplementalProperties[AMZ_TRACK_TYPE] || as.supplementalProperties[AMZ_TRACK_TYPE] || null;
        const bitDepth = toNumber(rep.supplementalProperties[AMZ_BIT_DEPTH] ?? as.supplementalProperties[AMZ_BIT_DEPTH] ?? null);
        const tierLabel = qualityTierLabel(trackType, rep.audioSamplingRate, rep.bandwidth);
        const family = codecFamily(rep.codecs);
        representations.push({
          ...rep,
          periodId: period.id,
          adaptationSetId: as.id,
          adaptationContentType: as.contentType || as.mimeType?.split('/')[0] || null,
          trackType,
          bitDepth,
          tierLabel,
          codecFamily: family,
          lossless: family === 'flac',
          specLabel: specLabelFor({ codec: rep.codecs, bitDepth, samplingRateHz: rep.audioSamplingRate, tierLabel }),
          drmProtected: rep.contentProtections.some(cp =>
            (cp.schemeIdUri || '').includes('cenc') || (cp.schemeIdUri || '').includes('marlin') ||
            (cp.schemeIdUri || '').includes('playready') || (cp.schemeIdUri || '').includes('widevine') ||
            cp.psshList.length > 0)
        });
      }
    }
  }

  return {
    mpd: {
      type: mpdEl.attrs.type ?? 'static',
      profiles: mpdEl.attrs.profiles ?? null,
      durationSec: parseDuration(mpdEl.attrs.mediaPresentationDuration),
      availabilityStartTime: mpdEl.attrs.availabilityStartTime ?? null,
      minimumUpdatePeriod: mpdEl.attrs.minimumUpdatePeriod ?? null
    },
    baseUrls,
    periods,
    representations,
    raw: mpdXml
  };
}

/* ------------------------------------------------------------------ */
/* Segment URL resolution (the actual stream links)                   */
/* ------------------------------------------------------------------ */

/**
 * Resolves concrete CDN URLs for a representation.
 *
 * @param {Object} manifest - parseDashManifest() output
 * @param {Object} representation - a manifest.representations entry
 * @param {Object} [options]
 * @param {string} [options.origin] - absolute origin used when BaseURL is relative
 * @returns {Object} link graph
 */
export function resolveSegmentUrls(manifest, representation, { origin = null } = {}) {
  const tpl = representation.segmentTemplate;
  if (!tpl) {
    // SegmentList / SegmentBase forms
    if (representation.segmentList?.mediaUrls?.length) {
      return {
        kind: 'segment-list',
        initUrl: null,
        mediaUrls: resolveBase(manifest, representation.segmentList.mediaUrls[0], origin) === null
          ? representation.segmentList.mediaUrls
          : representation.segmentList.mediaUrls.map(u => resolveBase(manifest, u, origin)),
        segmentDurationSec: null,
        segmentCount: representation.segmentList.mediaUrls.length
      };
    }
    if (representation.segmentBase) {
      return { kind: 'segment-base', initUrl: null, byteRange: representation.segmentBase.initializationRange, segmentCount: null, segmentDurationSec: null };
    }
    return { kind: 'none', initUrl: null, segmentCount: 0 };
  }

  const subst = (template) => {
    if (!template) return null;
    return template
      .replace(/\$RepresentationID\$/g, String(representation.id ?? ''))
      .replace(/\$Bandwidth\$/g, String(representation.bandwidth ?? ''))
      .replace(/\$Number%0+(\d+)d\$/g, (_, width) => '0'.repeat(Number(width)))
      .replace(/\$Number\$/g, String(tpl.startNumber))
      .replace(/\$Time\$/g, '0');
  };

  const initRel = subst(tpl.initialization);
  const mediaTemplate = tpl.media;
  const initUrl = initRel ? resolveBase(manifest, initRel, origin) : null;

  // Segment count/duration from @duration or SegmentTimeline
  let segmentCount = null;
  let segmentDurationSec = null;
  const totalDuration = manifest.mpd.durationSec
    ?? (manifest.periods[0]?.durationSec ?? null);

  if (tpl.duration != null && tpl.timescale) {
    segmentDurationSec = tpl.duration / tpl.timescale;
    if (totalDuration != null && segmentDurationSec > 0) {
      segmentCount = Math.ceil(totalDuration / segmentDurationSec);
    }
  } else if (tpl.segmentTimeline.length) {
    let t = tpl.segmentTimeline[0].t ?? 0;
    let count = 0;
    const sampleD = tpl.segmentTimeline[0].d ?? 0;
    for (const s of tpl.segmentTimeline) {
      if (s.t != null) t = s.t;
      count += (s.r ?? 0) + 1;
    }
    segmentCount = count;
    segmentDurationSec = sampleD && tpl.timescale ? sampleD / tpl.timescale : null;
  }

  // First few resolved media URLs (links are real even when count unknown)
  const firstUrls = [];
  if (mediaTemplate) {
    const total = segmentCount ?? Math.min(segmentCount ?? 3, 3);
    for (let n = tpl.startNumber; n < tpl.startNumber + Math.min(total, 3); n++) {
      const url = mediaTemplate
        .replace(/\$RepresentationID\$/g, String(representation.id ?? ''))
        .replace(/\$Bandwidth\$/g, String(representation.bandwidth ?? ''))
        .replace(/\$Number%0+(\d+)d\$/g, (_, width) => String(n).padStart(Number(width), '0'))
        .replace(/\$Number\$/g, String(n))
        .replace(/\$Time\$/g, '0');
      firstUrls.push(resolveBase(manifest, url, origin));
    }
  }

  return {
    kind: 'segment-template',
    initUrl,
    mediaTemplate: mediaTemplate ? resolveBase(manifest, mediaTemplate, origin) : null,
    mediaTemplateRaw: mediaTemplate,
    firstSegmentUrls: firstUrls,
    segmentCount,
    segmentDurationSec,
    timescale: tpl.timescale
  };
}

function resolveBase(manifest, url, origin) {
  if (!url) return null;
  if (/^https?:\/\//i.test(url)) return url;
  const base = manifest.baseUrls?.[0];
  if (base && /^https?:\/\//i.test(base)) {
    try { return new URL(url, base).href; } catch { return base.replace(/\/+$/, '') + '/' + url; }
  }
  if (base) return base.replace(/\/+$/, '') + '/' + url;
  if (origin) return origin.replace(/\/+$/, '') + '/' + url;
  return url; // relative - caller resolves against the DMLS host
}

/* ------------------------------------------------------------------ */
/* Tier selection policy                                               */
/* ------------------------------------------------------------------ */

/**
 * Ranks representations for a requested tier.
 *   'ultra'  -> FLAC UHD family (24-bit), highest sample rate first
 *   'hd'     -> FLAC HD family (16-bit), highest sample rate first
 *   'lossy'  -> opus/aac HIGH
 *   'best'   -> highest-quality anything
 */
export function selectRepresentationForTier(representations, tier = 'ultra') {
  if (!representations || representations.length === 0) return null;
  const byLosslessSample = (a, b) => (b.audioSamplingRate ?? 0) - (a.audioSamplingRate ?? 0) ||
    (b.bandwidth ?? 0) - (a.bandwidth ?? 0);

  const want = String(tier).toLowerCase();
  if (want === 'ultra' || want === 'uhd' || want === 'ultra_hd') {
    const uhdFlac = representations.filter(r => r.codecFamily === 'flac' && (r.trackType === 'UHD' || (r.bitDepth ?? 0) >= 24));
    if (uhdFlac.length) return uhdFlac.sort(byLosslessSample)[0];
    const anyFlac = representations.filter(r => r.codecFamily === 'flac');
    if (anyFlac.length) return anyFlac.sort(byLosslessSample)[0];
  }
  if (want === 'hd' || want === 'lossless') {
    const flac = representations.filter(r => r.codecFamily === 'flac');
    if (flac.length) return flac.sort(byLosslessSample)[0];
  }
  if (want === 'lossy' || want === 'high') {
    const lossy = representations.filter(r => ['opus', 'aac-lc', 'aac-he', 'aac', 'ec-3'].includes(r.codecFamily));
    if (lossy.length) return lossy.sort((a, b) => (b.bandwidth ?? 0) - (a.bandwidth ?? 0))[0];
  }
  // best / fallback: FLAC first then highest bandwidth
  const flac = representations.filter(r => r.codecFamily === 'flac');
  if (flac.length) return flac.sort(byLosslessSample)[0];
  return [...representations].sort((a, b) => (b.bandwidth ?? 0) - (a.bandwidth ?? 0))[0];
}
