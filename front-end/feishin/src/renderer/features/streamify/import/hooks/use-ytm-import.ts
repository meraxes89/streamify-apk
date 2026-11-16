/**
 * features/streamify/import/hooks/use-ytm-import.ts — the importer's state
 * wiring (W1-A).
 *
 * Owns the AbortController and mirrors the ingest engine's progress into
 * component state. The hook is deliberately thin: every transition comes
 * from importYtmTarget's onProgress callback — no derived logic here.
 */

import type { ServerListItemWithCredential } from '/@/shared/types/domain-types';

import { useCallback, useRef, useState } from 'react';

import type { ParsedYtmTarget } from '../url-parser';

import { type ImportProgress, importYtmTarget } from '../ingest';

export interface UseYtmImportState {
    cancel: () => void;
    /** Latest progress (null before the first report). */
    progress: ImportProgress | null;
    running: boolean;
    start: (args: {
        onSettled?: (summary: null | { importedCount: number }) => void;
        server: null | ServerListItemWithCredential;
        serverId: string | undefined;
        target: ParsedYtmTarget;
    }) => void;
}

export function useYtmImport(): UseYtmImportState {
    const [progress, setProgress] = useState<ImportProgress | null>(null);
    const [running, setRunning] = useState(false);
    const abortRef = useRef<AbortController | null>(null);

    const cancel = useCallback(() => {
        abortRef.current?.abort();
    }, []);

    const start = useCallback<UseYtmImportState['start']>((args) => {
        if (abortRef.current !== null) return; // one import at a time
        const controller = new AbortController();
        abortRef.current = controller;
        setRunning(true);
        setProgress(null);
        importYtmTarget({
            onProgress: (update) => setProgress(update),
            server: args.server,
            serverId: args.serverId,
            signal: controller.signal,
            target: args.target,
        })
            .then((summary) => {
                args.onSettled?.({ importedCount: summary.importedCount });
            })
            .catch(() => {
                args.onSettled?.(null);
            })
            .finally(() => {
                abortRef.current = null;
                setRunning(false);
            });
    }, []);

    return { cancel, progress, running, start };
}
