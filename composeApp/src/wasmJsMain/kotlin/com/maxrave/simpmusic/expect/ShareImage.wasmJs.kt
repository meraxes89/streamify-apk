package com.maxrave.simpmusic.expect

actual suspend fun saveImageToDevice(
    bytes: ByteArray,
    fileName: String,
): Boolean = false

actual suspend fun shareImage(
    bytes: ByteArray,
    fileName: String,
    chooserTitle: String,
    mimeType: String,
): Boolean = false
