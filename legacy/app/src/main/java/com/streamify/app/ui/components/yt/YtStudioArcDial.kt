package com.streamify.app.ui.components.yt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun YtStudioArcDial(
    label: String,
    value: Float, // 0.0f to 1.0f
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(110.dp)
                .pointerInput(enabled) {
                    if (enabled) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            // Vertical drag adjusts dial value smoothly
                            val delta = -dragAmount.y / 250f
                            val newValue = (value + delta).coerceIn(0f, 1f)
                            onValueChange(newValue)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val diameter = size.minDimension - stroke * 2
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)

                // Background Arc (240 degree sweep starting at 150 degrees)
                drawArc(
                    color = Divider,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )

                // Active Progress Arc
                if (value > 0.01f) {
                    drawArc(
                        color = if (enabled) ActiveControl else TextTertiary,
                        startAngle = 150f,
                        sweepAngle = 240f * value,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            // Percentage readout inside dial
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                color = if (enabled) TextMain else TextTertiary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            style = LocalAppTypography.current.songArtist.copy(
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            ),
            color = TextSecondary
        )
    }
}
