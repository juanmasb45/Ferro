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
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FerroDatabase : RoomDatabase() {
    abstract fun ferroDao(): FerroDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_shifts ADD COLUMN currentPK REAL NOT NULL DEFAULT 0.0")
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

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Por si acaso la versión 10 se creó sin la columna currentPK o la tabla track_logs
                // debido al problema de fallbackToDestructiveMigration previo
                
                // Intentamos añadir la columna (si ya existe, fallará el SQL pero Room lo ignora en migraciones manuales si capturamos o podemos verificar)
                // En SQLite no hay "IF NOT EXISTS" para ADD COLUMN, así que verificamos manualmente
                val cursor = db.query("PRAGMA table_info(work_shifts)")
                var hasCurrentPK = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "currentPK") {
                        hasCurrentPK = true
                        break
                    }
                }
                cursor.close()
                
                if (!hasCurrentPK) {
                    db.execSQL("ALTER TABLE work_shifts ADD COLUMN currentPK REAL NOT NULL DEFAULT 0.0")
                }

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
