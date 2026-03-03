package com.juanma.ferro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.juanma.ferro.data.local.dao.FerroDao
import com.juanma.ferro.data.local.entities.PointType
import com.juanma.ferro.data.local.entities.StationVisitEntity
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
            val activeShift = ferroDao.getActiveWorkShift().first() ?: return@launch
            
            // 1. Calcular y guardar distancia
            if (lastLocation != null) {
                val distance = location.distanceTo(lastLocation!!) / 1000.0
                if (distance > 0.001) {
                    ferroDao.incrementShiftDistance(activeShift.id, distance)
                }
            }
            lastLocation = location

            // 2. Detectar estaciones (si el PK actual está cerca de una estación del itinerario)
            activeShift.routeId?.let { routeId ->
                val points = ferroDao.getPointsForRoute(routeId).first()
                val currentPK = 0.0 // Aquí deberías vincular con el PK del ViewModel si es necesario, 
                                   // pero por ahora el servicio registra por proximidad GPS si tuvieras coordenadas.
                                   // Como usas PK manual, el servicio confía en el incremento.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
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