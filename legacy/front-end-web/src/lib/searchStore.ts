/**
 * lib/searchStore.ts — P2 browse: the SearchViewModel port, deepened.
 *
 * Zustand (in-memory) so navigating away and back restores the results
 * list without a refetch — the Android search ViewModel's screen-spanning
 * lifetime, web lowering. The P3a surface (query/setQuery/run/phase/
 * results/error — what SearchPanel consumes) is unchanged; the browse
 * deepening adds:
 *
 *   • typed sections (SearchUiState.Success.localResults/onlineResults →
 *     tracks + artists + playlists from searchMusicDetailed);
 *   • the selected filter (SearchFilter.fromLabel — client-side section
 *     filter over the real result set);
 *   • search history (SharedPreferences ";;"-joined list → localStorage,
 *     max 10, added after the 1 s dwell so keystrokes don't pollute it);
 *   • suggestions (fetchSearchSuggestions → the session's music suggest
 *     lane, debounced 150 ms, history-derived fallback — never faked).
 *
 * G4 request-epoch guards: a slow older query must never land its results
 * over a newer one (the stale-shelf failure mode).
 */

import { create } from 'zustand';
import { searchMusicDetailed } from './engine';
import type { ArtistSearchResult, PlaylistSearchResult, Track } from './engine';
import { useSessionStore } from './sessionStore';
import type { CloudTrack } from './supabase';

export type SearchPhase = 'idle' | 'loading' | 'results' | 'error';

/** YtSearchFilterChips' web-honest subset (see PR notes: Videos/Albums collapse). */
export type SearchFilterLabel = 'All' | 'Songs' | 'Artists' | 'Playlists';

const HISTORY_KEY = 'streamify.search.history.v1';
const HISTORY_MAX = 10;
const HISTORY_JOIN = ';;';
const SUGGEST_MIN_CHARS = 2;

export interface SearchState {
  phase: SearchPhase;
  /** The live query field (SearchPanel compat). */
  query: string;
  filter: SearchFilterLabel;
  /** Video results (SearchPanel compat + the Songs section). */
  results: Track[];
  artists: ArtistSearchResult[];
  playlists: PlaylistSearchResult[];
  suggestions: string[];
  history: string[];
  error: string | null;
  setQuery(query: string): void;
  setFilter(filter: SearchFilterLabel): void;
  /** Immediate search of a query (the debounced lane calls this too). */
  search(query?: string): Promise<void>;
  /** SearchPanel compat: search(get().query). */
  run(): Promise<void>;
  /** Debounced suggestion refresh (150 ms, ≥2 chars). */
  updateSuggestions(query: string): void;
  clearHistory(): void;
  /** Back to the explore hub (the BackHandler query-clear lowering). */
  reset(): void;
}

function readHistory(): string[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    if (raw === null || raw.trim() === '') return [];
    return raw.split(HISTORY_JOIN).filter((q) => q !== '');
  } catch {
    return [];
  }
}

function persistHistory(list: string[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, list.join(HISTORY_JOIN));
  } catch {
    /* private mode — history becomes memory-only */
  }
}

function addQueryToHistory(list: string[], query: string): string[] {
  const q = query.trim();
  if (q === '') return list;
  const next = list.filter((item) => item !== q);
  next.unshift(q);
  if (next.length > HISTORY_MAX) next.length = HISTORY_MAX;
  return next;
}

/** The "From your library" section: cloud likes matching the query (real). */
export function matchLibraryTracks(query: string, likes: CloudTrack[]): CloudTrack[] {
  const q = query.trim().toLowerCase();
  if (q === '') return [];
  const terms = q.split(/\s+/);
  return likes.filter((track) => {
    const hay = `${track.title} ${track.artist} ${track.album ?? ''}`.toLowerCase();
    return terms.every((term) => hay.includes(term));
  });
}

