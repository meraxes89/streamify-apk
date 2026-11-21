package com.streamify.app.ui.components.yt

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

/**
 * YtDaylistHeroBanner — Gap #20's morphing Daylist header on Home.
 *
 * A sleek time-reactive hero: the gradient breathes on an infinite drift,
 * the descriptive title ("Acoustic Morning Chill Tuesday") crossfades +
 * slides whenever the circadian bucket / 4-hour refresh slot advances,
 * and the next three tracks peek as chips. Tapping anywhere plays the
 * Daylist.
 *
 * Zero-jank notes: the infinite gradient transition is leaf-scoped to the
 * background Box; the banner itself only recomposes when the immutable
 * [DaylistHeroModel] actually changes.
 */
data class DaylistHeroModel(
    val title: String,
    val subtitle: String,
    val bucketLabel: String,
    val trackTitles: List<String> = emptyList(),
    val trackCount: Int = 0,
    val gradientStart: Long = 0xFF667EEA,
    val gradientEnd: Long = 0xFFF6D365,
    val isFromCache: Boolean = false
)

@Composable
fun YtDaylistHeroBanner(
    model: DaylistHeroModel,
    onPlayDaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val startColor = Color(model.gradientStart)
    val endColor = Color(model.gradientEnd)

    // Breathing gradient — leaf-scoped infinite animation.
    val transition = rememberInfiniteTransition(label = "daylistGradient")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "daylistGradientDrift"
    )
    val brush = remember(startColor, endColor, drift) {
        Brush.linearGradient(
            colors = listOf(startColor, endColor, startColor),
            start = androidx.compose.ui.geometry.Offset(x = 120f * drift, y = 0f),
            end = androidx.compose.ui.geometry.Offset(x = 120f * (1f + drift), y = 520f)
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
    ) {
        Box(
            modifier = Modifier
                .background(brush)
                .heightIn(min = 178.dp)
        ) {
            // Soft darkening scrim for text legibility.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.45f))
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "DAYLIST • ${model.bucketLabel.uppercase()} • REFRESHES EVERY 4 HOURS",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 10.sp,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Morphing descriptive title — crossfade + slide whenever
                // the bucket/slot (and therefore the title) advances.
                AnimatedContent(
                    targetState = model.title,
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = tween(420)
                        ) { it / 3 } + fadeIn(tween(420))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(280)
                            ) { -it / 3 } + fadeOut(tween(280)))
                    },
                    label = "daylistTitleMorph"
                ) { title ->
                    Text(
                        text = title,
                        style = LocalAppTypography.current.headlineLarge.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                AnimatedContent(
                    targetState = model.subtitle,
                    transitionSpec = {
                        fadeIn(tween(420)) togetherWith fadeOut(tween(280))
                    },
                    label = "daylistSubtitleMorph"
                ) { subtitle ->
                    Text(
                        text = subtitle,
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp),
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Next-up peek chips + play affordance.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    model.trackTitles.take(3).forEach { chip ->
                        Surface(
                            color = Color.Black.copy(alpha = 0.28f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = chip,
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                color = Color.White.copy(alpha = 0.92f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onPlayDaylist() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play Daylist",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                if (model.trackCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${model.trackCount} tracks tuned to your ${model.bucketLabel.lowercase()}",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
