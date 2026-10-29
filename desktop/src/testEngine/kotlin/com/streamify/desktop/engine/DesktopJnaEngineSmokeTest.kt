package com.streamify.desktop.engine

import com.sun.jna.NativeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.streamify.EngineEventListener
import uniffi.streamify.EngineSnapshot
import uniffi.streamify.MediaCommand
import uniffi.streamify.PlaybackState
import uniffi.streamify.StreamifyEngine
import uniffi.streamify.contractVersion
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Phase 4b deliverable 2 — Desktop JNA Engine Binding Verification (headless).
 *
 * MP-DEC-02 / MP-C12: the Desktop JVM runner must load the native engine in
 * **JNA mode** — the `streamify-jvm` lane: generated `uniffi.streamify`
 * proxies dispatching through `com.sun.jna` onto the platform dynamic library
 * (`streamify_core_rs.dll` / `libstreamify_core_rs.so`) — and NOT in Android
 * direct-JNI mode. On desktop targets the `jni` crate is cfg-gated out
 * (MP-C13), so the cdylib carries the `uniffi_*` C ABI and zero `Java_*`
 * exports: there IS no JNI lane here, and this suite proves the JNA lane
 * end-to-end without symbol-link errors or crashes.
 *
 * Verified invariants:
 *  1. the platform cdylib resolves through JNA under the pinned
 *     `streamify_core_rs` name;
 *  2. the Android JNI sink class is structurally absent from the desktop
 *     classpath;
 *  3. a live `contractVersion()` round-trip (the generated loader also runs
 *     `uniffiCheckContractApiVersion` at first touch — MP-C05 fail-fast ABI
 *     verification);
 *  4. `StreamifyEngine` initialization drives the one-way Idle → Buffering
 *     lifecycle transition;
 *  5. `EngineEventListener` registration receives the initial coalesced
 *     state push with a monotonic revision (MP-C02 / MP-DEC-04);
 *  6. basic lifecycle mutations (volume, debounced media commands per
 *     MP-C03 / MP-DEC-07) push strictly-increasing revisions.
 *
 * Hermetic degradation: the generated bindings exist only after
 * `generateDesktopUniffiBindings` has run (cargo on PATH — every CI desktop
 * job provides it). On toolchain-less hosts the suite SKIPS via a JUnit
 * assumption rather than failing, mirroring the app module's hermetic JVM
 * shard contract.
 */
class DesktopJnaEngineSmokeTest {

    private fun jnaBindingsOnClasspath(): Boolean = try {
        Class.forName(
            "uniffi.streamify.StreamifyEngine",
            false,
            DesktopJnaEngineSmokeTest::class.java.classLoader,
        )
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    @Test
    fun engineLoadsViaJnaAndPushesInitialState() {
        assumeTrue(
            "UniFFI desktop bindings not generated (no cargo toolchain?) — skipping JNA smoke",
            jnaBindingsOnClasspath(),
        )

        // ── 1. JNA mode: the platform dynamic library resolves through JNA ──
        val engineLibrary = NativeLibrary.getInstance(ENGINE_LIBRARY)
        assertNotNull(
            "JNA must resolve the platform engine library '$ENGINE_LIBRARY' " +
                "(jna.library.path / classpath resource must expose it)",
            engineLibrary,
        )

        // ── 2. Android direct-JNI mode is structurally absent ───────────────
        try {
            Class.forName(
                "com.streamify.core.native_.NativeAudioSink",
                false,
                javaClass.classLoader,
            )
            fail("Android JNI audio sink leaked onto the desktop classpath (MP-DEC-02 violation)")
        } catch (_: ClassNotFoundException) {
            // expected: the dogfood source set is Android debug-only
        }

        // ── 3. Fail-fast ABI verification (MP-C05) ──────────────────────────
        // A successful round-trip through the generated proxies proves the
        // JNA dispatch table is live (the loader has already executed
        // uniffiCheckContractApiVersion + checksums by this point).
        val abi = contractVersion()
        assertTrue("contract_version must be a positive ABI marker (got $abi)", abi > 0u)

        // ── 4. Engine lifecycle: construct + initialize (Idle → Buffering) ──
        // uniffi-rs 0.28.3 object naming: the Rust-implemented object's
        // CONCRETE class is `StreamifyEngine` (object_names() appends Impl
        // only to foreign/callback objects); `StreamifyEngineInterface` is
        // the abstract side nobody instantiates.
        val engine = StreamifyEngine()
        engine.initialize()
        assertEquals(
            "initialize() must advance the one-way Idle -> Buffering transition",
            PlaybackState.BUFFERING,
            engine.getPlaybackState(),
        )

        // ── 5. Listener registration pushes the initial state (MP-C02) ──────
        val snapshots: LinkedBlockingQueue<EngineSnapshot> = LinkedBlockingQueue()
        val listenerCount = engine.addEventListener(
            object : EngineEventListener {
                override fun onEngineEvent(snapshot: EngineSnapshot) {
                    snapshots.offer(snapshot)
                }
            },
        )
        assertTrue("first listener registration must report count >= 1 (got $listenerCount)", listenerCount >= 1u)

        val initial = snapshots.poll(PUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: throw AssertionError(
                "EngineEventListener never received the initial state push — " +
                    "registration must bump the revision and wake the dispatcher (MP-DEC-04)",
            )
        assertTrue("initial snapshot rev must be monotonic (>= 1), got ${initial.rev}", initial.rev >= 1uL)
        assertEquals(
            "initial push must carry the post-initialize lifecycle state",
            PlaybackState.BUFFERING,
            initial.state,
        )

        // ── 6. Lifecycle mutations + debounced media commands (MP-C03) ──────
        engine.setVolume(0.5f)

        val applied = engine.onMediaCommand(MediaCommand.TogglePlayPause)
        assertTrue("first TogglePlayPause must be applied", applied)

        val swallowed = engine.onMediaCommand(MediaCommand.TogglePlayPause)
        assertFalse(
            "an IDENTICAL command inside the 50-150 ms debounce window must be swallowed",
            swallowed,
        )

        assertEquals(
            "TogglePlayPause from Buffering must land on Playing",
            PlaybackState.PLAYING,
            engine.getPlaybackState(),
        )

        val mutated = snapshots.poll(PUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?: throw AssertionError(
                "no coalesced snapshot followed the accepted mutations — " +
                    "every accepted mutation must wake the dispatcher (MP-DEC-04)",
            )
        assertTrue(
            "snapshot rev must strictly increase across mutations (${initial.rev} -> ${mutated.rev})",
            mutated.rev > initial.rev,
        )
    }

    private companion object {
        /** Crate [lib] name — pinned via uniffi.component.streamify.libraryOverride. */
        private const val ENGINE_LIBRARY = "streamify_core_rs"

        /** Generous bound for the dedicated dispatch thread's first pass. */
        private const val PUSH_TIMEOUT_SECONDS = 10L
    }
}
