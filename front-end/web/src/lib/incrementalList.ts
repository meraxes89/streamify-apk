/**
 * lib/incrementalList.ts — P2 browse: the LazyColumn lowering for long
 * lists, zero-dependency.
 *
 * The Android screens are LazyColumns (viewport windows); the web port of
 * a several-hundred-row cloud library renders incrementally instead: the
 * first `step` rows mount, a sentinel row at the bottom grows the window
 * via IntersectionObserver. Re-renders stay proportional to the visible
 * slice, and the growth is layout-free (append-only).
 */

import { useEffect, useRef, useState } from 'react';

export interface IncrementalList<T> {
  /** The visible slice. */
  visible: T[];
  /** Mount the sentinel after the list; it grows the window on intersect. */
  sentinelRef: (node: HTMLElement | null) => void;
  /** True while more rows remain unmounted. */
  hasMore: boolean;
}

export function useIncrementalList<T>(items: T[], step = 80): IncrementalList<T> {
  // No reset-on-items-change: a stale-grown limit is harmless (smaller
  // lists render fully; larger lists re-arm the sentinel), and callers
  // that switch lists remount via React keys anyway.
  const [limit, setLimit] = useState(step);

  const observerRef = useRef<IntersectionObserver | null>(null);
  const sentinelRef = (node: HTMLElement | null): void => {
    observerRef.current?.disconnect();
    observerRef.current = null;
    if (node === null) return;
    observerRef.current = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setLimit((prev) => prev + step);
        }
      },
      { rootMargin: '400px' },
    );
    observerRef.current.observe(node);
  };

  useEffect(
    () => () => {
      observerRef.current?.disconnect();
    },
    [],
  );

  const visible = items.slice(0, limit);
  return { visible, sentinelRef, hasMore: visible.length < items.length };
}
