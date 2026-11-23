package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun photoPickerResult(onResultUri: (String?) -> Unit): PhotoPickerLauncher =
    remember {
        object : PhotoPickerLauncher {
            override fun launch() {
                onResultUri(null)
            }
        }
    }
