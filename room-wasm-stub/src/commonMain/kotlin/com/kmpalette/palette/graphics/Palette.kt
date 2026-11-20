package com.kmpalette.palette.graphics

class Palette(
    val swatches: List<Swatch> = emptyList(),
) {
    class Swatch(val rgb: Int, val population: Int)

    fun getDarkVibrantColor(defaultColor: Int): Int = defaultColor
    fun getDarkMutedColor(defaultColor: Int): Int = defaultColor
    fun getVibrantColor(defaultColor: Int): Int = defaultColor
    fun getMutedColor(defaultColor: Int): Int = defaultColor
    fun getLightVibrantColor(defaultColor: Int): Int = defaultColor
    fun getLightMutedColor(defaultColor: Int): Int = defaultColor
    fun getDominantColor(defaultColor: Int): Int = defaultColor
}
