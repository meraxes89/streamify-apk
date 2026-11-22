package com.streamify.announcements

/**
 * Built-in default announcements shipped with Streamify.
 */
object LocalAnnouncementDefaults {

    val INAUGURAL_BETA = Announcement(
        id = "streamify_inaugural_beta_announcement",
        schemaVersion = 1,
        priority = 100,
        presentation = PresentationType.MODAL,
        content = mapOf(
            "en" to LocalizedContent(
                title = "Welcome to Streamify Beta",
                body = "Experience high-fidelity music streaming powered by our Sovereign audio engine, SLYR real-time lyrics, and hybrid synchronization. If you enjoy the app, consider supporting the project with a star on GitHub!",
                bannerUrl = null,
                actionLabel = "Star on GitHub"
            )
        ),
        action = AnnouncementAction.DeepLink("https://github.com/meraxes89/streamify-apk"),
        targeting = Targeting(),
        frequency = FrequencyCap(
            maxImpressions = 1,
            cooldownMs = 7L * 24 * 60 * 60 * 1000L
        ),
        dismissPolicy = DismissPolicy.LATER_SNOOZE
    )

    val ALL = listOf(INAUGURAL_BETA)
}
