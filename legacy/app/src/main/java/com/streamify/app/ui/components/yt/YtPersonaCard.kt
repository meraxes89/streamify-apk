package com.streamify.app.ui.components.yt
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtPersonaCard(
    personaName: String,
    personaEmoji: String,
    description: String,
    bpm: Int,
    modifier: Modifier = Modifier
) {
    // Pulse speed inversely proportional to BPM (Higher BPM = faster pulse).
    // Bounded animation: a handful of BPM-synced pulses then the ring rests
    // at its glow value — no infinite frame loop for the lifetime of the
    // Wrapped screen.
    val durationMs = (60000 / bpm.coerceAtLeast(60)).coerceIn(350, 1200)
    val pulseAlpha = remember { Animatable(0.2f) }
    LaunchedEffect(durationMs) {
        pulseAlpha.animateTo(
            targetValue = 0.75f,
            animationSpec = repeatable(
                iterations = 7,
                animation = tween(durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BgSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // BPM-Reactive Canvas Energy Ring
            Canvas(modifier = Modifier.size(56.dp)) {
                drawCircle(
                    color = Primary.copy(alpha = pulseAlpha.value),
                    radius = size.minDimension / 2f
                )
                drawCircle(
                    color = ActiveControl,
                    radius = size.minDimension / 3.5f
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI AUDIO PERSONA • ${bpm} BPM",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$personaName $personaEmoji",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                    color = TextSecondary
                )
            }
        }
    }
}
