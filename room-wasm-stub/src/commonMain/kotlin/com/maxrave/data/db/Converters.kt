package com.maxrave.data.db

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

@ProvidedTypeConverter
class Converters {
    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? = date?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()
}
