package com.juanma.ferro.data.local

import androidx.room.TypeConverter
import com.juanma.ferro.data.local.entities.PointType

class Converters {
    @TypeConverter
    fun fromPointType(value: PointType): String {
        return value.name
    }

    @TypeConverter
    fun toPointType(value: String): PointType {
        return PointType.valueOf(value)
    }
}