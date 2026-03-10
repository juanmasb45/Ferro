package com.juanma.ferro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.juanma.ferro.data.local.dao.FerroDao
import com.juanma.ferro.data.local.entities.PointType
import com.juanma.ferro.data.local.entities.StationVisitEntity
import com.juanma.ferro.data.local.entities.TrackLogEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class LocationService : Service() {

    @Inject lateinit var ferroDao: FerroDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private var lastLocation: Location? = null
    private var lastLogLocation: Location? = null
    private val visitedPointIds = mutableSetOf<Long>()

    companion object {
        private val _locationData = MutableStateFlow<Location?>(null)
        val locationData = _locationData.asStateFlow()
        
        private const val CHANNEL_ID = "location_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    _locationData.value = location
                    handleLocationUpdate(location)
                }
            }
        }
        
        createNotificationChannel()
    }

    private fun handleLocationUpdate(location: Location) {
        serviceScope.launch {
            val activeShift = ferroDao.getActiveWorkShift().firstOrNull() ?: return@launch
            
            // 1. Calcular y actualizar progreso (distancia y PK)
            var distanceIncrement = 0.0
            if (lastLocation != null) {
                distanceIncrement = location.distanceTo(lastLocation!!) / 1000.0
                if (distanceIncrement > 0.001) {
                    // Actualizamos el total de kilómetros y el PK actual en la DB
                    ferroDao.updateShiftProgress(activeShift.id, distanceIncrement, distanceIncrement)
                }
            }
            lastLocation = location

            // 2. Grabar Log de Trayecto (cada 500 metros para no saturar, pero ser precisos)
            if (lastLogLocation == null || location.distanceTo(lastLogLocation!!) >= 500) {
                val updatedShift = ferroDao.getActiveWorkShift().firstOrNull()
                ferroDao.insertTrackLog(
                    TrackLogEntity(
                        shiftId = activeShift.id,
                        timestamp = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        kilometerPoint = updatedShift?.currentPK ?: activeShift.currentPK,
                        speed = location.speed * 3.6f // Convertir m/s a km/h
                    )
                )
                lastLogLocation = location
            }

            // 3. Detección automática de Estaciones/PDIs por proximidad GPS
            activeShift.routeId?.let { routeId ->
                val points = ferroDao.getPointsForRoute(routeId).firstOrNull() ?: emptyList()
                points.forEach { point ->
                    if (point.id !in visitedPointIds && point.latitude != 0.0) {
                        val pointLoc = Location("").apply {
                            latitude = point.latitude
                            longitude = point.longitude
                        }
                        
                        if (location.distanceTo(pointLoc) < 200) { // Radio de 200 metros
                            visitedPointIds.add(point.id)
                            // Si es estación, podrías registrar la visita automáticamente
                            if (point.type == PointType.STATION) {
                                ferroDao.insertStationVisit(
                                    StationVisitEntity(
                                        shiftId = activeShift.id,
                                        routePointId = point.id,
                                        actualTime = System.currentTimeMillis(),
                                        delayMinutes = 0 // Cálculo opcional comparando con it.arrivalTime
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        requestLocationUpdates()
        return START_STICKY
    }

    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        } catch (e: SecurityException) {}
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Seguimiento Ferro Activo")
            .setContentText("Registrando trayecto y kilómetros...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Servicio de Seguimiento", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
    }
}
