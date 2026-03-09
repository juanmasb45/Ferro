package com.juanma.ferro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juanma.ferro.data.local.dao.FerroDao
import com.juanma.ferro.data.local.entities.*
import com.juanma.ferro.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

data class PointProgress(
    val point: RoutePointEntity,
    val visit: StationVisitEntity?
)

@HiltViewModel
class MainViewModel @Inject constructor(
    val ferroDao: FerroDao
) : ViewModel() {

    val locationData = LocationService.locationData

    private val _mexicoCityTime = MutableStateFlow("")
    val mexicoCityTime = _mexicoCityTime.asStateFlow()

    val currentSpeed = locationData.map { location ->
        location?.let { (it.speed * 3.6).toInt() } ?: 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _manualPK = MutableStateFlow(0.0)
    val currentPK = _manualPK.asStateFlow()

    private val _isAscending = MutableStateFlow(true)
    val isAscending = _isAscending.asStateFlow()

    private val _selectedRouteId = MutableStateFlow<Long?>(null)
    val selectedRouteId = _selectedRouteId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentRoutePoints = _selectedRouteId.flatMapLatest { id ->
        if (id != null) ferroDao.getPointsForRoute(id).map { it.sortedBy { p -> p.order } } else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRoutes = ferroDao.getAllRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allWorkShifts = ferroDao.getAllWorkShifts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentWorkShift = ferroDao.getActiveWorkShift()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeShiftVisits = currentWorkShift.flatMapLatest { shift ->
        if (shift != null) ferroDao.getVisitsForShift(shift.id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentRouteProgress = combine(currentRoutePoints, activeShiftVisits) { points, visits ->
        points.map { point ->
            val visit = visits.find { it.routePointId == point.id }
            PointProgress(point, visit)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val visitedPointIds = mutableSetOf<Long>()
    private val announcedLimitIds = mutableSetOf<Long>()

    private val _proximityAlert = MutableStateFlow<String?>(null)
    val proximityAlert = _proximityAlert.asStateFlow()

    private val _scheduleStatus = MutableStateFlow<String?>(null)
    val scheduleStatus = _scheduleStatus.asStateFlow()

    private val _nextStation = MutableStateFlow<RoutePointEntity?>(null)
    val nextStation = _nextStation.asStateFlow()

    private val _nextLimitation = MutableStateFlow<RoutePointEntity?>(null)
    val nextLimitation = _nextLimitation.asStateFlow()

    private val _activeLimitation = MutableStateFlow<RoutePointEntity?>(null)
    val activeLimitation = _activeLimitation.asStateFlow()

    val currentLimit = combine(_selectedRouteId, allRoutes, _activeLimitation) { routeId, routes, activeLim ->
        val route = routes.find { it.id == routeId }
        activeLim?.speedLimit ?: route?.maxSpeed ?: 80
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 80)

    val isOverSpeed = combine(currentSpeed, currentLimit) { speed, limit ->
        speed > limit
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        updateClock()
        observeLocationUpdates()
        observeProximityAndSchedule()
    }

    private fun updateClock() {
        viewModelScope.launch {
            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
            val zoneId = ZoneId.of("America/Mexico_City")
            while (true) {
                _mexicoCityTime.value = ZonedDateTime.now(zoneId).format(formatter)
                delay(1000)
            }
        }
    }

    private var lastLocation: android.location.Location? = null
    private fun observeLocationUpdates() {
        locationData.onEach { location ->
            if (location != null && lastLocation != null) {
                val distance = location.distanceTo(lastLocation!!) / 1000.0
                if (_isAscending.value) _manualPK.value += distance else _manualPK.value -= distance
                updateTotalKilometers(distance)
            }
            lastLocation = location
        }.launchIn(viewModelScope)
    }

    private fun updateTotalKilometers(distance: Double) {
        viewModelScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().firstOrNull()
            activeShift?.id?.let { shiftId ->
                ferroDao.incrementShiftDistance(shiftId, distance)
            }
        }
    }

    private fun observeProximityAndSchedule() {
        combine(currentPK, currentRoutePoints, _isAscending) { pk, points, isAsc ->
            if (points.isEmpty()) return@combine

            points.forEach { point ->
                if (abs(point.kilometerPoint - pk) < 0.2 && !visitedPointIds.contains(point.id)) {
                    visitedPointIds.add(point.id)
                    if (point.type == PointType.STATION) {
                        recordStationVisit(point)
                    }
                }
            }

            val upcomingPoints = if (isAsc) {
                points.filter { it.kilometerPoint > pk + 0.1 }.sortedBy { it.kilometerPoint }
            } else {
                points.filter { it.kilometerPoint < pk - 0.1 }.sortedByDescending { it.kilometerPoint }
            }

            val nextSt = upcomingPoints.firstOrNull { it.type == PointType.STATION }
            _nextStation.value = nextSt

            val nextLimit = upcomingPoints.firstOrNull { it.type == PointType.LIMITATION }
            _nextLimitation.value = nextLimit

            val active = points.filter { it.type == PointType.LIMITATION }.find { 
                val start = minOf(it.kilometerPoint, it.endKm ?: it.kilometerPoint)
                val end = maxOf(it.kilometerPoint, it.endKm ?: it.kilometerPoint)
                pk >= (start - 0.02) && pk <= (end + 0.02) 
            }
            _activeLimitation.value = active

            nextLimit?.let {
                val distance = abs(it.kilometerPoint - pk)
                if (distance <= 2.05 && distance >= 1.85 && !announcedLimitIds.contains(it.id)) {
                    announcedLimitIds.add(it.id)
                    _proximityAlert.value = "Atención: Limitación ${it.name} a dos kilómetros. Velocidad máxima ${it.speedLimit} kilómetros por hora"
                    viewModelScope.launch {
                        delay(5000)
                        _proximityAlert.value = null
                    }
                }
            }

            if (nextSt != null) {
                updateScheduleStatus(nextSt)
            } else {
                _scheduleStatus.value = null
            }
        }.launchIn(viewModelScope)
    }

    private fun recordStationVisit(point: RoutePointEntity) {
        viewModelScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().firstOrNull()
            activeShift?.id?.let { shiftId ->
                val now = LocalTime.now(ZoneId.of("America/Mexico_City"))
                var delayMins = 0L
                
                if (!point.arrivalTime.isNullOrBlank() || !point.departureTime.isNullOrBlank()) {
                    try {
                        val scheduled = parseLocalTime(point.arrivalTime ?: point.departureTime)
                        if (scheduled != null) {
                            delayMins = ChronoUnit.MINUTES.between(scheduled, now)
                        }
                    } catch (e: Exception) {}
                }

                ferroDao.insertStationVisit(StationVisitEntity(
                    shiftId = shiftId,
                    routePointId = point.id,
                    actualTime = System.currentTimeMillis(),
                    delayMinutes = delayMins
                ))
            }
        }
    }

    private fun parseLocalTime(timeStr: String?): LocalTime? {
        if (timeStr.isNullOrBlank()) return null
        return try {
            val parts = timeStr.split(":")
            if (parts.size >= 2) {
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            } else null
        } catch (e: Exception) { null }
    }

    private fun updateScheduleStatus(nextStation: RoutePointEntity?) {
        val scheduled = parseLocalTime(nextStation?.arrivalTime ?: nextStation?.departureTime)
        if (scheduled == null) {
            _scheduleStatus.value = "PASO: ${nextStation?.name ?: ""}"
            return
        }

        try {
            val now = LocalTime.now(ZoneId.of("America/Mexico_City"))
            val diffMinutes = ChronoUnit.MINUTES.between(scheduled, now)
            _scheduleStatus.value = when {
                diffMinutes > 2 -> "RETRASO: $diffMinutes min"
                diffMinutes < -2 -> "ADELANTO: ${abs(diffMinutes)} min"
                else -> "EN HORA"
            }
        } catch (e: Exception) { _scheduleStatus.value = null }
    }

    fun selectRoute(routeId: Long) {
        visitedPointIds.clear()
        announcedLimitIds.clear()
        _selectedRouteId.value = routeId
        viewModelScope.launch {
            val route = ferroDao.getRouteById(routeId)
            val newShift = WorkShiftEntity(
                startTime = System.currentTimeMillis(),
                trainNumber = route?.trainNumber ?: "",
                routeId = routeId
            )
            ferroDao.insertWorkShift(newShift)
        }
    }

    fun finishWorkShift(notes: String) {
        viewModelScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().firstOrNull()
            activeShift?.let { shift ->
                ferroDao.insertWorkShift(shift.copy(
                    endTime = System.currentTimeMillis(),
                    notes = notes
                ))
                _selectedRouteId.value = null
                visitedPointIds.clear()
                announcedLimitIds.clear()
            }
        }
    }

    fun setPK(pk: Double) { _manualPK.value = pk }
    fun setDirection(ascending: Boolean) { _isAscending.value = ascending }

    fun addIncident(type: String) {
        viewModelScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().firstOrNull()
            activeShift?.id?.let { shiftId ->
                val incident = IncidentEntity(
                    shiftId = shiftId,
                    timestamp = System.currentTimeMillis(),
                    type = type,
                    pk = _manualPK.value
                )
                ferroDao.insertIncident(incident)
            }
        }
    }

    fun deleteRoute(route: RouteEntity) {
        viewModelScope.launch { 
            ferroDao.deleteRoute(route)
            if (_selectedRouteId.value == route.id) {
                _selectedRouteId.value = null
            }
        }
    }

    fun deleteWorkShift(shift: WorkShiftEntity) {
        viewModelScope.launch {
            ferroDao.deleteWorkShift(shift)
        }
    }

    fun updateRoute(route: RouteEntity) {
        viewModelScope.launch {
            ferroDao.updateRoute(route)
        }
    }

    fun cloneRoute(route: RouteEntity) {
        viewModelScope.launch {
            val newRouteId = ferroDao.insertRoute(route.copy(id = 0, name = "${route.name} (Copia)"))
            val points = ferroDao.getPointsForRoute(route.id).first()
            points.forEach { ferroDao.insertRoutePoint(it.copy(id = 0, routeId = newRouteId)) }
        }
    }

    fun invertRoute(route: RouteEntity) {
        viewModelScope.launch {
            val points = ferroDao.getPointsForRoute(route.id).first()
            val newRouteId = ferroDao.insertRoute(route.copy(id = 0, name = "${route.name} (Invertida)"))
            points.reversed().forEachIndexed { index, point ->
                ferroDao.insertRoutePoint(point.copy(id = 0, routeId = newRouteId, order = index))
            }
        }
    }

    fun reorderPoints(routeId: Long, newOrderedPoints: List<RoutePointEntity>) {
        viewModelScope.launch {
            newOrderedPoints.forEachIndexed { index, point ->
                ferroDao.insertRoutePoint(point.copy(order = index))
            }
        }
    }

    suspend fun getShiftSummaryText(shift: WorkShiftEntity): String {
        val route = shift.routeId?.let { ferroDao.getRouteById(it) }
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        val zone = ZoneId.of("America/Mexico_City")
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(shift.startTime), zone).format(formatter)
        val end = shift.endTime?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zone).format(formatter) } ?: "En curso"

        val duration = if (shift.endTime != null) {
            val mins = (shift.endTime - shift.startTime) / 60000
            "${mins / 60}h ${mins % 60}m"
        } else "---"

        val visits = ferroDao.getVisitsForShift(shift.id).first()
        val allPoints = route?.id?.let { ferroDao.getPointsForRoute(it).first() } ?: emptyList()
        val visitFormatter = DateTimeFormatter.ofPattern("HH:mm")

        val visitsText = if (visits.isNotEmpty()) {
            val details = visits.sortedBy { it.actualTime }.joinToString("\n            ") { visit ->
                val point = allPoints.find { it.id == visit.routePointId }
                val theoretical = point?.arrivalTime ?: point?.departureTime ?: "--:--"
                val actual = ZonedDateTime.ofInstant(Instant.ofEpochMilli(visit.actualTime), zone).format(visitFormatter)
                val typeLabel = if (point?.arrivalTime == null && point?.departureTime == null) "PASO" else "PARADA"
                val delayLabel = if (visit.delayMinutes != 0L) " (${if(visit.delayMinutes > 0) "+" else ""}${visit.delayMinutes} min)" else ""
                "- $theoretical -> REAL: $actual | ${point?.name ?: "---"} ($typeLabel)$delayLabel"
            }
            "\n\n            Itinerario Real vs Teórico:\n            $details"
        } else ""

        val incidents = ferroDao.getIncidentsForShift(shift.id).first()
        val incidentsText = if (incidents.isNotEmpty()) {
            val incidentFormatter = DateTimeFormatter.ofPattern("HH:mm")
            val incidentDetails = incidents.joinToString("\n            ") { incident ->
                val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(incident.timestamp), zone).format(incidentFormatter)
                "- ${incident.type} a las $time (PK ${String.format(Locale.getDefault(), "%.3f", incident.pk)})"
            }
            "\n\n            Incidencias Registradas:\n            $incidentDetails"
        } else {
            ""
        }

        return """
            🚂 RESUMEN DE JORNADA FERRO
            ---------------------------
            Ruta: ${route?.name ?: "N/A"}
            Tren: ${shift.trainNumber}
            Fecha Inicio: $start
            Fecha Fin: $end
            Duración: $duration
            Distancia: ${String.format(Locale.getDefault(), "%.2f", shift.totalKilometers)} km
            $visitsText
            $incidentsText
            
            Notas: ${shift.notes.ifBlank { "Sin observaciones" }}
            
            Enviado desde Ferro App
        """.trimIndent()
    }

    suspend fun getAllRoutesExportJson(): String {
        val routes = ferroDao.getAllRoutes().first()
        val exportList = routes.map { route ->
            val points = ferroDao.getPointsForRoute(route.id).first()
            RouteExportContainer(route, points)
        }
        return Json.encodeToString(exportList)
    }

    fun importRoutesFromJson(json: String) {
        viewModelScope.launch {
            try {
                val list = Json.decodeFromString<List<RouteExportContainer>>(json)
                list.forEach { container ->
                    val newRouteId = ferroDao.insertRoute(container.route.copy(id = 0))
                    container.points.forEach { point ->
                        ferroDao.insertRoutePoint(point.copy(id = 0, routeId = newRouteId))
                    }
                }
            } catch (e: Exception) {
                try {
                    val container = Json.decodeFromString<RouteExportContainer>(json)
                    val newRouteId = ferroDao.insertRoute(container.route.copy(id = 0))
                    container.points.forEach { point ->
                        ferroDao.insertRoutePoint(point.copy(id = 0, routeId = newRouteId))
                    }
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }
    }

    fun generateCsvData(): String {
        return "Not implemented yet"
    }
}