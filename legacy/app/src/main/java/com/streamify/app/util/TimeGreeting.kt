package com.streamify.app.util

import java.util.Calendar

enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT
}

object TimeGreeting {
    fun getGreeting(): String {
        return when (getCurrentTimeOfDay()) {
            TimeOfDay.MORNING -> "Good morning"
            TimeOfDay.AFTERNOON -> "Good afternoon"
            TimeOfDay.EVENING -> "Good evening"
            TimeOfDay.NIGHT -> "Good night"
        }
    }

    fun getCurrentTimeOfDay(): TimeOfDay {
        val c = Calendar.getInstance()
        val hour = c.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> TimeOfDay.MORNING
            in 12..16 -> TimeOfDay.AFTERNOON
            in 17..21 -> TimeOfDay.EVENING
            else -> TimeOfDay.NIGHT
        }
    }
}

