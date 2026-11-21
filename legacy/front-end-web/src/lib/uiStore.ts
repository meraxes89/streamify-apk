/**
 * lib/uiStore.ts — P1: overlay/sheet visibility state.
 *
 * The store-per-screen discipline (§9.3) keeps sheet state out of the
 * routed screens so the FullPlayerSheet overlay survives navigation the
 * same way the Android sheet lives above the nav graph (MainActivity
 * layer 3, `isPlayerExpanded && hasTrack`). Surfaces that open it:
 * the MiniPlayerBar tap (the Android mapping), the sheet's own collapse
 * closes it.
 */

import { create } from 'zustand';

export interface UiState {
  /** The liquid full-player overlay (layer 3 in MainActivity terms). */
  fullPlayerOpen: boolean;
  openFullPlayer(): void;
  closeFullPlayer(): void;
}

export const useUiStore = create<UiState>((set) => ({
  fullPlayerOpen: false,
  openFullPlayer() {
    set({ fullPlayerOpen: true });
  },
  closeFullPlayer() {
    set({ fullPlayerOpen: false });
  },
}));
