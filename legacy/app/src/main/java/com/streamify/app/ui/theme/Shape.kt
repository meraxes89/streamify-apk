package com.streamify.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// =========================================================================
// StreamifyShapeTokens (YouTube Music Docked & Flat Geometry)
// =========================================================================
data class StreamifyShapeTokens(
    val thumbnailSmall: Shape   = RoundedCornerShape(4.dp),
    val thumbnailMedium: Shape  = RoundedCornerShape(8.dp),
    val thumbnailLarge: Shape   = RoundedCornerShape(8.dp),
    val filterChip: Shape       = RoundedCornerShape(8.dp),
    val dockedMiniPlayer: Shape = RoundedCornerShape(0.dp), // Docked flat against bottom nav
    val actionPill: Shape       = RoundedCornerShape(50.dp), // Circular action pills
    val bottomSheet: Shape      = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
)

val LocalAppShapes = staticCompositionLocalOf { StreamifyShapeTokens() }

// =========================================================================
// StreamifyShapes Object (Compatibility with Existing UI Components)
// =========================================================================
object StreamifyShapes {
    val CardShape       = RoundedCornerShape(8.dp)
    val MiniPlayerShape = RoundedCornerShape(0.dp) // YTM Docked Edge-to-Edge
    val ChipShape       = RoundedCornerShape(8.dp)
    val SearchBarShape  = RoundedCornerShape(24.dp)
    val CategoryShape   = RoundedCornerShape(8.dp)
    val BottomSheet     = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    val PlayButton      = CircleShape
    val ActionPill      = RoundedCornerShape(50.dp)
    val ThumbnailSmall  = RoundedCornerShape(4.dp)
    val ThumbnailMedium = RoundedCornerShape(8.dp)
    val ThumbnailLarge  = RoundedCornerShape(8.dp)
}

val AppShapes = Shapes(
    small  = StreamifyShapes.ChipShape,
    medium = StreamifyShapes.CardShape,
    large  = StreamifyShapes.BottomSheet
)
