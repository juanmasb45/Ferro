package com.juanma.ferro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.juanma.ferro.data.local.dao.FerroDao
import com.juanma.ferro.data.local.entities.*

@Database(
    entities = [
        WorkShiftEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
        StationVisitEntity::class,
        IncidentEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FerroDatabase : RoomDatabase() {
    abstract fun ferroDao(): FerroDao
}