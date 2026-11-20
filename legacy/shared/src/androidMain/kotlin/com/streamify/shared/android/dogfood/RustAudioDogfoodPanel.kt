package com.streamify.shared.android.dogfood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.streamify.shared.model.DogfoodTelemetryState
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-CUT01 / MP-UI03 / Beta): the Rust-audio dogfood observability
 * panel — the Android consumption of the shared presentation layer.
 *
 * Pure presentation over [DogfoodTelemetryState]: the host (the app's
 * debug-only Settings section) feeds the state collected from the REAL
 * telemetry surfaces (`AudioRoutePolicy.lastDecision`,
 * `DogfoodTelemetry.snapshot`, `RustAudioSettings.runtimeOverride`) and
 * this panel renders it with the SHARED theme tokens and typography — the
 * exact wiring the master plan's "consume shared components without
 * duplicating UI code between platforms" mandates. The panel itself never
 * touches a collector, an engine handle or an Android framework API, so it
 * renders identically anywhere and stays trivially testable.
 *
 * The runtime-override switch reflects the MP-HAL07 dogfood contract:
 * debug builds honor a developer-settings override (applies at the NEXT
 * playback session — clean switching semantics), release builds ignore it
 * entirely.
 */
@Composable
fun RustAudioDogfoodPanel(
    state: DogfoodTelemetryState,
    runtimeOverrideEnabled: Boolean,
    onRuntimeOverrideChange: (Boolean) -> Unit,
    runtimeOverrideEditable: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = StreamifyPalette.BgCard,
        ),
    ) {
        Column(
            modifier = Modifier.padding(StreamifySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Md),
        ) {
            Column {
                Text(
                    text = "Rust audio pipeline — dogfood (MP-CUT01)",
                    style = StreamifyTextStyles.title,
                    color = StreamifyPalette.TextMain,
                )
                Spacer(modifier = Modifier.height(StreamifySpacing.Xs))
                Text(
                    text = "USE_RUST_AUDIO two-week dogfood window: route decisions, " +
                        "underrun rate, DAC drift and cold-start latency are logged " +
                        "locally for the cutover review.",
                    style = StreamifyTextStyles.caption,
                    color = StreamifyPalette.TextSecondary,
                )
            }

            HorizontalDivider(color = StreamifyPalette.BorderSubtle)

            MetricRow(label = "Route", value = state.route)
            MetricRow(
                label = "Reason",
                value = state.routeReason,
                mono = true,
            )
            MetricRow(
                label = "Pipeline",
                value = if (state.pipelineLive) "live (f32 SPSC pull)" else "legacy Media3",
            )
            MetricRow(
                label = "Underrun rate",
                value = state.underrunRatePercent?.let { "%.2f%% of bursts".format(it) }
                    ?: "awaiting telemetry",
            )
            MetricRow(
                label = "DAC drift",
                value = state.dacDriftPpm?.let { "%+.1f ppm".format(it) }
                    ?: "awaiting clock samples",
            )
            MetricRow(
                label = "Cold start",
                value = state.coldStartMs?.let { "$it ms (route → first DAC sample)" }
                    ?: "measuring…",
            )
            MetricRow(
                label = "Frames pulled",
                value = "%,d".format(state.framesPulled),
            )
            MetricRow(
                label = "DAC clock samples",
                value = "%,d".format(state.dacClockSamples),
            )
            MetricRow(
                label = "Auto fallbacks",
                value = if (state.legacyFallbacks == 0L) {
                    "none — zero-crash guarantee holding"
                } else {
                    "${state.legacyFallbacks} (legacy Media3 engaged)"
                },
                valueColor = if (state.legacyFallbacks == 0L) {
                    StreamifyPalette.TextSecondary
                } else {
                    StreamifyPalette.PrimaryAccent
                },
            )

            HorizontalDivider(color = StreamifyPalette.BorderSubtle)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Runtime override (debug builds)",
                        style = StreamifyTextStyles.body,
                        color = StreamifyPalette.TextMain,
                    )
                    Text(
                        text = if (runtimeOverrideEditable) {
                            "Applies at the next playback session."
                        } else {
                            "Ignored: release builds ship the legacy pipeline."
                        },
                        style = StreamifyTextStyles.caption,
                        color = StreamifyPalette.TextTertiary,
                    )
                }
                Switch(
                    checked = runtimeOverrideEnabled,
                    onCheckedChange = if (runtimeOverrideEditable) {
                        onRuntimeOverrideChange
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color = StreamifyPalette.TextSecondary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = if (mono) StreamifyTextStyles.mono else StreamifyTextStyles.body,
            color = StreamifyPalette.TextTertiary,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = if (mono) StreamifyTextStyles.mono else StreamifyTextStyles.body,
            color = valueColor,
            modifier = Modifier.weight(0.62f),
        )
    }
}
