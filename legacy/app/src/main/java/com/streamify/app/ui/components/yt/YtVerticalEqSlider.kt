package com.streamify.app.ui.components.yt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun YtVerticalEqSlider(
    label: String,
    level: Short,
    minLevel: Short,
    maxLevel: Short,
    enabled: Boolean = true,
    onLevelChange: (Short) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalRange = (maxLevel - minLevel).toFloat().coerceAtLeast(1f)
    val fraction = ((level - minLevel) / totalRange).coerceIn(0f, 1f)
    val dbValue = (level / 100f).roundToInt()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        // Dynamic dB Readout
        Text(
            text = if (dbValue > 0) "+${dbValue}dB" else "${dbValue}dB",
            style = LocalAppTypography.current.seekbarTime.copy(fontSize = 11.sp),
            color = if (dbValue != 0 && enabled) ActiveControl else TextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Native Vertical Canvas Slider
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(130.dp)
                .pointerInput(enabled, minLevel, maxLevel) {
                    if (enabled) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // Dragging upwards increases level
                            val deltaFraction = -dragAmount.y / size.height.toFloat()
                            val newFraction = (fraction + deltaFraction).coerceIn(0f, 1f)
                            val newLevel = (minLevel + (newFraction * totalRange)).toInt().toShort()
                            onLevelChange(newLevel)
                        }
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackWidth = 4.dp.toPx()
                val xPos = (size.width - trackWidth) / 2f
                val thumbRadius = 7.dp.toPx()
                val currentY = size.height * (1f - fraction)

                // 1. Background Track
                drawRoundRect(
                    color = Divider,
                    topLeft = Offset(xPos, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
                )

                // 2. Center Zero-Line Indicator
                val centerY = size.height * 0.5f
                drawLine(
                    color = TextTertiary,
                    start = Offset(xPos - 4.dp.toPx(), centerY),
                    end = Offset(xPos + trackWidth + 4.dp.toPx(), centerY),
                    strokeWidth = 1.5.dp.toPx()
                )

                // 3. Active Track
                val activeTop = minOf(currentY, centerY)
                val activeHeight = kotlin.math.abs(currentY - centerY)
                drawRoundRect(
                    color = if (enabled) ActiveControl else TextTertiary,
                    topLeft = Offset(xPos, activeTop),
                    size = Size(trackWidth, activeHeight),
                    cornerRadius = CornerRadius(trackWidth / 2f, trackWidth / 2f)
                )

                // 4. Thumb
                drawCircle(
                    color = if (enabled) ActiveControl else TextTertiary,
                    radius = thumbRadius,
                    center = Offset(size.width / 2f, currentY.coerceIn(thumbRadius, size.height - thumbRadius))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Center Frequency Label
        Text(
            text = label,
            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
            color = TextSecondary
        )
    }
}
