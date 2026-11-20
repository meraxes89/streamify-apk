/**
 * Parse an HTTP Range header for single-range streaming.
 * @param {string | undefined} rangeHeader - e.g. "bytes=0-1023" or "bytes=0-1"
 * @param {number} totalLength - Total content length in bytes
 * @returns {{ satisfiable: true, start: number, end: number, length: number, contentRange: string } | { satisfiable: false } | null}
 */
export function parseRange(rangeHeader, totalLength) {
  if (!rangeHeader || typeof rangeHeader !== 'string') {
    return null;
  }

  // Multi-range: serve only the first range
  const firstRange = rangeHeader.split(',')[0].trim();
  const match = firstRange.match(/^bytes=(\d*)-(\d*)$/);
  if (!match) {
    return { satisfiable: false };
  }

  const [, startStr, endStr] = match;

  if (startStr === '' && endStr === '') {
    return { satisfiable: false };
  }

  let start;
  let end;

  if (startStr === '') {
    // Suffix range: bytes=-500 (last 500 bytes)
    const suffix = parseInt(endStr, 10);
    if (isNaN(suffix) || suffix <= 0) return { satisfiable: false };
    start = Math.max(0, totalLength - suffix);
    end = totalLength - 1;
  } else if (endStr === '') {
    // Open-ended range: bytes=1000-
    start = parseInt(startStr, 10);
    if (isNaN(start) || start < 0 || start >= totalLength) return { satisfiable: false };
    end = totalLength - 1;
  } else {
    // Explicit range: bytes=0-1023
    start = parseInt(startStr, 10);
    end = parseInt(endStr, 10);
    if (isNaN(start) || isNaN(end) || start < 0 || start > end || start >= totalLength) {
      return { satisfiable: false };
    }
    // Cap end to totalLength - 1
    end = Math.min(end, totalLength - 1);
  }

  const length = end - start + 1;
  const contentRange = `bytes ${start}-${end}/${totalLength}`;

  return {
    satisfiable: true,
    start,
    end,
    length,
    contentRange,
  };
}
