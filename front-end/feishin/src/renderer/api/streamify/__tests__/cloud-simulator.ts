/**
 * cloud-simulator.ts - the network-free Supabase tables (P3A test lane).
 *
 * A faithful in-memory simulation of the two PostgREST surfaces the sync
 * layer touches (playlists + playlist_tracks), including the semantics the
 * convergence depends on: PK-conflict-ignoring inserts, no-op PATCHes on
 * missing rows, cascade DELETEs, FK violations, and BYTEWISE uuid
 * comparison for the element block ranges (undashed-hex string order ==
 * big-endian u64 numeric order, exactly like Postgres).
 *
 * Not a test file (no *.test.ts) - imported by the vi.mock factories of
 * the controller and sync-convergence suites through a hoisted holder.
 */

import type { PgPlaylistRow, PgTrackRow } from '../playlist-sync/postgrest';

export interface CloudPlaylistTableRow {
    cover_url: null | string;
    created_at: string;
    description: null | string;
    id: string;
    is_public: boolean;
    name: string;
    updated_at: string;
    user_id: string;
}

export interface CloudSimulator {
    deleteElementRows: (playlistId: string, addOpId: bigint) => Promise<void>;
    deletePlaylistRow: (id: string) => Promise<void>;
    deleteTrackRow: (rowId: string) => Promise<void>;
    fetchPlaylistRows: () => Promise<PgPlaylistRow[]>;
    fetchTrackRows: (playlistIds: string[]) => Promise<PgTrackRow[]>;
    insertPlaylistRows: (rows: Array<Record<string, unknown>>) => Promise<void>;
    insertTrackRows: (rows: CloudTrackTableRow[]) => Promise<void>;
    patchPlaylistRow: (id: string, patch: Record<string, unknown>) => Promise<void>;
    patchTrackRow: (
        rowId: string,
        patch: { added_at?: string; id?: string; position?: number },
    ) => Promise<void>;
    playlists: Map<string, CloudPlaylistTableRow>;
    setOffline: (offline: boolean) => void;
    tracks: Map<string, CloudTrackTableRow>;
}

export interface CloudTrackTableRow {
    added_at: string;
    added_by: string;
    id: string;
    playlist_id: string;
    position: number;
    track_id: string;
}

const undash = (id: string) => id.replace(/-/g, '').toLowerCase();

export function createCloudSimulator(userIds: string[] = ['user-1']): CloudSimulator {
    const playlists = new Map<string, CloudPlaylistTableRow>();
    const tracks = new Map<string, CloudTrackTableRow>();
    const users = new Set(userIds);
    let offline = false;

    const guard = (): void => {
        if (offline) {
            throw new Error('simulated offline');
        }
    };

    return {
        async deleteElementRows(playlistId: string, addOpId: bigint): Promise<void> {
            guard();
            const lo = undash(blockLow(addOpId));
            const hi = undash(blockLow(addOpId + 1n));
            for (const [id, row] of tracks) {
                const key = undash(id);
                if (row.playlist_id === playlistId && key >= lo && key < hi) {
                    tracks.delete(id);
                }
            }
        },
        async deletePlaylistRow(id: string): Promise<void> {
            guard();
            if (!playlists.has(id)) {
                return; // DELETE on missing = no-op
            }
            playlists.delete(id);
            for (const [id2, row] of tracks) {
                if (row.playlist_id === id) {
                    tracks.delete(id2);
                }
            }
        },
        async deleteTrackRow(rowId: string): Promise<void> {
            guard();
            tracks.delete(rowId);
        },
        async fetchPlaylistRows(): Promise<PgPlaylistRow[]> {
            guard();
            return Array.from(playlists.values())
                .filter((row) => users.has(row.user_id))
                .sort((a, b) => (a.created_at < b.created_at ? -1 : 1))
                .map((row) => ({
                    cover_url: row.cover_url,
                    created_at: row.created_at,
                    description: row.description,
                    id: row.id,
                    is_public: row.is_public,
                    name: row.name,
                    updated_at: row.updated_at,
                }));
        },
        async fetchTrackRows(playlistIds: string[]): Promise<PgTrackRow[]> {
            guard();
            const wanted = new Set(playlistIds);
            return Array.from(tracks.values())
                .filter((row) => wanted.has(row.playlist_id))
                .sort((a, b) => a.position - b.position || (undash(a.id) < undash(b.id) ? -1 : 1))
                .map((row) => ({
                    added_at: row.added_at,
                    added_by: row.added_by,
                    id: row.id,
                    playlist_id: row.playlist_id,
                    position: row.position,
                    track_id: row.track_id,
                }));
        },
        async insertPlaylistRows(rows: Array<Record<string, unknown>>): Promise<void> {
            guard();
            for (const row of rows) {
                const rowId = String(row.id ?? '');
                if (rowId === '' || playlists.has(rowId)) {
                    continue; // ignore-duplicates
                }
                playlists.set(rowId, {
                    cover_url: (row.cover_url as null | string) ?? null,
                    created_at: String(row.created_at ?? ''),
                    description: (row.description as null | string) ?? null,
                    id: rowId,
                    is_public: row.is_public !== false,
                    name: String(row.name ?? ''),
                    updated_at: String(row.updated_at ?? ''),
                    user_id: String(row.user_id ?? ''),
                });
            }
        },
        async insertTrackRows(rows: CloudTrackTableRow[]): Promise<void> {
            guard();
            for (const row of rows) {
                if (tracks.has(row.id)) {
                    continue; // ignore-duplicates
                }
                if (!playlists.has(row.playlist_id)) {
                    throw new Error('simulator: FK violation (playlist missing)');
                }
                tracks.set(row.id, { ...row });
            }
        },
        async patchPlaylistRow(id: string, patch: Record<string, unknown>): Promise<void> {
            guard();
            const row = playlists.get(id);
            if (row === undefined) {
                return; // PATCH on missing = 0 rows, success
            }
            for (const [key, value] of Object.entries(patch)) {
                (row as unknown as Record<string, unknown>)[key] = value;
            }
        },
        async patchTrackRow(rowId, patch): Promise<void> {
            guard();
            const row = tracks.get(rowId);
            if (row === undefined) {
                return; // PATCH on missing = 0 rows, success
            }
            if (patch.id !== undefined && patch.id !== rowId) {
                if (tracks.has(patch.id)) {
                    throw new Error('simulator: primary key conflict on id rewrite');
                }
                tracks.delete(rowId);
                row.id = patch.id;
                tracks.set(patch.id, row);
            }
            if (patch.position !== undefined) {
                row.position = patch.position;
            }
            if (patch.added_at !== undefined) {
                row.added_at = patch.added_at;
            }
        },
        playlists,
        setOffline(next: boolean): void {
            offline = next;
        },
        tracks,
    };
}

