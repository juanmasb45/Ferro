package com.juanma.ferro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.juanma.ferro.data.local.dao.FerroDao
import com.juanma.ferro.data.local.entities.*

@Database(
    entities = [
        WorkShiftEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
        StationVisitEntity::class,
        IncidentEntity::class,
        TrackLogEntity::class
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FerroDatabase : RoomDatabase() {
    abstract fun ferroDao(): FerroDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Añadir columna currentPK a work_shifts
                db.execSQL("ALTER TABLE work_shifts ADD COLUMN currentPK REAL NOT NULL DEFAULT 0.0")
                
                // 2. Crear la tabla track_logs
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        shiftId INTEGER NOT NULL, 
                        timestamp INTEGER NOT NULL, 
                        latitude REAL NOT NULL, 
                        longitude REAL NOT NULL, 
                        kilometerPoint REAL NOT NULL, 
                        speed REAL NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
