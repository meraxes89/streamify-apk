package com.streamify.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.streamify.app.BuildConfig
import com.streamify.app.media.audio.AudioRoutePolicy
import com.streamify.app.media.audio.DogfoodTelemetry
import com.streamify.app.media.audio.RustAudioSettings
import com.streamify.shared.android.dogfood.RustAudioDogfoodPanel
import com.streamify.shared.model.DogfoodTelemetryState

/**
 * Phase 5 (MP-CUT01 / MP-UI03 / Beta): the app-side host for the SHARED
 * dogfood panel.
 *
 * This is the Android consumption edge of the shared CMP module: the host
 * maps the REAL telemetry surfaces — [DogfoodTelemetry.snapshot],
 * [AudioRoutePolicy.lastDecision], [RustAudioSettings.runtimeOverride] —
 * onto the shared presentation model ([DogfoodTelemetryState]) and lets
 * the :shared-android panel render it. Zero presentation logic lives
 * here; zero collection logic lives there.
 *
 * Rendered only in debug builds (see the SettingsScreen insertion): the
 * MP-CUT01 dogfood window is a developer observability surface, and the
 * runtime-override switch is contractually debug-only.
 */
@Composable
fun RustAudioDogfoodSection() {
    val telemetry by DogfoodTelemetry.snapshot.collectAsState()
    val decision by AudioRoutePolicy.lastDecision.collectAsState()
    val runtimeOverride by RustAudioSettings.runtimeOverride.collectAsState()

    RustAudioDogfoodPanel(
        state = DogfoodTelemetryState(
            route = decision.route.name,
            routeReason = decision.reason,
            underrunRatePercent = telemetry.underrunRatePercent,
            dacDriftPpm = telemetry.dacDriftPpm,
            coldStartMs = telemetry.coldStartMs,
            framesPulled = telemetry.framesPulled,
            dacClockSamples = telemetry.dacClockSamples,
            pipelineLive = telemetry.pipelineLive,
            legacyFallbacks = telemetry.legacyFallbacks,
        ),
        runtimeOverrideEnabled = runtimeOverride,
        onRuntimeOverrideChange = { enabled ->
            RustAudioSettings.setRuntimeOverride(enabled)
        },
        runtimeOverrideEditable = BuildConfig.DEBUG,
    )
}
