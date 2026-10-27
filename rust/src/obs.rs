//! Observability scaffolding — tracing + metrics with per-platform
//! subscribers (multiplatform Phase 3 / MP-C15, Alpha scope).
//!
//! This module provides the Rust-core side of the observability
//! contract: ONE portable initialization entry
//! ([`init_observability`]) that installs a `tracing` subscriber
//! (formatted logs, `RUST_LOG`/`STREAMIFY_LOG` env filtering) plus the
//! platform-native sink when the corresponding feature is enabled:
//!
//! | Platform | Feature flag | Subscriber crate | Native surface |
//! |---|---|---|---|
//! | Android | `obs-android` | `tracing-android` | logcat |
//! | macOS / iOS | `obs-oslog` | `tracing-oslog` | unified logging (os_log) |
//! | Linux | `obs-journald` | `tracing-journald` | systemd journald |
//! | Windows | `obs-etw` | `tracing-etw` | Event Tracing for Windows |
//!
//! The platform layers are OPTIONAL feature-gated dependencies (default
//! off): the artifact matrix selects exactly one per target (the
//! `.aar` enables `obs-android`, the desktop JVM bundle enables the
//! host OS layer, the iOS XCFramework enables `obs-oslog`), keeping
//! cross-compilation hermetic and the default host build lean.
//!
//! The CLIENT-SIDE initialization (calling this from `StreamifyApp`, the
//! crash-telemetry wiring and the Sentry bridge) is Engineer Beta's
//! Phase 3 item (MP-C15's client half); this module is the sanctioned
//! core surface Beta calls.
//!
//! `metrics` façade: `register_core_metrics()` describes the canonical
//! metric instruments the core emits (engine snapshot pushes, media
//! command outcomes, SRC stages). With no exporter installed the
//! macros compile to (near) no-ops; the desktop/Android shells install
//! the recorder at process init.

use tracing_subscriber::EnvFilter;

/// Build the platform-native layer for the subscriber type `S`, when the
/// matching `obs-*` feature is enabled. `Option<L>` itself implements
/// `Layer`, so the composition chain stays uniform across platforms.
///
/// Generic over `S` because the layer's position in the chain determines
/// its subscriber parameter (a pre-boxed `dyn Layer<Registry>` would only
/// compose as the first layer).
fn platform_layer<S>() -> Option<Box<dyn tracing_subscriber::Layer<S> + Send + Sync>>
where
    S: tracing::Subscriber + for<'a> tracing_subscriber::registry::LookupSpan<'a> + Send + Sync,
{
    #[cfg(all(feature = "obs-journald", target_os = "linux"))]
    {
        use tracing_subscriber::Layer as _;
        match tracing_journald::layer() {
            Ok(layer) => Some(layer.boxed()),
            Err(e) => {
                // No journald socket (container/CI): degrade to fmt-only
                // rather than failing process init.
                eprintln!("streamify obs: journald layer unavailable ({e}); fmt-only");
                None
            }
        }
    }
    #[cfg(all(feature = "obs-oslog", target_os = "macos"))]
    {
        use tracing_subscriber::Layer as _;
        Some(tracing_oslog::layer().boxed())
    }
    #[cfg(all(feature = "obs-android", target_os = "android"))]
    {
        use tracing_subscriber::Layer as _;
        Some(tracing_android::layer().boxed())
    }
    #[cfg(all(feature = "obs-etw", target_os = "windows"))]
    {
        use tracing_subscriber::Layer as _;
        // tracing-etw emits through the ETW provider registered by the
        // shell; the layer constructor is infallible on Windows.
        Some(tracing_etw::layer().boxed())
    }
    #[cfg(not(any(
        all(feature = "obs-journald", target_os = "linux"),
        all(feature = "obs-oslog", target_os = "macos"),
        all(feature = "obs-android", target_os = "android"),
        all(feature = "obs-etw", target_os = "windows"),
    )))]
    {
        None
    }
}

/// Initialize the global tracing subscriber for the Rust core.
///
/// Layers, innermost first: the platform-native layer (feature-gated,
/// see the module table), the `EnvFilter` (`STREAMIFY_LOG` if set,
/// falling back to `RUST_LOG`, falling back to `info` for the crate),
/// and the portable compact `fmt` layer — so SOME log surface always
/// exists, and the native sink carries the structured events when the
/// shell compiled it in.
///
/// Idempotent: a second call (double init, test harness overlap) logs a
/// note and returns `Ok(())` — the first installed subscriber wins for
/// the process lifetime.
pub fn init_observability() -> Result<(), crate::contract::EngineError> {
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;

    let filter = EnvFilter::try_from_env("STREAMIFY_LOG")
        .or_else(|_| EnvFilter::try_from_env("RUST_LOG"))
        .unwrap_or_else(|_| EnvFilter::new("info"));

    // Compose: registry -> platform layer (optional) -> env filter ->
    // compact fmt. Each `with` requires the layer to implement Layer<S>
    // for the composition-so-far, which is why the platform layer is
    // built generic (see `platform_layer`).
    let subscriber = tracing_subscriber::registry()
        .with(platform_layer())
        .with(filter)
        .with(tracing_subscriber::fmt::layer().compact());

    match subscriber.try_init() {
        Ok(()) => {
            tracing::info!(
                contract_version = crate::contract::CONTRACT_VERSION,
                "streamify core observability initialized"
            );
            Ok(())
        }
        Err(_) => {
            // Already initialized (double init is a wiring smell, not a
            // fault): keep the existing subscriber.
            eprintln!("streamify obs: global subscriber already set; keeping it");
            Ok(())
        }
    }
}

/// Describe the core's canonical metric instruments (metrics façade).
///
/// Describing registers names/units/descriptions with whatever recorder
/// the shell installed; with no recorder it is a no-op. Shells surface
/// these in their telemetry dashboards (MP-C15's client half).
pub fn register_core_metrics() {
    use metrics::{describe_counter, describe_gauge, Unit};

    describe_counter!(
        "streamify_engine_snapshots_pushed",
        Unit::Count,
        "Coalesced EngineSnapshot dispatches to registered listeners"
    );
    describe_counter!(
        "streamify_engine_media_commands",
        Unit::Count,
        "Reverse-plane media commands accepted by the engine"
    );
    describe_counter!(
        "streamify_engine_media_commands_debounced",
        Unit::Count,
        "Media commands swallowed by the 50-150ms hardware debounce"
    );
    describe_counter!(
        "streamify_engine_ffi_panics_marshaled",
        Unit::Count,
        "Rust panics caught by the FFI shield and marshaled to EngineError::Internal"
    );
    describe_gauge!(
        "streamify_src_canonical_rate_hz",
        Unit::Count,
        "Canonical internal pipeline rate (48000 Hz by contract; Unit has no Hertz variant — the description carries the unit)"
    );
    describe_gauge!(
        "streamify_engine_revision",
        Unit::Count,
        "Latest engine snapshot revision (monotonic)"
    );
}
