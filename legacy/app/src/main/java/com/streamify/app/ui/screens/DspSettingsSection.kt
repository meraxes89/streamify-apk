package com.streamify.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.media.audio.DspPreferences
import com.streamify.app.ui.theme.*

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUDIOPHILE DSP SETTINGS SECTION (BEHIND.md Gaps #39 + #41, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The dedicated "Audio Quality & DSP" surface inside SettingsScreen:
 *
 *   • Loudness normalization toggle + calibrated target slider
 *     (−23.0 LUFS EBU R128 … −11.0 LUFS loud-party, default −14.0).
 *   • Gapless playback switch (zero-silence transitions).
 *   • Crossfade duration slider, smooth 0 s → 12 s.
 *   • Mono audio switch + stereo balance slider (−100 % … +100 %).
 *   • True-peak limiter ceiling toggle (−1.0 dBFS EU compliance).
 *
 * Every change writes [DspPreferences] (JSON blob, SharedPreferences) and
 * pushes live values into the render path via [DspPreferences.apply] — the
 * same call the service uses on cold start, so preferences survive restarts
 * and take effect within one audio buffer.
 */
@Composable
fun DspSettingsSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences(DspPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    var prefsState by remember { mutableStateOf(DspPreferences.load(prefs)) }

    fun update(mutate: (DspPreferences) -> DspPreferences) {
        prefsState = mutate(prefsState).also {
            DspPreferences.save(prefs, it)
            it.apply()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Loudness normalization (Gap #39) ──────────────────────────
            SettingToggleRow(
                title = "Loudness Normalization",
                subtitle = "Match every track to one calibrated loudness",
                checked = prefsState.normalizeEnabled,
                onChecked = { on -> update { it.copy(normalizeEnabled = on) } }
            )

            if (prefsState.normalizeEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Target loudness",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextMain
                    )
                    Text(
                        "%.1f LUFS".format(prefsState.targetLufs),
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = prefsState.targetLufs,
                    onValueChange = { v ->
                        update { it.copy(targetLufs = (Math.round(v * 10.0) / 10.0).toFloat()) }
                    },
                    valueRange = DspPreferences.MIN_TARGET_LUFS..DspPreferences.MAX_TARGET_LUFS,
                    colors = SliderDefaults.colors(
                        thumbColor = StreamifyColors.Primary,
                        activeTrackColor = StreamifyColors.Primary,
                        inactiveTrackColor = StreamifyColors.TextSub.copy(alpha = 0.3f)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("−23 Quiet", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Text("−14 Balanced", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    Text("−11 Loud", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            HorizontalDivider(color = StreamifyColors.TextSub.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))

            // ── Gapless (Gap #39) ─────────────────────────────────────────
            SettingToggleRow(
                title = "Gapless Playback",
                subtitle = "Zero silence between consecutive tracks",
                checked = prefsState.gaplessEnabled,
                onChecked = { on -> update { it.copy(gaplessEnabled = on) } }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Crossfade (Gap #39) ───────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Crossfade", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                Text(
                    text = if (prefsState.crossfadeSeconds <= 0f) "Off" else "${prefsState.crossfadeSeconds.toInt()}s",
                    style = StreamifyType.BodyMedium,
                    color = StreamifyColors.TextSub
                )
            }
            Slider(
                value = prefsState.crossfadeSeconds,
                onValueChange = { v -> update { it.copy(crossfadeSeconds = v) } },
                valueRange = 0f..DspPreferences.MAX_CROSSFADE_SECONDS,
                colors = SliderDefaults.colors(
                    thumbColor = StreamifyColors.Primary,
                    activeTrackColor = StreamifyColors.Primary,
                    inactiveTrackColor = StreamifyColors.TextSub.copy(alpha = 0.3f)
                )
            )
            Text(
                "Smooth constant-energy blend between songs. Gapless takes over at 0s.",
                style = StreamifyType.Caption,
                color = StreamifyColors.TextSub
            )

            HorizontalDivider(color = StreamifyColors.TextSub.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))

            // ── Mono + balance (Gap #41) ──────────────────────────────────
            SettingToggleRow(
                title = "Mono Audio",
                subtitle = "Combine left and right channels (accessibility)",
                checked = prefsState.monoEnabled,
                onChecked = { on -> update { it.copy(monoEnabled = on) } }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Stereo Balance", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                Text(
                    text = when {
                        prefsState.stereoBalancePercent == 0 -> "Centered"
                        prefsState.stereoBalancePercent < 0 -> "L ${-prefsState.stereoBalancePercent}%"
                        else -> "R ${prefsState.stereoBalancePercent}%"
                    },
                    style = StreamifyType.BodyMedium,
                    color = StreamifyColors.TextSub
                )
            }
            Slider(
                value = prefsState.stereoBalancePercent.toFloat(),
                onValueChange = { v ->
                    update { it.copy(stereoBalancePercent = Math.round(v)) }
                },
                valueRange = -100f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = StreamifyColors.Primary,
                    activeTrackColor = StreamifyColors.Primary,
                    inactiveTrackColor = StreamifyColors.TextSub.copy(alpha = 0.3f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("L 100%", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                Text("R 100%", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
            }

            HorizontalDivider(color = StreamifyColors.TextSub.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 10.dp))

            // ── True-peak limiter (Gap #41) ───────────────────────────────
            SettingToggleRow(
                title = "True-Peak Limiter",
                subtitle = "−1.0 dBFS ceiling (EU volume-law compliance guard)",
                checked = prefsState.truePeakLimiterEnabled,
                onChecked = { on -> update { it.copy(truePeakLimiterEnabled = on) } }
            )
        }
    }
}

/** One toggle row with title + supporting copy. */
@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
            Text(subtitle, style = StreamifyType.Caption, color = StreamifyColors.TextSub)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = StreamifyColors.Primary,
                checkedThumbColor = StreamifyColors.TextOnActiveChip
            )
        )
    }
}
