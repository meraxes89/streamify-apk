# Adding a Feature — Playbooks

Step-by-step recipes for the most common changes. Each recipe lists
the exact files to touch, in order. When in doubt about *where*
something belongs, check [ARCHITECTURE.md](ARCHITECTURE.md) §3 rules.

---

## Recipe 1 — Add a new screen

1. **Create the screen** — `app/.../ui/screens/MyScreen.kt`
   ```kotlin
   @Composable
   fun MyScreen(onBack: () -> Unit, vm: MyViewModel = viewModel()) {
       val state by vm.state.collectAsState()
       /* ... */
   }
   ```
2. **(If it needs state) create its ViewModel** — `viewmodel/MyViewModel.kt`;
   get dependencies via `AppGraph` (never construct repositories inline).
3. **Register the route** — `navigation/AppNavGraph.kt`:
   add a route constant, a composable entry, and navigation args.
4. **Wire navigation UI** — add the entry point (a button in a screen,
   or an item in `ui/components/BottomNavBar.kt`).
5. **Test** — pure logic (VM transformations, mappers) belongs in
   `app/src/test/` as a JVM test; the CI `jvm-unit-suite` shard runs
   the whole suite automatically.
6. **Docs** — add the screen to the index in `ui/screens/README.md`.

## Recipe 2 — Add a new data provider (e.g. another music backend)

1. **Create the package** — `data/<provider>/` with a README.
2. **Client** — `data/<provider>/<Provider>Client.kt`: transport only
   (OkHttp/serialization), no business logic.
3. **Models** — provider DTOs live beside the client; convert into
   `data/models/Track` at the boundary via a `toTrack()` mapper.
4. **Identity** — route ingested tracks through `data/discovery/`
   (CAD-ID + fuzzy gates) before they enter the catalog.
5. **Repository glue** — if it affects the local catalog, add the
   surface to `data/repository/TrackRepositoryApi` + implementation.
6. **Wiring** — any cross-provider flow (e.g. ingest → cloud sync)
   is orchestrated in a ViewModel, never inside a client.
7. **Secrets** — read keys from `BuildConfig`/native key pools (see
   `data/network/ZhipuAiEngine` for the pooled-key pattern). Never
   commit new plaintext secrets; flag them for rotation instead.

## Recipe 3 — Add a field to `Track`

1. Add the property to `data/models/Track.kt` **with a default value**
   (serialization compatibility).
2. Update mapping sites: `data/repository/TrackRepository` (SQLite
   cursor mapping), `data/supabase/SupabaseTracksClient` (DTO ↔ model),
   native row mappers if the Rust core reads the column.
3. If it changes the DB schema: bump `native/storage` schema version
   and add a migration path.
4. Update `supabase/schema.sql` if the cloud row changes.
5. Grep usages of the constructor and fix call sites.

## Recipe 4 — Add a UI component

- **Used by 2+ screens?** → `ui/components/` (+ index it in that README)
- **YTM-styled only?** → `ui/components/yt/`
- **Single-screen helper?** → keep it private in the screen file.

Component rules (perf contracts from v1.1.0):
- Composables receive state as parameters; hoist events as lambdas.
- Lazy lists: **stable `key`** per item (canonical id, never index).
- Images: pass explicit decode sizes at small-artwork call sites.
- Animations: gate on visibility/lifecycle (see `ui/animations/`).

## Recipe 5 — Add a new native (JNI) function

1. Declare in `data/NativeBridge.kt`:
   `external fun nativeFoo(input: String): ByteArray`
2. Implement in the right engine:
   - I/O / auth / PTP / tagging → `rust/src/jni_bridge.rs`
     `#[no_mangle] pub unsafe extern "C" fn Java_com_streamify_app_data_NativeBridge_nativeFoo(...)`
   - DSP / physics / storage → `native/jni/jni_bridge.cc`
3. Convert with the existing JNI helpers in those files; return error
   codes, never propagate panics (`catch_unwind`).
4. Kotlin side wraps the call in `runCatching` + `SLog` handling.
5. Add a native test in the matching CI shard target
   (`native/dsp/*_test`, `rust/tests/*`).

## Recipe 6 — Add a background job

1. **Engine maintenance (sync, compute)** → `media/ingestion/`:
   extend a `CoroutineWorker`, enqueue with constraints from
   `StreamifyApp`/`MainActivity`.
2. **User-visible download** → `worker/DownloadWorker.kt` pattern
   (foreground, notification progress).
3. Long-running jobs should observe `media/cache` eviction policy
   rather than inventing their own.

## Recipe 7 — Add a Supabase table / RPC

1. Write the migration in `supabase/migrations/` (or `supabase/sql/`).
2. Update `supabase/schema.sql` to the new canonical shape.
3. Add/extend the focused client in `data/supabase/` — do **not**
   grow `SupabaseClient.kt` (that's the v1.1.0 god-object rule).
4. Update `SupabaseModels.kt` DTOs to match the row shape.

## Recipe 8 — Change the theme

All tokens are in `ui/theme/` (`StreamifyColors`/`StreamifyDimens`/
`StreamifyType`). Screens wildcard-import `ui.theme.*`, so a token
rename is a mechanical find-and-replace. New tokens: add to `Color.kt`
/ `Dimens.kt` and expose via the `Streamify*` object in `Theme.kt`.

---

## Before you open the PR

- [ ] `./gradlew assembleDebug` compiles locally (or let CI do it).
- [ ] New JVM tests exist for new pure logic — the `jvm-unit-suite`
      shard runs them automatically.
- [ ] READMEs touched: the folders you added files to.
- [ ] No new hardcoded secrets; no `printStackTrace` (use `SLog`).
- [ ] Serialization-critical packages untouched:
      `data/models/`, `ui/models/`, `data/NativeBridge` (JNI pin).
- [ ] CHANGELOG.md entry under **Unreleased**.
