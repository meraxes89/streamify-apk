package com.maxrave.simpmusic.ui.screen.login

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.expect.ui.rememberPlatformContext
import com.maxrave.simpmusic.ui.component.SireenBrandingBadge
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.fontFamily
import com.streamify.supabase.StreamifyAuthManager
import com.streamify.supabase.UserProfile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon

/**
 * GoogleOnboardingAuthScreen — Minimalist Luxury Auth Gatekeeper
 *
 * Requirements:
 * - Built according to SimpMusic / Streamify "Prismatic Horizon" aesthetic.
 * - Minimalist luxury layout with zero text clutter.
 * - Hardware-accelerated acoustic ambient rings & aperture glow.
 * - Authentic 4-color vector Google "G" sign-in pill.
 * - Strict gatekeeper: authentication required to access the main player.
 */
@Composable
fun GoogleOnboardingAuthScreen(
    onAuthenticated: (UserProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val platformContext = rememberPlatformContext()
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val appFontFamily = fontFamily()

    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f

    // Theme adaptive colors
    val bgColor = if (isDark) Color(0xFF07070A) else Color(0xFFF8F9FD)
    val textPrimary = if (isDark) Color.White else Color(0xFF0F172A)
    val textSecondary = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val mottoColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
    val buttonBg = if (isDark) Color(0xFF16161E) else Color(0xFFFFFFFF)
    val laserCyan = if (isDark) Color(0xFF00F2FE) else Color(0xFF0284C7)
    val laserViolet = if (isDark) Color(0xFF8B5CF6) else Color(0xFF7C3AED)
    val laserMagenta = if (isDark) Color(0xFFFF2A85) else Color(0xFFDB2777)

    // Ambient glow pulse
    val transition = rememberInfiniteTransition(label = "auth_ambient")
    val pulseProg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient_pulse",
    )

    fun startGoogleSignIn() {
        isLoading = true
        errorMessage = null
        scope.launch {
            val result = StreamifyAuthManager.signInWithGoogle(platformContext)
            isLoading = false
            if (result.isSuccess) {
                val profile = result.getOrNull()
                if (profile != null) {
                    onAuthenticated(profile)
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Sign-in failed"
                if (!err.contains("cancelled", ignoreCase = true)) {
                    errorMessage = err
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        // Hardware-Accelerated Ambient Acoustic Mesh
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2f, size.height * 0.36f)

            // 1. Aperture Bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        laserViolet.copy(alpha = (if (isDark) 0.22f else 0.12f) * (0.8f + 0.3f * pulseProg)),
                        laserCyan.copy(alpha = (if (isDark) 0.12f else 0.06f) * (0.8f + 0.3f * pulseProg)),
                        Color.Transparent,
                    ),
                    center = centerOffset,
                    radius = size.width * 0.85f,
                ),
                radius = size.width * 0.85f,
                center = centerOffset,
            )

            // 2. Concentric Acoustic Soundwave Rings (Vinyl Grooves)
            val ringCount = 3
            for (i in 0 until ringCount) {
                val radius = 72.dp.toPx() + (32.dp.toPx() * (i + 1)) * (0.95f + 0.05f * pulseProg)
                val alpha = (if (isDark) 0.14f else 0.08f) * (1f - (i * 0.25f)) * (0.8f + 0.2f * pulseProg)
                drawCircle(
                    color = if (i % 2 == 0) laserCyan.copy(alpha = alpha) else laserViolet.copy(alpha = alpha),
                    radius = radius,
                    center = centerOffset,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Hero App Emblem with Iridescent Specular Rim
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                if (isDark) Color(0xFF1E1E28) else Color(0xFFFFFFFF),
                                if (isDark) Color(0xFF101016) else Color(0xFFF1F3F9),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                laserCyan.copy(alpha = 0.6f),
                                laserViolet.copy(alpha = 0.7f),
                                laserMagenta.copy(alpha = 0.5f),
                                laserCyan.copy(alpha = 0.6f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.circle_app_icon),
                    contentDescription = "Streamify Logo",
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Brand Wordmark: "STREAMIFY"
            Text(
                text = "STREAMIFY",
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 8.sp,
                    fontFamily = appFontFamily,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Acoustic Motto: "S O U N D   /   R E D E F I N E D"
            Text(
                text = "S O U N D   /   R E D E F I N E D",
                style = TextStyle(
                    color = mottoColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.0.sp,
                    fontFamily = appFontFamily,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Minimalist Purpose Statement
            Text(
                text = "Cloud Sync & High-Fidelity Streaming",
                style = TextStyle(
                    color = textSecondary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = appFontFamily,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(44.dp))

            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.height(52.dp),
                ) {
                    CircularProgressIndicator(
                        color = laserViolet,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Authenticating...",
                        fontSize = 12.5.sp,
                        color = textSecondary,
                        fontFamily = appFontFamily,
                    )
                }
            } else {
                // Tactile Frosted Google Sign-In Pill
                Button(
                    onClick = { startGoogleSignIn() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonBg,
                        contentColor = textPrimary,
                    ),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 2.dp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        GoogleLogoIcon(modifier = Modifier.size(19.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = appFontFamily,
                            color = textPrimary,
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = errorMessage ?: "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = appFontFamily,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = {
                        val result = StreamifyAuthManager.signInAsGuest(platformContext)
                        if (result.isSuccess) {
                            val profile = result.getOrNull()
                            if (profile != null) {
                                onAuthenticated(profile)
                            }
                        }
                    },
                ) {
                    Text(
                        text = "Continue as Guest",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = appFontFamily,
                        color = textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Studio Signet
            SireenBrandingBadge(
                prefix = "ENGINEERED BY ",
                author = "SIREEN",
                tagline = "INDEPENDENT AUDIO LAB",
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Monospace Engineering Specification
            Text(
                text = "STREAMIFY CLOUD · HIGH-FIDELITY AUDIO",
                style = TextStyle(
                    color = textSecondary.copy(alpha = 0.65f),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.0.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Authentic 4-color Google "G" vector emblem drawn with pure mathematical paths.
 */
@Composable
private fun GoogleLogoIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f

        // Blue (#4285F4)
        val bluePath = Path().apply {
            moveTo(23.745f * sx, 12.27f * sy)
            cubicTo(23.745f * sx, 11.57f * sy, 23.685f * sx, 10.87f * sy, 23.555f * sx, 10.2f * sy)
            lineTo(12f * sx, 10.2f * sy)
            lineTo(12f * sx, 14.71f * sy)
            lineTo(18.6f * sx, 14.71f * sy)
            cubicTo(18.31f * sx, 16.23f * sy, 17.46f * sx, 17.53f * sy, 16.2f * sx, 18.39f * sy)
            lineTo(16.2f * sx, 21.44f * sy)
            lineTo(20.08f * sx, 21.44f * sy)
            cubicTo(22.35f * sx, 19.35f * sy, 23.745f * sx, 16.27f * sy, 23.745f * sx, 12.27f * sy)
            close()
        }
        drawPath(bluePath, Color(0xFF4285F4))

        // Green (#34A853)
        val greenPath = Path().apply {
            moveTo(12f * sx, 24f * sy)
            cubicTo(15.24f * sx, 24f * sy, 17.95f * sx, 22.92f * sy, 19.93f * sx, 21.09f * sy)
            lineTo(16.05f * sx, 18.04f * sy)
            cubicTo(14.97f * sx, 18.76f * sy, 13.6f * sx, 19.2f * sy, 12f * sx, 19.2f * sy)
            cubicTo(8.88f * sx, 19.2f * sy, 6.23f * sx, 17.1f * sy, 5.28f * sx, 14.27f * sy)
            lineTo(1.25f * sx, 14.27f * sy)
            lineTo(1.25f * sx, 17.42f * sy)
            cubicTo(3.26f * sx, 21.36f * sy, 7.33f * sx, 24f * sy, 12f * sx, 24f * sy)
            close()
        }
        drawPath(greenPath, Color(0xFF34A853))

        // Yellow (#FBBC05)
        val yellowPath = Path().apply {
            moveTo(5.28f * sx, 14.27f * sy)
            cubicTo(5.03f * sx, 13.55f * sy, 4.9f * sx, 12.78f * sy, 4.9f * sx, 12f * sy)
            cubicTo(4.9f * sx, 11.22f * sy, 5.03f * sx, 10.45f * sy, 5.28f * sx, 9.73f * sy)
            lineTo(5.28f * sx, 6.58f * sy)
            lineTo(1.25f * sx, 6.58f * sy)
            cubicTo(0.45f * sx, 8.18f * sy, 0f * sx, 9.99f * sy, 0f * sx, 12f * sy)
            cubicTo(0f * sx, 14.01f * sy, 0.45f * sx, 15.82f * sy, 1.25f * sx, 17.42f * sy)
            lineTo(5.28f * sx, 14.27f * sy)
            close()
        }
        drawPath(yellowPath, Color(0xFFFBBC05))

        // Red (#EA4335)
        val redPath = Path().apply {
            moveTo(12f * sx, 4.75f * sy)
            cubicTo(13.77f * sx, 4.75f * sy, 15.35f * sx, 5.36f * sy, 16.6f * sx, 6.55f * sy)
            lineTo(20.02f * sx, 3.13f * sy)
            cubicTo(17.95f * sx, 1.19f * sy, 15.24f * sx, 0f * sy, 12f * sx, 0f * sy)
            cubicTo(7.33f * sx, 0f * sy, 3.26f * sx, 2.64f * sy, 1.25f * sx, 6.58f * sy)
            lineTo(5.28f * sx, 9.73f * sy)
            cubicTo(6.23f * sx, 6.9f * sy, 8.88f * sx, 4.75f * sy, 12f * sx, 4.75f * sy)
            close()
        }
        drawPath(redPath, Color(0xFFEA4335))
    }
}