/** The mocked postgrest module shape delegating to a hoisted simulator.
 * The session argument is accepted (the real lane's signature) and the
 * simulator trusts the test to have configured its user set. */
export function createPostgrestMock(holder: {
    cloud: CloudSimulator | null;
}): Record<string, unknown> {
    const need = (): CloudSimulator => {
        if (holder.cloud === null) {
            throw new Error('test bug: cloud simulator not installed');
        }
        return holder.cloud;
    };
    // Explicit wrappers (no generic arrow): esbuild's TS parser trips on
    // <Args extends ...> arrow generics in this position.
    return {
        deleteElementRows: (...args: unknown[]) => {
            const [, playlistId, addOpId] = args as [unknown, string, bigint];
            return need().deleteElementRows(playlistId, addOpId);
        },
        deletePlaylistRow: (...args: unknown[]) => {
            const [, id] = args as [unknown, string];
            return need().deletePlaylistRow(id);
        },
        deleteTrackRow: (...args: unknown[]) => {
            const [, rowId] = args as [unknown, string];
            return need().deleteTrackRow(rowId);
        },
        fetchPlaylistRows: (...args: unknown[]) => {
            void args;
            return need().fetchPlaylistRows();
        },
        fetchTrackRows: (...args: unknown[]) => {
            const [, playlistIds] = args as [unknown, string[]];
            return need().fetchTrackRows(playlistIds);
        },
        insertPlaylistRows: (...args: unknown[]) => {
            const [, rows] = args as [unknown, Array<Record<string, unknown>>];
            return need().insertPlaylistRows(rows);
        },
        insertTrackRows: (...args: unknown[]) => {
            const [, rows] = args as [unknown, CloudTrackTableRow[]];
            return need().insertTrackRows(rows);
        },
        patchPlaylistRow: (...args: unknown[]) => {
            const [, id, patch] = args as [unknown, string, Record<string, unknown>];
            return need().patchPlaylistRow(id, patch);
        },
        patchTrackRow: (...args: unknown[]) => {
            const [, rowId, patch] = args as [
                unknown,
                string,
                { added_at?: string; id?: string; position?: number },
            ];
            return need().patchTrackRow(rowId, patch);
        },
    };
}

/** The block lower bound for an add op id (mirrors element-id packing). */
function blockLow(addOpId: bigint): string {
    const a = addOpId.toString(16).padStart(16, '0');
    const b = '0000000000000000';
    return `${a.slice(0, 8)}-${a.slice(8, 12)}-${a.slice(12, 16)}-${b.slice(0, 4)}-${b.slice(4)}`;
}
