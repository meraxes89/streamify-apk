/**
 * streamify/ids.ts - the stable ID scheme mapping cloud/jam identities onto
 * feishin entity IDs (P1 kickoff: "define a stable ID scheme early -
 * everything downstream hangs off it").
 *
 * Scheme (all deterministic Java String.hashCode derivations, so IDs are
 * stable across sessions, devices and the Android/web apps):
 *
 *   song        trk_<|hash|>   - the tracks table id itself, already the
 *                               `trk_<|abs(javaHashCode(title_artist))|>`
 *                               derivation the Android app upserts and the
 *                               web app mirrors (a like from any surface
 *                               lands on the same row). Used verbatim.
 *   album       alb_<|hash|>   - hash of `<albumKey>__<artistKey>`
 *   album artist art_<|hash|>  - hash of `<artistKey>`
 *   genre       gen_<|hash|>   - hash of the lowercase genre name
 *
 * Album identity is (album name, album artist): two artists' "Greatest
 * Hits" stay separate albums. The hand-rolled web UI groups by name only
 * (a documented simplification there); feishin's album model carries an
 * album artist per album, so the pairing is the honest mapping here - the
 * delta is recorded in decisions/p1/data-mapping.md.
 *
 * Unknown buckets: a track without an album groups under "Unknown Album"
 * (per its artist); a track without an artist under "Unknown Artist".
 */

/** Java String.hashCode - parity with the Android app's derivations. */
export function javaStringHash(s: string): number {
    let h = 0;
    for (let i = 0; i < s.length; i += 1) {
        h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
    }
    return h;
}

export const UNKNOWN_ALBUM = 'Unknown Album';
export const UNKNOWN_ARTIST = 'Unknown Artist';

/** feishin album artist id for an artist string. */
export function albumArtistIdOf(artist: null | string | undefined): string {
    return idFrom('art', artistKeyOf(artist));
}

/** The display name for an album bucket. */
export function albumDisplayNameOf(album: null | string | undefined): string {
    const trimmed = (album ?? '').trim();
    return trimmed === '' ? UNKNOWN_ALBUM : trimmed;
}

/** feishin album id for an (album, artist) pair. */
export function albumIdOf(
    album: null | string | undefined,
    artist: null | string | undefined,
): string {
    return idFrom('alb', albumKeyOf(album, artist));
}

/** The album grouping key (album name + artist key). */
export function albumKeyOf(
    album: null | string | undefined,
    artist: null | string | undefined,
): string {
    const albumKey = normalizeKey(album);
    const name = albumKey === '' ? UNKNOWN_ALBUM.toLowerCase() : albumKey;
    return `${name}__${artistKeyOf(artist)}`;
}

/** The display name for an artist bucket. */
export function artistDisplayNameOf(artist: null | string | undefined): string {
    const trimmed = (artist ?? '').trim();
    return trimmed === '' ? UNKNOWN_ARTIST : trimmed;
}

/** The artist grouping key ('unknown artist' bucket when blank). */
export function artistKeyOf(artist: null | string | undefined): string {
    const key = normalizeKey(artist);
    return key === '' ? UNKNOWN_ARTIST.toLowerCase() : key;
}

/**
 * The cloud track id for a (title, artist) pair - `trk_<|abs(hash)|>` over
 * `<title>_<artist>` lowercased/trimmed. This is BOTH the tracks table id
 * (what the Android app upserts, what the web app mirrors) AND the
 * `track_sig` the play-event lane records (AppShell passes cloudTrackId as
 * the signature), so play stats join onto library rows by plain id
 * equality - no fuzzy matching anywhere.
 */
export function cloudTrackIdOf(title: string, artist: string): string {
    const sig = `${title.trim().toLowerCase()}_${artist.trim().toLowerCase()}`;
    return `trk_${Math.abs(javaStringHash(sig))}`;
}

/** feishin genre id for a genre name. */
export function genreIdOf(name: string): string {
    return idFrom('gen', normalizeKey(name));
}

function idFrom(prefix: string, key: string): string {
    return `${prefix}_${Math.abs(javaStringHash(key))}`;
}

function normalizeKey(value: null | string | undefined): string {
    const trimmed = (value ?? '').trim().toLowerCase();
    return trimmed;
}
