package com.maxrave.simpmusic.ui.screen.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.expect.ui.rememberPlatformContext
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.fontFamily
import com.streamify.supabase.AuthState
import com.streamify.supabase.StreamifyAuthManager
import com.streamify.supabase.UserProfile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon

/**
 * GoogleOnboardingAuthScreen — SimpMusic-thematic Google OAuth & One-Tap Login screen.
 *
 * Requirements:
 * - Built according to SimpMusic thematic (blends smoothly in light and dark mode).
 * - Automatically triggers and provides user option to choose their Google account via Credential Manager One-Tap.
 * - Authenticates against Supabase and persists their identity forever until explicit logout.
 * - Gatekeeper: user must authenticate to proceed.
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
    var hasAttemptedAutoPrompt by remember { mutableStateOf(false) }

    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f

    // Theme adaptive colors
    val bgColor = if (isDark) Color(0xFF07070A) else Color(0xFFF8F9FD)
    val cardColor = if (isDark) Color(0xFF141418) else Color(0xFFFFFFFF)
    val textPrimary = if (isDark) Color.White else Color(0xFF16161A)
    val textSecondary = if (isDark) Color(0xFF9E9EA8) else Color(0xFF6B6B76)
    val primaryColor = MaterialTheme.colorScheme.primary
    val buttonBg = if (isDark) Color(0xFF1E1E26) else Color(0xFFECEEF6)

    // Ambient glow pulse
    val transition = rememberInfiniteTransition(label = "auth_glow")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radial_pulse_scale",
    )

    // Function to trigger Google Sign-In
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

    // Auto-prompt Google One-Tap on first composition
    LaunchedEffect(platformContext) {
        if (!hasAttemptedAutoPrompt && platformContext != null) {
            hasAttemptedAutoPrompt = true
            startGoogleSignIn()
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
        // Ambient Radial Background Glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = (if (isDark) 0.18f else 0.10f) * pulseScale),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.38f),
                    radius = size.width * 1.1f,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // App Logo
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(cardColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(Res.drawable.circle_app_icon),
                    contentDescription = "Streamify Logo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Welcome to Streamify",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = fontFamily(),
                color = textPrimary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Log in with your Google account to sync your playlists, favorites, and listening stats across all your devices.",
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily(),
                color = textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
            )

            Spacer(modifier = Modifier.height(40.dp))

            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = primaryColor,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Authenticating with Google...",
                        fontSize = 13.sp,
                        color = textSecondary,
                        fontFamily = fontFamily(),
                    )
                }
            } else {
                // One-Tap / Continue with Google Button
                Button(
                    onClick = {
                        startGoogleSignIn()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonBg,
                        contentColor = textPrimary,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "G",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = primaryColor,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily(),
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
                        fontFamily = fontFamily(),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "🔒 Secure Cloud Sync & Offline Listening",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = fontFamily(),
                    color = textSecondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
