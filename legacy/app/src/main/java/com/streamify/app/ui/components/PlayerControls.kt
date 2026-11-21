package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import com.streamify.app.viewmodel.PlaybackButtonState

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    isShuffleActive: Boolean,
    isRepeatActive: Boolean,
    isBuffering: Boolean = false,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val buttonState = when {
        isBuffering -> PlaybackButtonState.BUFFERING
        isPlaying -> PlaybackButtonState.PLAYING
        else -> PlaybackButtonState.PAUSED
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .cardPressEffect {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onShuffleToggle()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                tint = if (isShuffleActive) StreamifyColors.Shuffle else StreamifyColors.TextSub,
                modifier = Modifier.size(StreamifyDimens.ShuffleButtonSize)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .cardPressEffect {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipPrevious()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = StreamifyColors.TextMain,
                modifier = Modifier.size(StreamifyDimens.SkipButtonSize)
            )
        }

        Box(
            modifier = Modifier
                .size(StreamifyDimens.PlayButtonSize)
                .clip(CircleShape)
                .background(StreamifyColors.TextMain)
                .cardPressEffect {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayPause()
                }
                .padding(StreamifyDimens.SpaceMD),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = buttonState,
                label = "play_pause_icon"
            ) { state ->
                when (state) {
                    PlaybackButtonState.BUFFERING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = StreamifyColors.BgBase,
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round
                        )
                    }
                    PlaybackButtonState.PLAYING -> {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "Pause",
                            tint = StreamifyColors.BgBase,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    PlaybackButtonState.PAUSED -> {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = StreamifyColors.BgBase,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .cardPressEffect {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipNext()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = StreamifyColors.TextMain,
                modifier = Modifier.size(StreamifyDimens.SkipButtonSize)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .cardPressEffect {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onRepeatToggle()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = "Repeat",
                tint = if (isRepeatActive) StreamifyColors.Shuffle else StreamifyColors.TextSub,
                modifier = Modifier.size(StreamifyDimens.ShuffleButtonSize)
            )
        }
    }
}
