package com.juanma.ferro.data.local.dao

import androidx.room.*
import com.juanma.ferro.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FerroDao {
    @Query("SELECT * FROM routes")
    fun getAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRouteById(id: Long): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteEntity): Long

    @Update
    suspend fun updateRoute(route: RouteEntity)

    @Delete
    suspend fun deleteRoute(route: RouteEntity)

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY `order` ASC")
    fun getPointsForRoute(routeId: Long): Flow<List<RoutePointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutePoint(point: RoutePointEntity)

    @Delete
    suspend fun deleteRoutePoint(point: RoutePointEntity)

    @Query("DELETE FROM route_points WHERE routeId = :routeId")
    suspend fun deletePointsForRoute(routeId: Long)

    @Query("SELECT * FROM stations ORDER BY name ASC")
    fun getAllStations(): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: StationEntity): Long

    @Delete
    suspend fun deleteStation(station: StationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkShift(workShift: WorkShiftEntity): Long

    @Query("UPDATE work_shifts SET totalKilometers = totalKilometers + :distance WHERE id = :shiftId")
    suspend fun incrementShiftDistance(shiftId: Long, distance: Double)

    @Query("SELECT * FROM work_shifts WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    fun getActiveWorkShift(): Flow<WorkShiftEntity?>

    @Query("SELECT * FROM work_shifts ORDER BY startTime DESC")
    fun getAllWorkShifts(): Flow<List<WorkShiftEntity>>

    @Delete
    suspend fun deleteWorkShift(workShift: WorkShiftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity)

    @Query("SELECT * FROM incidents WHERE shiftId = :shiftId ORDER BY timestamp DESC")
    fun getIncidentsForShift(shiftId: Long): Flow<List<IncidentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStationVisit(visit: StationVisitEntity)

    @Query("SELECT * FROM station_visits WHERE shiftId = :shiftId")
    fun getVisitsForShift(shiftId: Long): Flow<List<StationVisitEntity>>
}
