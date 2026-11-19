# `di/` — Dependency Injection

Hand-rolled, compile-time DI (no framework).

## Files

| File | What it is |
|---|---|
| `AppGraph.kt` | Process-wide dependency graph — the single wiring point. Swap members in tests. |
| `DispatcherProvider.kt` | Coroutine dispatcher abstraction (inject instead of hardcoding `Dispatchers.IO`). |

Convention: constructors stay dumb; wiring happens only in `AppGraph`.
New subsystem? Add a `lazy val` here, never a service-locator lookup.
