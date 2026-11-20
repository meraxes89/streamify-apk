/// <reference types="vite/client" />
/**
 * lyrics/offsetStore.ts — per-track lyric sync offsets (port of
 * app/…/media/lyrics/LyricOffsetStore.kt).
 *
 * Every lyric surface binds to the same track key, so a nudge made anywhere
 * survives navigation and reloads. Keyed by "title|artist" identity
 * (sanitized) — stable across id schemes, exactly like the Android
 * SharedPreferences store it replaces.
 */

import { trackKeyOf } from './sanitize';

const FILE_KEY = 'streamify.lyric_user_offsets';

/** The stable per-track key (LyricOffsetStore.keyOf). */
export function keyOf(title: string | null | undefined, artist: string | null | undefined): string {
  return trackKeyOf(title ?? '', artist ?? '');
}

interface OffsetMap {
  [trackKey: string]: number;
}

function readAll(): OffsetMap {
  try {
    const raw = localStorage.getItem(FILE_KEY);
    if (raw == null) return {};
    const parsed = JSON.parse(raw) as OffsetMap;
    if (parsed === null || typeof parsed !== 'object') return {};
    return parsed;
  } catch {
    return {}; // private mode / corrupt store — offsets are best-effort
  }
}

function writeAll(map: OffsetMap): void {
  try {
    localStorage.setItem(FILE_KEY, JSON.stringify(map));
  } catch {
    /* storage full / private mode — nudges live for the session only */
  }
}

/** The saved user offset for a track key (0 when unset). */
export function getOffset(key: string | null | undefined): number {
  if (key == null || key.trim() === '') return 0;
  const map = readAll();
  const v = map[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}

/** Persist (or clear, 0) the user offset for a track key. */
export function setOffset(key: string, offsetMs: number): void {
  if (key.trim() === '') return;
  const map = readAll();
  if (offsetMs === 0) delete map[key];
  else map[key] = Math.round(offsetMs);
  writeAll(map);
}
