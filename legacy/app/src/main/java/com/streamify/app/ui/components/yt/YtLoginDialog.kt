package com.streamify.app.ui.components.yt
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamify.app.data.spotify.SpotifyAuthManager
import com.streamify.app.data.youtube.YtSessionExtractor

@Composable
fun YtLoginDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onAuthSuccess: (authHeader: String, rawCookies: String) -> Unit,
    onError: (String) -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val extractor = remember { YtSessionExtractor(context) }
    val authManager = remember { SpotifyAuthManager(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isSecuringSession by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            extractor.release()
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isSecuringSession) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = !isSecuringSession) {
            if (webViewInstance?.canGoBack() == true) {
                webViewInstance?.goBack()
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        webViewInstance = this
                        extractor.launchAuthSession(
                            webView = this,
                            onSuccess = { header, cookies ->
                                isSecuringSession = true
                                authManager.saveYtSession(header, cookies)
                                onAuthSuccess(header, cookies)
                                onDismiss()
                            },
                            onError = { error ->
                                onError(error)
                                onDismiss()
                            }
                        )
                    }
                },
                update = {
                    webViewInstance = it
                }
            )

            // Securing Session Transition Overlay
            AnimatedVisibility(
                visible = isSecuringSession,
                enter = fadeIn(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0C)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color(0xFFFF0000),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Securing YouTube Session...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Dismiss / Close Button
            if (!isSecuringSession) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Login",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
