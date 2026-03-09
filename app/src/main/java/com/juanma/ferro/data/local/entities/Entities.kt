package com.juanma.ferro.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trainNumber: String = "",
    val maxSpeed: Int = 80,
    val trainLength: Int = 0
)

@Serializable
@Entity(tableName = "route_points")
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val order: Int,
    val type: PointType,
    val name: String,
    val kilometerPoint: Double,
    val arrivalTime: String? = null,
    val departureTime: String? = null,
    val speedLimit: Int? = null,
    val endKm: Double? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

@Serializable
enum class PointType {
    STATION, LIMITATION
}

@Entity(tableName = "work_shifts")
data class WorkShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val totalKilometers: Double = 0.0,
    val trainNumber: String = "",
    val routeId: Long? = null,
    val notes: String = ""
)

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long,
    val timestamp: Long,
    val type: String,
    val pk: Double,
    val description: String = ""
)

@Entity(tableName = "station_visits")
data class StationVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long,
    val routePointId: Long,
    val actualTime: Long,
    val delayMinutes: Long
)

@Serializable
data class RouteExportContainer(
    val route: RouteEntity,
    val points: List<RoutePointEntity>
)
