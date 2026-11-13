/**
 * lib/toastStore.ts — P3a: minimal toast feedback (Android's Toast parity).
 */

import { create } from 'zustand';

export interface Toast {
  id: number;
  message: string;
  kind: 'info' | 'error' | 'success';
}

export interface ToastState {
  toasts: Toast[];
  push(message: string, kind?: Toast['kind']): void;
  dismiss(id: number): void;
}

let nextId = 1;

export const useToastStore = create<ToastState>((set, get) => ({
  toasts: [],
  push(message, kind = 'info') {
    const id = nextId++;
    set((prev) => ({ toasts: [...prev.toasts.slice(-2), { id, message, kind }] }));
    setTimeout(() => get().dismiss(id), 3800);
  },
  dismiss(id) {
    set((prev) => ({ toasts: prev.toasts.filter((t) => t.id !== id) }));
  },
}));

/** Fire-and-forget helper for non-component call sites. */
export function toast(message: string, kind: Toast['kind'] = 'info'): void {
  useToastStore.getState().push(message, kind);
}
