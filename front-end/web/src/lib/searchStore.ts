/**
 * lib/searchStore.ts — P3a: search state lifted out of the component tree.
 *
 * Zustand (in-memory) so navigating Home ↔ Settings ↔ Profile and back
 * restores the results list without a refetch — the Android search
 * ViewModel's screen-spanning lifetime, web lowering.
 */

import { create } from 'zustand';
import { searchMusic } from './engine';
import type { Track } from './engine';

export type SearchPhase = 'idle' | 'loading' | 'results' | 'error';

export interface SearchState {
  phase: SearchPhase;
  query: string;
  results: Track[];
  error: string | null;
  setQuery(query: string): void;
  run(): Promise<void>;
}

export const useSearchStore = create<SearchState>((set, get) => ({
  phase: 'idle',
  query: '',
  results: [],
  error: null,

  setQuery(query) {
    set({ query });
  },

  async run() {
    const q = get().query.trim();
    if (q === '') return;
    // G4: request epoch guard — a slow older query must never land its
    // results over a newer one (the stale-shelf failure mode: search
    // "blinding lights" while a slow earlier request is still in flight
    // and the earlier query's tracks render under the new query's label).
    const epoch = ++searchEpoch;
    set({ phase: 'loading', error: null });
    try {
      const tracks = await searchMusic(q);
      if (epoch !== searchEpoch) return; // superseded — drop silently
      set({ results: tracks, phase: 'results' });
    } catch (err) {
      if (epoch !== searchEpoch) return;
      set({
        phase: 'error',
        error: err instanceof Error ? err.message : String(err),
      });
    }
  },
}));

/** Monotonic request epoch (module-level — survives store re-creation). */
let searchEpoch = 0;
