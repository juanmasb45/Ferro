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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs

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
        if (id != null) ferroDao.getPointsForRoute(id) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allRoutes = ferroDao.getAllRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allWorkShifts = ferroDao.getAllWorkShifts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentWorkShift = ferroDao.getActiveWorkShift()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allStations = ferroDao.getAllStations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                // We only update the manual PK for display and next stop logic.
                // We REMOVE the incrementShiftDistance call from here to avoid double counting,
                // as LocationService already handles DB updates.
                if (_isAscending.value) _manualPK.value += distance else _manualPK.value -= distance
            }
            lastLocation = location
        }.launchIn(viewModelScope)
    }

    private fun observeProximityAndSchedule() {
        combine(currentPK, currentRoutePoints, isAscending) { pk, points, ascending ->
            val stations = points.filter { it.type == PointType.STATION }
            val limitations = points.filter { it.type == PointType.LIMITATION }

            val nextSt = if (ascending) stations.filter { it.kilometerPoint > pk }.minByOrNull { it.kilometerPoint }
            else stations.filter { it.kilometerPoint < pk }.maxByOrNull { it.kilometerPoint }
            _nextStation.value = nextSt

            val nextLimit = if (ascending) limitations.filter { it.kilometerPoint > pk }.minByOrNull { it.kilometerPoint }
            else limitations.filter { it.kilometerPoint < pk }.maxByOrNull { it.kilometerPoint }
            _nextLimitation.value = nextLimit

            val active = limitations.find { pk >= minOf(it.kilometerPoint, it.endKm ?: it.kilometerPoint) && pk <= maxOf(it.kilometerPoint, it.endKm ?: it.kilometerPoint) }
            _activeLimitation.value = active

            // Alerts for limitations (2.0km as requested)
            nextLimit?.let {
                val distance = abs(it.kilometerPoint - pk)
                if (distance <= 2.05 && distance >= 1.95 && !announcedLimitIds.contains(it.id)) {
                    announcedLimitIds.add(it.id)
                    _proximityAlert.value = "Atención: Limitación ${it.name} a dos kilómetros. Velocidad máxima ${it.speedLimit} kilómetros por hora"
                    viewModelScope.launch {
                        delay(5000)
                        _proximityAlert.value = null
                    }
                }
            }

            stations.forEach { station ->
                if (abs(station.kilometerPoint - pk) < 0.1 && !visitedPointIds.contains(station.id)) {
                    recordStationVisit(station)
                }
            }

            val distanceToStation = nextSt?.let { abs(it.kilometerPoint - pk) } ?: Double.MAX_VALUE
            if (distanceToStation <= 1.0) {
                updateScheduleStatus(nextSt)
            } else {
                _scheduleStatus.value = null
            }
        }.launchIn(viewModelScope)
    }

    private fun recordStationVisit(point: RoutePointEntity) {
        viewModelScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().first()
            activeShift?.id?.let { shiftId ->
                visitedPointIds.add(point.id)
                val now = LocalTime.now(ZoneId.of("America/Mexico_City"))
                var delayMins = 0L
                try {
                    val scheduled = LocalTime.parse(point.arrivalTime)
                    delayMins = ChronoUnit.MINUTES.between(scheduled, now)
                } catch (e: Exception) {}

                ferroDao.insertStationVisit(StationVisitEntity(
                    shiftId = shiftId,
                    routePointId = point.id,
                    actualTime = System.currentTimeMillis(),
                    delayMinutes = delayMins
                ))
            }
        }
    }

    private fun updateScheduleStatus(nextStation: RoutePointEntity?) {
        val scheduledTimeStr = nextStation?.arrivalTime ?: return
        try {
            val now = LocalTime.now(ZoneId.of("America/Mexico_City"))
            val scheduled = LocalTime.parse(scheduledTimeStr)
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
            val activeShift = ferroDao.getActiveWorkShift().first()
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
            val activeShift = ferroDao.getActiveWorkShift().first()
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

    fun insertStation(station: StationEntity) {
        viewModelScope.launch {
            ferroDao.insertStation(station)
        }
    }

    fun deleteStation(station: StationEntity) {
        viewModelScope.launch {
            ferroDao.deleteStation(station)
        }
    }

    suspend fun getRouteJson(routeId: Long): String? {
        val route = ferroDao.getRouteById(routeId) ?: return null
        val points = ferroDao.getPointsForRoute(routeId).first()
        val container = RouteExportContainer(route, points)
        return Json.encodeToString(container)
    }

    fun importRouteFromJson(json: String) {
        viewModelScope.launch {
            try {
                val container = Json.decodeFromString<RouteExportContainer>(json)
                val newRouteId = ferroDao.insertRoute(container.route.copy(id = 0))
                container.points.forEach { point ->
                    ferroDao.insertRoutePoint(point.copy(id = 0, routeId = newRouteId))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun generateCsvData(): String {
        return "Not implemented yet"
    }
}