package com.streamify.app.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Cast framework options (Gap #53).
 *
 * Binds the app to the DEFAULT MEDIA RECEIVER application id — the
 * receiver-side app Chromecast/Smart-TV devices run without any custom
 * receiver deployment. A future branded receiver swaps exactly this one
 * constant (plus an app id registration in the Cast console).
 */
class StreamifyCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
