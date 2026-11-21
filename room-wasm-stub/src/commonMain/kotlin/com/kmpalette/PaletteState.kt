package com.kmpalette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.kmpalette.palette.graphics.Palette
import io.ktor.http.Url

class PaletteState {
    var palette: Palette? = null
    suspend fun generate(bitmap: ImageBitmap) {}
}

@Composable
fun rememberPaletteState(): PaletteState = remember { PaletteState() }

class DominantColorState(
    var color: Color = Color.Unspecified,
    var onColor: Color = Color.Unspecified,
) {
    suspend fun updateFrom(url: Url) {}
}

@Composable
fun rememberDominantColorState(
    defaultColor: Color = Color.Unspecified,
    defaultOnColor: Color = Color.Unspecified,
    loader: Any? = null,
): DominantColorState = remember { DominantColorState(defaultColor, defaultOnColor) }
