# P4B: Jam live-queue UI, EQ DSP + visualizer, brand theming, Streamify settings

- **Status:** implemented (branch `ws-w/p4b-jam-eq-theming`)
- **Scope:** `front-end/feishin` only (fast front-end CI: Feishin CI + preflight + CI gate; CodeQL and native lanes are path-gated away)
- **Zero-overlap contract:** no touches to `streamify-downloads`, `streamify-wrapped`, `streamify-debug`, `front-end/crates/streamify-wasm/`, `front-end/web/src/**`, `.github/workflows/**`, `shared/**`, or upstream `features/login|servers|visualizer`, `components/`, or theme definition files. Every upstream file touched is logged in `front-end/feishin/PATCHES.md` (patches 24-31).

## 1. The Jam listening room: host/follower sync protocol

### 1.1 Wire alignment (what we reuse, unchanged)

The listening-room lane rides the exact infrastructure the web client and the Android engine share:

- **Room codes:** 6 chars from the unambiguous alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (I L O 0 1 excluded), always uppercase; web invite links are `?jam=<CODE>` (the web app's hash-router format), and bare codes are accepted.
- **Op journal:** queue mutations cross ONLY through the `jam_mutation` SECURITY DEFINER RPC (8 `p_*` params, u64s as decimal strings - the bridge discipline) and arrive back via Realtime `postgres_changes` on `public.jam_ops` filtered by `session_code=eq.<CODE>` (the table is in the `supabase_realtime` publication; `UNIQUE(session_code, op_id)` dedupes at-least-once delivery).
- **The 48-byte op row:** the codec, the op-id mint (hybrid logical clock, `unix_ms << 16 | counter`, clock-step-back ratchet), the fractional indexing engine, and the cad-id hasher are vendored from P3A (`streamify/jam/*.ts`, PR #190) with byte-identical semantics - pinned against `fixtures/jam_golden_vectors.json` (the `rust_48` image) in the test suite. This branch is rebased onto the merged #190/#191 main, and the consolidation is DONE: `api/streamify/jam-live/jam-live-wire|cad|frac` are thin re-exports of `streamify/jam/*` (one frozen semantic, one implementation), with only the listening-room additions (journal-row normalization, policy bits) local. The golden-vector tests now pin #190's actual codec through the re-exports.
- **Membership:** `jam_touch_presence` every 15 s (the membership fence); the live roster view is Supabase Realtime Presence on the same channel, keyed by device id.
- **Host lease:** the host renews via `jam_heartbeat(sessionId, posMs, monoMs)` on the same 15 s cadence; a `DEMOTED` verdict latches a UI warning (takeover stays server-side and out of scope, matching the web lane's honest gap).

### 1.2 What P4B adds: the playback-delta lane

The web transport deliberately deferred playback sync ("position sync rides the engine's existing DAC-clock snapshot lane"). Feishin has no wasm engine, so P4B implements the pragmatic protocol the JAM_ENGINE_SPEC catalog already sketched, on the SAME channel's broadcast lane (the web transport's channel config already carries `broadcast: { self: false }`):

- **`jam_sync`** (event `jam_sync`): `{v:1, kind, hostEpoch, hostUserId, emittedAt, positionMs, hostOnlySkipSeek, track?, trackAddOpId?}` with `kind ∈ {track, play, pause, seek, tick, end}`.
  - The HOST subscribes to the player store (`subscribeCurrentTrack` / `subscribePlayerStatus` / `subscribePlayerSeekToTimestamp`, through the injectable `JamPlayerBridge`) and broadcasts deltas as they happen, plus a 5 s `tick`.
  - FOLLOWERS apply them: `track` reloads the local single-track queue and seeks (`setQueue` + `mediaSeekToTimestamp`; if the seek races the source load, the next tick's drift corrector re-lands it), `play`/`pause` map straight through, `end` parks the session as ended.
  - **Epoch fencing:** messages with `hostEpoch` below the last-seen epoch are ignored (the session row's `host_epoch` seeds the floor).
- **`jam_meta`** (event `jam_meta`): the out-of-band track payload (the JamTrackCodec discipline - the 48-byte op row carries only the cad id). The full playable `QueueSong` payload (minus client-local `_uniqueId`) rides beside the op so followers can display AND play without a catalog round-trip.
- **`jam_control`** (event `jam_control`): `request` (a follower's advisory seek/skip: the host applies it only when the room's permission posture allows) and `snapshot` (the host's answer to presence-join diffs: ordered queue + metadata + playback state, idempotent to apply - late joiners converge instantly).

### 1.3 Drift correction (the honest feishin-web posture)

One-way latency is unbilled (Cristian-lite: the message carries the host's emit timestamp; under-correction is the safe direction). The corrector is threshold-based:

- **Hard window (2 s):** seek immediately.
- **Soft window (750 ms):** seek after 3 consecutive offences; any in-window observation resets the count.

The spec's Kalman PLL / speed-scalar nudge (0.98-1.02x) stays deferred exactly like the web lane - documented, not pretended.

### 1.4 The queue CRDT

The fold (pure, in `jam-queue-crdt.ts`) follows the spec's §8 register discipline:

- element identity = the ADD op's id; ordered view sorts live elements by `(frac, add_op_id)`;
- **REMOVE tombstones are absorbing** (remove-before-add mints a tombstone shell; late adds/reorders on tombstones are no-ops in any order);
- **REORDER is an order-register LWW keyed by the counter-free `(clock_ms, frac_bits)`** - excluding the op counter bits so the rule computes identically from an op and from a durable row;
- **VOTE registers max-by-op-id per sender** (one vote per participant, re-votes overwrite, counts converge);
- Vote/reorder-before-add deliveries mint **live shells** that the late Add merges into (votes are NOT ordering-authoritative: their shells seed the minimal register key, so the Add always wins it back - that keeps the register convergent in every order);
- `appliedOps` makes the fold idempotent under redelivery;
- a `needsRebalance` latch (sub-ULP neighbour gaps) triggers the host's **respace** compaction (fresh `0.5 + i` grid, one Reorder per element).

The convergence battery folds shuffled op streams in both orders (plus duplication) and asserts `canonicalJamQueueState` equality - the same two-replica proof P3A established for playlists (both suites green side by side on the rebased branch).

### 1.5 Permissions (soft, by design)

`streamify-settings` carries the defaults for rooms I host: `jamOpenQueue` (participants mutate the queue) and `jamHostOnlySkipSeek` (follower `request` messages are ignored). Enforcement of queue mutations is **not** server-side: `jam_mutation` accepts any participant op (the journal is append-only by design; changing that needs SQL, which is outside this phase's boundary). The hostOnlySkipSeek gate IS enforced host-side (requests are simply not applied). Both are documented honestly in the settings UI copy.

### 1.6 The escape hatch

Leaving a room (`controller.leave()`) never touches playback: intervals clear, the channel unsubscribes, jam state resets, and local audio keeps running - "leave" and "desync" are the same deliberate action.

## 2. EQ DSP + visualizer: the Web Audio wiring

### 2.1 The graph

Upstream already builds `gains[0..1] -> preamp -> N x peaking biquad -> compressor -> destination` when `settings.playback.webAudio` is on (and already sets `crossOrigin='anonymous'` on both media elements since the fork base). P4B's graph manager (`use-streamify-eq-graph`) **rebuilds the chain downstream of the gains** through the app's own `WebAudioContext` setter:

```
<audio> x2 -> MediaElementSource -> gains[0..1]        (upstream, untouched)
    gains -> preampGain -> 10 x peaking BiquadFilterNode -> limiter -> destination
    limiter -> probe AnalyserNode                        (watchdog sink)
```

- `webAudio.dsp` is replaced wholesale (`setWebAudio({...webAudio, dsp: ours})`), so the upstream EQ settings panel (which mutates whatever `dsp` is current) and the visualizer tap (which reads `dsp.compressor`) keep working against our nodes - same shapes, better chain.
- Band/preamp values write through `settings.playback.equalizer` (the upstream schema): ours is the richer preset layer (10-band ISO bank, Q support), the upstream cold-start builder restores it, the upstream panel edits it, and both stay coherent.
- The limiter engages only when EQ is on (threshold -1 dB, ratio 20, the safety net for preamp + boost); disabled it is mathematically transparent (ratio 1, threshold 0 - the upstream pattern).

### 2.2 The CORS guardrail (defense in depth)

1. `crossOrigin='anonymous'` on both elements (upstream) means a CORS-unclean rung fails LOUDLY at load - the P2-A source-error ladder rotates rungs; audio never silently mutes through a tainted `MediaElementAudioSourceNode`. (The resolver's 1-byte Range probes are also CORS-gated, so unclean rungs are ranked out before playback.)
2. **The watchdog** probes the chain output (the limiter's fan-out analyser) once per second: while playback is running, unmuted, and advancing, three consecutive all-zero probes engage the **hard bypass** - the gains rewire straight to `context.destination`, the DSP chain leaves the path completely, audio keeps flowing. The state surfaces in the EQ panel.
3. **The attach seam** (web-player.tsx, two lines, PATCHES 26): while a bypass is latched, NEW media-element sources route direct-to-destination - a fresh track never enters a graph we declared unhealthy.
4. **One retry per track change** (the next rung may be CORS-clean); a repeat strike within the same track latches the bypass until the track changes again.

### 2.3 The visualizer tap

`get-visualizer-audio-nodes` now returns `[webAudio.dsp.compressor]` for WEB playback when a chain is attached (falling back to the pre-EQ `gains` otherwise; the LOCAL mpv loopback path is untouched). Both per-slot gains merge into the chain upstream of the tap, so the single post-DSP node covers the src1/src2 crossfade - audiomotion and Butterchurn both connect their analysers to every returned node and see the equalized signal.

### 2.4 Presets

Built-ins: Flat, Bass Boost, Vocal, Acoustic, Electronic, Late Night (10-band ISO 31 Hz..16 kHz). Custom presets persist in `localStorage` (`streamify.eq.v1`, hand-rolled versioned blob). Band gains and preamp clamp to +/-12 dB before ANY persistence - a stale/hand-edited blob can never blow up the chain.

## 3. Brand theming (upstream-safe)

- **No upstream theme file is touched** (the hygiene contract: themes evolve upstream, conflict-free).
- The Streamify brand theme (the StreamifyTheme port from `front-end/web/src/theme/tokens.css`: OLED true-dark `#030303`/`#0f0f0f`/`#181818` surfaces, brand red `#ff0033` primary, Mantine 9 `generateColors` primary tuple + OLED dark ramp, Inter typography) registers **additively** through the runtime seam upstream built for third-party themes: `setCustomThemeRegistry` + the custom-themes store metas. In web builds the custom registry is exclusively ours; in electron builds disk-loaded customs are re-fetched and composed WITH ours before the registry swap, and a store subscription re-registers after any file-watch update.
- `getAppTheme('streamifyBrand')` resolves through the normal registry path, so the stock theme picker lists it next to the built-ins - every upstream theme stays selectable.
- **Default-on-activation:** when a `ServerType.STREAMIFY` session becomes active AND the brand toggle is on AND the user has not pinned a theme choice, the stock default theme switches to the brand preset using the exact three calls the upstream picker makes (`setSettings`, `setColorScheme`, electron `localSettings.themeSet`). Any later explicit change away pins the choice forever - we never fight the user.

## 4. Streamify settings

`features/streamify-settings/` (route `/streamify-settings`, PATCHES 22-23) with five sections, persisted in `localStorage` (`streamify.settings.v1`, normalized through one validator):

- **Audio & Playback:** codec ceiling (Opus/AAC/Auto - a persisted forward preference; the resolver ladder ranks internally today, and the consumption seam is a future `preferredCodec` option on `getStreamUrl` exactly like the relay knob), crossfade duration (0-12 s, applied live via the player store), gapless toggle (applied via `setTransitionType`), EQ quick-link (opens the drawer on the equalizer tab).
- **Network & Relay:** custom `STREAMIFY_RELAY_BASE` override (validated absolute http(s) URL; priority 0 in `relayBase.ts`, above the deployment knob; cache-busted on change) + a reachability health check (HEAD with a 5 s abort; any HTTP answer proves the worker is up).
- **Jam & Privacy:** default room permissions (open queue / host-only skip+seek - see §1.5) and the scrobble tracking toggle.
- **Theme:** the brand-theme default toggle.
- **Account & Session:** signed-in Google profile status, manual session refresh, sign-out - all through the P1 session seam (`streamify-supabase.ts`), no upstream auth file touched.

**The scrobble gate (landed):** the controller's `scrobble` endpoint requires `settings.scrobbleEnabled` on its cloud-send adapter (the `canSend` lane). Local session bookkeeping keeps running; queued outbox entries drain on re-enable - the same at-least-once posture the offline outbox already has. Wired post-#191-merge exactly as planned pre-rebase (PATCHES 31).

## 5. Verification

`cd front-end/feishin && pnpm run lint && pnpm run test` (network-free):

- **103 new tests (10 files; 260 total with the merged P3A/P3B suites green side by side):** the golden-vector codec lock (rust_48 byte image + FNV seal + tamper/pad rejection + DTO/journal-row round-trips + the op-id ratchet), the fractional-index anchors/walls/collisions, the cad-id pins + normalization, the CRDT convergence battery (both delivery orders + duplication: adds, absorbing tombstones, reorder LWW, per-sender votes, respace plan, idempotency), the protocol validators (room codes, invite links, drift thresholds, epoch fencing, message decoding), the session-controller lifecycle against a fake transport + fake bridge (create/join/bootstrap, op minting, host delta broadcast, follower application + drift seek, permission-gated requests, outbox retry, presence snapshots, heartbeat demotion, leave/desync cleanup, end handling), the EQ math (clamps, preset bank, biquad response, curve) + preset store persistence, the settings validator + relay URL validation + persistence round-trip + health state machine, and the brand-theme registration contract (additive, idempotent, re-registering, palette pinned).

CI: Feishin CI runs the same lint + tests plus `build:web` (not run locally per the 4 GB constraint).

## 6. Known boundaries (honest list)

- Server-side enforcement of room permissions, host takeover (epoch-fenced HOST_TAKEOVER), and the Kalman PLL are deferred (same posture as the web lane).
- The codec ceiling is persisted and validated but does not yet steer the resolver ladder (needs a `preferredCodec` option on the vendored `getStreamUrl` - a resolver-tree change owned by that lane).
- Same-user-two-devices both claiming host is an acknowledged edge (both broadcast; epoch fencing keeps followers consistent, but the UX is undefined until takeover lands).
