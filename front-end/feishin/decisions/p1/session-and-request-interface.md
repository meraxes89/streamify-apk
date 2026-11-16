# P1 decision: the session seam and the read request interface

Status: PROPOSED (day-one agreement this PR asks Engineer A to confirm or amend)
Owner: Engineer B (reads), Engineer A (auth)

## The problem

Feishin routes every controller call through `apiClientProps.server` (a
`ServerListItemWithCredential` from the auth store). The P1 reads therefore
need to recover a usable Streamify session from that server object, without
A's auth flow existing yet, and without the api layer keeping hidden auth
state of its own.

## The contract (what A's `authenticate` must produce)

`StreamifyController.authenticate(url, body)` stays A's seam. When it lands,
it should return feishin's standard `AuthenticationResponse`:

```
{
    credential: JSON.stringify(StreamifyCredentialPayload),
    userId: <supabase user id>,
    username: <email or display name>,
    isAdmin: <from the profile / role lane>,
    ndCredential: undefined
}
```

`StreamifyCredentialPayload` (the versioned shape, defined and decoded in
`src/renderer/api/streamify/session.ts`):

```
{
    v: 1,
    supabaseUrl: string,     // the project URL
    anonKey: string,         // public anon key (RLS is the fence)
    accessToken: string,     // the user's JWT
    refreshToken: string,    // for the 401 -> refresh -> replay lane
    expiresAt: number,       // epoch seconds
    userId: string,
    email: string | null,
    isAdmin: boolean
}
```

Everything a read needs travels in the credential, so the reads stay
stateless against feishin's store (the same model as the Navidrome token
and the Subsonic token+salt string). The codec is shared code: A imports
`encodeStreamifyCredential`, the reads import `decodeStreamifyCredential`,
one file owns the shape.

Token expiry: the read lane (`cloud.ts` restCall) does one Supabase
refresh-token round-trip on a 401 and replays; if that fails it throws an
`unauthorized`-flavored error, which `use-server-authenticated` already
treats as the signal to re-run `authenticate` (A's flow). The reads never
write credentials back to the store; only A's flow does.

## The mock session (why reads are testable today)

When the credential is absent or not a v1 payload, `resolveStreamifySession`
falls back to deployment variables from `settings.js`:

- `STREAMIFY_SUPABASE_URL`
- `STREAMIFY_SUPABASE_ANON_KEY`
- `STREAMIFY_DEV_ACCESS_TOKEN` (optional, dev-only)

With URL + anon key and no token, the session is anonymous: the library
snapshot loads empty (RLS returns nothing for an anonymous role) and every
screen renders its honest empty state. With a dev token (generated
out-of-band for local development), the full read surface works before A's
flow lands. Unsubstituted `${...}` placeholders read as unset (same filter
upstream's env-settings parser applies).

## The read request interface (what the reads ignore)

Feishin's read endpoints receive `ReplaceApiClientProps<XArgs>`: the
controller glue (upstream `src/renderer/api/controller.ts`) resolves the
server by id, injects it, and merges `musicFolderId`. Streamify reads
consume `server` (credential), `serverId` (entity `_serverId`), and
`signal` (abort, threaded into the snapshot load). They ignore
`forceRemoteUrl` / `remoteUrl` (no remote/proxy distinction exists) and
`musicFolderId` (the library is a single flat collection; see
data-mapping.md).

## What this buys the end-of-phase integration

Nothing in the read paths knows how the session was obtained. When A's
flow lands, integration is: authenticate stores the payload, the snapshot
memo key changes (credential change invalidates), the next read loads the
signed-in library. "Sign in and see the catalog."
