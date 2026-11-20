package com.streamify.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// =========================================================================
// Extreme Performance: Use FontFamily.Default to map directly to System Roboto.
// Eliminates Google Font provider network calls, cold-start latency, and layout reflow.
// =========================================================================
val StreamifyFontFamily = FontFamily.Default

data class StreamifyTypography(
    val headlineLarge: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    val headlineSmall: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    val titleLarge: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    val titleMedium: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    val titleSmall: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    val songTitle: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val songArtist: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    val chipText: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    val playerTitle: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    val playerArtist: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val seekbarTime: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    val lyricsActive: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        color = TextMain
    ),
    val lyricsInactive: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        color = TextTertiary
    ),
    val bodyLarge: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val bodySmall: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = StreamifyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp
    )
)

val LocalAppTypography = staticCompositionLocalOf { StreamifyTypography() }

// =========================================================================
// StreamifyType Object (Seamless Compatibility with Existing Screens)
// =========================================================================
object StreamifyType {
    val DisplayLarge   = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp)
    val DisplayMedium  = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp)
    val HeadlineLarge  = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp)
    val HeadlineMedium = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
    val HeadlineSmall  = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val TitleLarge     = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val TitleMedium    = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val TitleSmall     = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)

    val BodyLarge      = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp)
    val BodyLargeBold  = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp)
    val BodyMedium     = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    val BodyMediumBold = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    val BodySmall      = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
    val BodySmallBold  = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)
    val Caption        = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
    val CaptionBold    = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp)
    val LabelSmall     = Caption

    val PlayerTitle    = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = 0.sp)
    val PlayerArtist   = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
    val SeekbarTime    = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp)

    val CardTitle      = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val CardSubtitle   = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)

    val LyricsActive   = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp, color = TextMain)
    val LyricsInactive = TextStyle(fontFamily = StreamifyFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.sp, color = TextTertiary)
}

val Typography = Typography(
    displayLarge = StreamifyType.DisplayLarge,
    displayMedium = StreamifyType.DisplayMedium,
    headlineLarge = StreamifyType.HeadlineLarge,
    headlineMedium = StreamifyType.HeadlineMedium,
    titleLarge = StreamifyType.TitleLarge,
    titleMedium = StreamifyType.TitleMedium,
    titleSmall = StreamifyType.TitleSmall,
    bodyLarge = StreamifyType.BodyLarge,
    bodyMedium = StreamifyType.BodyMedium,
    bodySmall = StreamifyType.BodySmall,
    labelSmall = StreamifyType.Caption
)
