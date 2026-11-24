package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun filePickerResult(onResultUri: (String?) -> Unit): FilePickerLauncher =
    remember {
        object : FilePickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }

@Composable
actual fun fileSaverResult(
    fileName: String,
    mimeType: String,
    onResultUri: (String?) -> Unit,
): FilePickerLauncher =
    remember {
        object : FilePickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }
