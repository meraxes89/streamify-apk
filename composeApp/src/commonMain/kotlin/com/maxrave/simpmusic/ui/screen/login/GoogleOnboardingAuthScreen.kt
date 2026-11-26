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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
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
    val colorScheme = MaterialTheme.colorScheme

    // Theme-adaptive Material 3 tokens
    val bgColor = colorScheme.background
    val textPrimary = colorScheme.onBackground
    val textSecondary = colorScheme.onSurfaceVariant
    val buttonBg = colorScheme.surfaceContainerLowest
    val buttonBorder = colorScheme.outlineVariant
    val buttonTextColor = colorScheme.onSurface
    val emblemSurface = colorScheme.surfaceContainerLow
    val emblemOutline = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)
    val accentColor = colorScheme.primary

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
                if (err.contains("web sign-in", ignoreCase = true)) {
                    // Browser OAuth fallback is actively in flight: maintain loading state without error text
                    isLoading = true
                    errorMessage = null
                } else if (!err.contains("cancelled", ignoreCase = true)) {
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
        // Subtle Theme-Derived Ambient Halo
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2f, size.height * 0.36f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = (if (isDark) 0.10f else 0.06f) * (0.85f + 0.15f * pulseProg)),
                        Color.Transparent,
                    ),
                    center = centerOffset,
                    radius = size.width * 0.75f,
                ),
                radius = size.width * 0.75f,
                center = centerOffset,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Hero App Emblem with Neutral 10% Outline
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(emblemSurface)
                    .border(
                        width = 1.dp,
                        color = emblemOutline,
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

            Spacer(modifier = Modifier.height(24.dp))

            // Brand Wordmark
            Text(
                text = "Streamify",
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = appFontFamily,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Purpose Statement
            Text(
                text = "Sign in to sync your library and preferences",
                style = TextStyle(
                    color = textSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    fontFamily = appFontFamily,
                ),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    CircularProgressIndicator(
                        color = accentColor,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Signing in...",
                        fontSize = 13.sp,
                        color = textSecondary,
                        fontFamily = appFontFamily,
                    )
                }
            } else {
                // Google Sign-In Pill
                Button(
                    onClick = { startGoogleSignIn() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonBg,
                        contentColor = buttonTextColor,
                    ),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(
                        width = 0.75.dp,
                        color = buttonBorder,
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
                            color = buttonTextColor,
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
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Studio Signet
            SireenBrandingBadge(
                prefix = "ENGINEERED BY ",
                author = "SIREEN",
                tagline = "INDEPENDENT AUDIO LAB",
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
