package com.streamify.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Maximize
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowDraggableArea
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-UI04 / Beta): the native-feeling custom window decoration.
 *
 * The window opens undecorated (see DesktopShellApp); this frame supplies
 * the title bar: a [WindowDraggableArea] spanning the full-width strip
 * (the whole bar drags the window — the button cluster at the end is
 * naturally excluded), the app glyph + title, and the classic
 * minimize / maximize-restore / close cluster.
 *
 * Window control mapping:
 *  * minimize → `ComposeWindow.isMinimized` (AWT iconification);
 *  * maximize / restore → [WindowPlacement] toggle on the window state;
 *  * close → the shell's close request (application exit — the tray's
 *    Show/Hide entry owns the hide-to-background semantics).
 */
@Composable
fun WindowScope.WindowFrame(
    windowState: WindowState,
    onMinimize: () -> Unit,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WindowFrameHeight)
            .background(StreamifyPalette.BgSurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Draggable chrome (title strip) ──────────────────────────────
        WindowDraggableArea(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxHeight().padding(horizontal = StreamifySpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App glyph: brand-red disc — the AppIcon geometry inlined
                // so the strip stays a pure vector draw.
                Box(
                    modifier = Modifier.size(14.dp)
                        .clip(CircleShape)
                        .background(StreamifyPalette.Primary),
                )
                Text(
                    text = "Streamify",
                    style = StreamifyTextStyles.title,
                    color = StreamifyPalette.TextMain,
                    modifier = Modifier.padding(start = StreamifySpacing.Sm),
                )
            }
        }

        // ── Window control cluster ─────────────────────────────────────
        FrameControlButton(
            icon = Icons.Filled.Minimize,
            contentDescription = "Minimize",
            onClick = onMinimize,
        )
        FrameControlButton(
            icon = Icons.Filled.Maximize,
            contentDescription = if (windowState.placement == WindowPlacement.Maximized) {
                "Restore"
            } else {
                "Maximize"
            },
            onClick = {
                windowState.placement =
                    if (windowState.placement == WindowPlacement.Maximized) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Maximized
                    }
            },
        )
        FrameControlButton(
            icon = Icons.Filled.Close,
            contentDescription = "Close",
            onClick = onCloseRequest,
            hoverTint = StreamifyPalette.Primary,
        )
    }
}

@Composable
private fun FrameControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    hoverTint: Color = StreamifyPalette.TextSecondary,
) {
    Box(
        modifier = Modifier.width(WindowFrameHeight).fillMaxHeight()
            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = hoverTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Title-bar height. */
val WindowFrameHeight = 40.dp
