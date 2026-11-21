package com.streamify.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.supabase.AuthManager
import com.streamify.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun YtOnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    // 120fps Pulsating Ambient Glow
    val transition = rememberInfiniteTransition(label = "onboarding_glow")
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radial_pulse_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // GPU Radial Canvas Ambient Layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Primary.copy(alpha = 0.22f * pulseScale),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height * 0.35f),
                    radius = size.width * 1.2f
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand Logo Circle Badge
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Streamify Play",
                    tint = TextMain,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Welcome to Streamify",
                style = LocalAppTypography.current.headlineLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextMain,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Your offline acoustic AI music sanctuary with 120 FPS high-fidelity sound.",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 14.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    color = Primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                // Continue with Google Button
                Button(
                    onClick = {
                        isLoading = true
                        scope.launch {
                            val result = AuthManager.signInWithGoogle(context)
                            isLoading = false
                            if (result.isSuccess) {
                                Toast.makeText(context, "Welcome back, ${result.getOrNull()?.displayName}!", Toast.LENGTH_SHORT).show()
                                onComplete()
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Sign-in failed"
                                if (!err.contains("cancelled", ignoreCase = true)) {
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Sign In with Google",
                        style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                        color = TextOnActiveChip,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "🔒 Secure Multi-Device Taste Sync & Cloud Storage",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
