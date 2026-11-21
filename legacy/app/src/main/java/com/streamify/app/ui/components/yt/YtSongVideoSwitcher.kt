package com.streamify.app.ui.components.yt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtSongVideoSwitcher(
    isVideo: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .width(140.dp)
            .height(32.dp)
            .clip(shape)
            .background(BgSurfaceElevated)
            .border(1.dp, BorderChip, shape)
    ) {
        // Animated Slider Indicator
        val targetOffset by animateDpAsState(
            targetValue = if (isVideo) 70.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "switcherOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = targetOffset)
                .width(70.dp)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(shape)
                .background(ActiveControl)
        )

        // Labels Row
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (isVideo) {
                            com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            onToggle(false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Song",
                    color = if (!isVideo) TextOnActiveChip else TextMain,
                    style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (!isVideo) {
                            com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            onToggle(true)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Video",
                    color = if (isVideo) TextOnActiveChip else TextMain,
                    style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp)
                )
            }
        }
    }
}