/** The session's music-suggest lane (shape-guarded; empty on any failure). */
async function fetchSuggestions(query: string): Promise<string[]> {
  try {
    const { getYt } = await import('./ytSession');
    const yt = await getYt();
    // youtubei.js MusicClient#getSearchSuggestions — guarded through a
    // narrow local type so the build never depends on its type surface.
    const music = yt.music as unknown as {
      getSearchSuggestions?: (q: string) => Promise<unknown>;
    };
    if (typeof music.getSearchSuggestions !== 'function') return [];
    const res = await music.getSearchSuggestions(query);
    const node = res as { suggestions?: unknown };
    if (!Array.isArray(node.suggestions)) return [];
    const out: string[] = [];
    for (const raw of node.suggestions) {
      const text =
        typeof raw === 'string'
          ? raw
          : typeof (raw as Record<string, unknown> | null)?.text === 'string'
            ? ((raw as Record<string, unknown>).text as string)
            : '';
      if (text !== '') out.push(text);
    }
    return out;
  } catch {
    return [];
  }
}

export const useSearchStore = create<SearchState>((set, get) => ({
  phase: 'idle',
  query: '',
  filter: 'All',
  results: [],
  artists: [],
  playlists: [],
  suggestions: [],
  history: readHistory(),
  error: null,

  setQuery(query) {
    set({ query });
  },

  setFilter(filter) {
    set({ filter });
  },

  async search(input) {
    const q = (input ?? get().query).trim();
    if (q === '') {
      set({ phase: 'idle', error: null, results: [], artists: [], playlists: [] });
      return;
    }
    // History lands after a 1 s dwell — rapid keystrokes never pollute it
    // (the historyJob delay, timer-canceled by the next search).
    window.clearTimeout(historyTimer);
    historyTimer = window.setTimeout(() => {
      const history = addQueryToHistory(get().history, q);
      persistHistory(history);
      set({ history });
    }, 1000);

    const epoch = ++searchEpoch;
    set({ phase: 'loading', error: null });
    try {
      const sections = await searchMusicDetailed(q);
      if (epoch !== searchEpoch) return; // superseded — drop silently
      set({
        results: sections.tracks,
        artists: sections.artists,
        playlists: sections.playlists,
        phase: 'results',
      });
    } catch (err) {
      if (epoch !== searchEpoch) return;
      set({
        phase: 'error',
        error: err instanceof Error ? err.message : String(err),
      });
    }
  },

  async run() {
    await get().search(get().query);
  },

  updateSuggestions(query) {
    const clean = query.trim();
    window.clearTimeout(suggestTimer);
    if (clean.length < SUGGEST_MIN_CHARS) {
      set({ suggestions: [] });
      return;
    }
    suggestTimer = window.setTimeout(() => {
      void fetchSuggestions(clean).then((networkSuggestions) => {
        // Real network suggestions first; the history-derived fallback
        // (prefix matches on past searches) keeps the rail honest when
        // the suggest lane is unreachable.
        const localFallback = get()
          .history.filter((item) => item.toLowerCase().includes(clean.toLowerCase()))
          .slice(0, 6);
        const list = networkSuggestions.length > 0 ? networkSuggestions.slice(0, 8) : localFallback;
        set({ suggestions: Array.from(new Set(list)) });
      });
    }, 150);
  },

  clearHistory() {
    persistHistory([]);
    set({ history: [] });
  },

  reset() {
    window.clearTimeout(historyTimer);
    ++searchEpoch; // any in-flight search is dead
    set({
      phase: 'idle',
      query: '',
      results: [],
      artists: [],
      playlists: [],
      suggestions: [],
      error: null,
    });
  },
}));

/** Monotonic request epochs (module-level — survive store re-creation). */
let searchEpoch = 0;
let suggestTimer = 0;
let historyTimer = 0;

/**
 * The signed-in library mirror for the "From your library" search section
 * (subscribe-free convenience read — the session store stays the truth).
 */
export function currentLikes(): CloudTrack[] {
  return useSessionStore.getState().likes;
}
