package com.juanma.ferro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.juanma.ferro.data.local.entities.*
import com.juanma.ferro.service.LocationService
import com.juanma.ferro.ui.MainViewModel
import com.juanma.ferro.ui.theme.FerroTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Routes : Screen("routes", "Rutas", Icons.AutoMirrored.Filled.List)
    object Stations : Screen("stations", "Estaciones", Icons.Default.Place)
    object Settings : Screen("settings", "Cuaderno", Icons.Default.Book)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        enableEdgeToEdge()
        setContent {
            FerroTheme {
                PermissionRequestWrapper {
                    FerroAppNavigation(onSpeak = { speak(it) })
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "MX")
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FerroAppNavigation(viewModel: MainViewModel = hiltViewModel(), onSpeak: (String) -> Unit) {
    val navController = rememberNavController()
    val alert by viewModel.proximityAlert.collectAsState()

    LaunchedEffect(alert) {
        alert?.let { onSpeak(it) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                val items = listOf(Screen.Dashboard, Screen.Routes, Screen.Stations, Screen.Settings)
                
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = Color.White,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = Color.White
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = Screen.Dashboard.route, modifier = Modifier.padding(innerPadding)) {
            composable(Screen.Dashboard.route) {
                FerroDashboard(
                    viewModel = viewModel,
                    onNavigateToRouteDetail = { navController.navigate("route_detail") }
                )
            }
            composable(Screen.Routes.route) {
                RoutesListScreen(
                    viewModel = viewModel,
                    onNavigateToEditRoute = { routeId -> navController.navigate("edit_route/$routeId") }
                )
            }
            composable(Screen.Stations.route) {
                StationsManagerScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(
                "edit_route/{routeId}",
                arguments = listOf(navArgument("routeId") { type = NavType.LongType })
            ) { backStackEntry ->
                val routeId = backStackEntry.arguments?.getLong("routeId") ?: 0L
                EditRouteScreen(
                    viewModel = viewModel,
                    routeId = routeId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("route_detail") {
                RouteSheetScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
fun PermissionRequestWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allPermissionsGranted()) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        granted = results.all { it.value }
        if (granted) {
            val intent = Intent(context, LocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(permissions.toTypedArray())
        } else {
            val intent = Intent(context, LocationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    if (granted) {
        content()
    } else {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Text("Se requieren permisos para funcionar", color = Color.White, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FerroDashboard(viewModel: MainViewModel, onNavigateToRouteDetail: () -> Unit) {
    val mexicoTime by viewModel.mexicoCityTime.collectAsState()
    val speed by viewModel.currentSpeed.collectAsState()
    val pk by viewModel.currentPK.collectAsState()
    val isAscending by viewModel.isAscending.collectAsState()
    val nextStation by viewModel.nextStation.collectAsState()
    val nextLimitation by viewModel.nextLimitation.collectAsState()
    val alert by viewModel.proximityAlert.collectAsState()
    val scheduleStatus by viewModel.scheduleStatus.collectAsState()
    val activeLimitation by viewModel.activeLimitation.collectAsState()
    val selectedRouteId by viewModel.selectedRouteId.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    
    val activeRoute = routes.find { it.id == selectedRouteId }

    var showEditPK by remember { mutableStateOf(false) }
    var showIncidentDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FERRO - CDMX: $mexicoTime", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        activeRoute?.let { Text("Marcha: ${it.trainNumber}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToRouteDetail) { Icon(Icons.Default.Info, "Itinerario", tint = Color.White) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            if (selectedRouteId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FloatingActionButton(
                        onClick = { showFinishDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Check, "Finalizar Viaje")
                    }
                    FloatingActionButton(
                        onClick = { showIncidentDialog = true },
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Warning, "Incidencia")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (selectedRouteId == null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No existe ruta seleccionada.\nVe a la pestaña Rutas.", textAlign = TextAlign.Center, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sentido:", color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = isAscending,
                        onClick = { viewModel.setDirection(true) },
                        label = { Text("Ascendente") },
                        leadingIcon = if (isAscending) { { Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) } } else null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = !isAscending,
                        onClick = { viewModel.setDirection(false) },
                        label = { Text("Descendente") },
                        leadingIcon = if (!isAscending) { { Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)) } } else null
                    )
                }

                alert?.let { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800)), modifier = Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp).align(Alignment.CenterHorizontally), fontWeight = FontWeight.Bold, color = Color.White) } }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$speed", fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("km/h", fontSize = 24.sp, color = Color.White)
                    val currentLimit = activeLimitation?.speedLimit ?: activeRoute?.maxSpeed ?: 80
                    val isOverSpeed = speed > currentLimit
                    Text("LÍMITE: $currentLimit km/h", color = if(isOverSpeed) Color.Red else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (isOverSpeed) Text("¡SOBREVELOCIDAD!", color = Color.Red, fontWeight = FontWeight.ExtraBold)
                }

                scheduleStatus?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = if (it.contains("RETRASO")) Color.Red else if (it.contains("ADELANTO")) Color.Blue else Color(0xFF4CAF50)), modifier = Modifier.fillMaxWidth()) {
                        Text(it, Modifier.padding(16.dp).fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp)
                    }
                }

                Card(Modifier.fillMaxWidth().clickable { showEditPK = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PK ACTUAL (Toca para ajustar)", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Text(String.format(Locale.getDefault(), "km %.3f", pk), fontSize = 42.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }

                nextLimitation?.let { limit ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("PRÓXIMA LIMITACIÓN: ${limit.name}", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Distancia: ${String.format(Locale.getDefault(), "%.1f", abs(limit.kilometerPoint - pk))} km", color = Color.White)
                            Text("Límite: ${limit.speedLimit} km/h", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }
                }

                nextStation?.let { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { 
                            Text("PRÓXIMA PARADA", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            Text(it.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text("Entrada: ${it.arrivalTime ?: "--:--"}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                        }
                        val distance = abs(it.kilometerPoint - pk)
                        Text(String.format(Locale.getDefault(), "%.1f km", distance), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                } }
            }
            // Espacio al final para que el FAB no tape contenido
            Spacer(modifier = Modifier.height(80.dp))
        }

        if (showEditPK) {
            EditPKDialog(currentPKValue = pk, onDismiss = { showEditPK = false }, onConfirm = { viewModel.setPK(it); showEditPK = false })
        }

        if (showIncidentDialog) {
            IncidentDialog(onDismiss = { showIncidentDialog = false }, onConfirm = { viewModel.addIncident(it); triggerFeedback(context); showIncidentDialog = false })
        }

        if (showFinishDialog) {
            FinishViajeDialog(onDismiss = { showFinishDialog = false }, onConfirm = { notes -> viewModel.finishWorkShift(notes); showFinishDialog = false })
        }
    }
}

@Composable
fun FinishViajeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Finalizar Viaje", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("¿Deseas guardar notas sobre este viaje?", color = Color.White.copy(0.7f))
                TextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = { Text("Ej: Retraso por cruce, clima...") },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(notes) }) { Text("Guardar en Cuaderno") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}

@Composable
fun EditPKDialog(currentPKValue: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var pkText by remember { mutableStateOf(currentPKValue.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Ajustar PK Actual", color = Color.White) },
        text = { TextField(value = pkText, onValueChange = { pkText = it }, label = { Text("Nuevo Kilómetro") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
        confirmButton = { Button(onClick = { onConfirm(pkText.replace(',', '.').toDoubleOrNull() ?: currentPKValue) }) { Text("Actualizar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}

private fun triggerFeedback(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1))
    }
    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 200)
}

@Composable
fun IncidentDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val types = listOf("Señal en Rojo", "Avería Técnica", "Parada No Prevista", "Cruce de Tren", "Obstrucción en Vía")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Registrar Incidencia", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { type ->
                    Button(onClick = { onConfirm(type) }, modifier = Modifier.fillMaxWidth()) { Text(type) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = Color.White) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesListScreen(viewModel: MainViewModel, onNavigateToEditRoute: (Long) -> Unit) {
    val routes by viewModel.allRoutes.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddRoute by remember { mutableStateOf(false) }
    var routeToDelete by remember { mutableStateOf<RouteEntity?>(null) }
    var selectedRouteForExport by remember { mutableStateOf<RouteEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                val json = viewModel.getRouteJson(selectedRouteForExport?.id ?: return@launch)
                if (json != null) {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(json.toByteArray())
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openInputStream(it)?.use { input ->
                    val json = input.bufferedReader().readText()
                    viewModel.importRouteFromJson(json)
                }
            }
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Gestión de Rutas", color = Color.White) }, 
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Default.FileDownload, "Importar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            ) 
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAddRoute = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(routes) { route ->
                ListItem(
                    headlineContent = { Text(route.name, color = Color.White) },
                    supportingContent = { Text("Tren: ${route.trainNumber} | Máx: ${route.maxSpeed} km/h", color = Color.White.copy(alpha = 0.7f)) },
                    trailingContent = { 
                        Row {
                            IconButton(onClick = { 
                                selectedRouteForExport = route
                                exportLauncher.launch("${route.name}.json")
                            }) { Icon(Icons.Default.FileUpload, "Exportar", tint = Color.White) }
                            IconButton(onClick = { viewModel.cloneRoute(route) }) { Icon(Icons.Default.Refresh, "Clonar", tint = Color.White) }
                            IconButton(onClick = { viewModel.invertRoute(route) }) { Icon(Icons.AutoMirrored.Filled.CompareArrows, "Invertir", tint = Color.White) }
                            IconButton(onClick = { onNavigateToEditRoute(route.id) }) { Icon(Icons.Default.Edit, null, tint = Color.White) }
                            IconButton(onClick = { routeToDelete = route }) { Icon(Icons.Default.Delete, null, tint = Color.White) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { viewModel.selectRoute(route.id) }
                )
            }
        }
        
        if (showAddRoute) {
            AddRouteDialog(onDismiss = { showAddRoute = false }, onConfirm = { name, number, speed, length ->
                scope.launch { viewModel.ferroDao.insertRoute(RouteEntity(name = name, trainNumber = number, maxSpeed = speed, trainLength = length)) }
                showAddRoute = false
            })
        }

        routeToDelete?.let { route ->
            AlertDialog(
                onDismissRequest = { routeToDelete = null },
                title = { Text("¿Borrar Ruta?") },
                text = { Text("Esta acción eliminará permanentemente la ruta '${route.name}' y todas sus estaciones.") },
                confirmButton = {
                    Button(onClick = { viewModel.deleteRoute(route); routeToDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Borrar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { routeToDelete = null }) { Text("Cancelar") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsManagerScreen(viewModel: MainViewModel) {
    val stations by viewModel.allStations.collectAsState()
    var showAddStation by remember { mutableStateOf(false) }
    var stationToDelete by remember { mutableStateOf<StationEntity?>(null) }
    var stationToEdit by remember { mutableStateOf<StationEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Base de Datos de Estaciones", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddStation = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(stations) { station ->
                ListItem(
                    headlineContent = { Text(station.name, color = Color.White) },
                    supportingContent = { Text("PK: ${station.kilometerPoint}", color = Color.White.copy(alpha = 0.7f)) },
                    trailingContent = { 
                        Row {
                            IconButton(onClick = { stationToEdit = station }) { Icon(Icons.Default.Edit, null, tint = Color.White) }
                            IconButton(onClick = { stationToDelete = station }) { Icon(Icons.Default.Delete, null, tint = Color.White) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        if (showAddStation) {
            AddStationDialog(onDismiss = { showAddStation = false }, onConfirm = { viewModel.insertStation(it) })
        }

        if (stationToEdit != null) {
            AddStationDialog(editingStation = stationToEdit, onDismiss = { stationToEdit = null }, onConfirm = { viewModel.insertStation(it) })
        }

        stationToDelete?.let { station ->
            AlertDialog(
                onDismissRequest = { stationToDelete = null },
                title = { Text("¿Borrar Estación?") },
                text = { Text("Se eliminará '${station.name}' de la base de datos global.") },
                confirmButton = {
                    Button(onClick = { viewModel.deleteStation(station); stationToDelete = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Borrar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stationToDelete = null }) { Text("Cancelar") }
                }
            )
        }
    }
}

@Composable
fun AddStationDialog(editingStation: StationEntity? = null, onDismiss: () -> Unit, onConfirm: (StationEntity) -> Unit) {
    var name by remember(editingStation) { mutableStateOf(editingStation?.name ?: "") }
    var pk by remember(editingStation) { mutableStateOf(editingStation?.kilometerPoint?.toString() ?: "") }
    val textFieldColors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF2C4A5E), unfocusedContainerColor = Color(0xFF2C4A5E))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (editingStation == null) "Nueva Estación Global" else "Editar Estación Global", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Nombre Estación") }, colors = textFieldColors)
                TextField(value = pk, onValueChange = { pk = it }, label = { Text("PK") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = textFieldColors)
            }
        },
        confirmButton = { Button(onClick = { 
            val finalPk = pk.replace(',', '.').toDoubleOrNull() ?: 0.0
            val finalStation = editingStation?.copy(name = name, kilometerPoint = finalPk) 
                ?: StationEntity(name = name, kilometerPoint = finalPk)
            onConfirm(finalStation)
            onDismiss() 
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}

@Composable
fun AddRouteDialog(onDismiss: () -> Unit, onConfirm: (String, String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("80") }
    var length by remember { mutableStateOf("0") }

    val textFieldColors = TextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = Color(0xFF2C4A5E),
        unfocusedContainerColor = Color(0xFF2C4A5E)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Nueva Ruta", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Nombre Ruta") }, colors = textFieldColors)
                TextField(value = number, onValueChange = { number = it }, label = { Text("Nº Tren") }, colors = textFieldColors)
                TextField(value = speed, onValueChange = { speed = it }, label = { Text("V. Máxima") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = textFieldColors)
                TextField(value = length, onValueChange = { length = it }, label = { Text("Longitud (m)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = textFieldColors)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name, number, speed.toIntOrNull() ?: 80, length.toIntOrNull() ?: 0) }) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditRouteScreen(viewModel: MainViewModel, routeId: Long, onBack: () -> Unit) {
    val points by viewModel.ferroDao.getPointsForRoute(routeId).collectAsState(initial = emptyList())
    val allStations by viewModel.allStations.collectAsState()
    
    var routeName by rememberSaveable { mutableStateOf("") }
    var trainNumber by rememberSaveable { mutableStateOf("") }
    var maxSpeed by rememberSaveable { mutableStateOf("") }
    var routeEntity by remember { mutableStateOf<RouteEntity?>(null) }
    var initialLoadDone by remember(routeId) { mutableStateOf(false) }

    LaunchedEffect(routeId) {
        if (!initialLoadDone) {
            viewModel.ferroDao.getRouteById(routeId)?.let {
                routeEntity = it
                routeName = it.name
                trainNumber = it.trainNumber
                maxSpeed = it.maxSpeed.toString()
                initialLoadDone = true
            }
        }
    }

    var showAddPoint by remember { mutableStateOf(false) }
    var pointToEdit by remember { mutableStateOf<RoutePointEntity?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Editar Itinerario", color = Color.White) }, 
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } },
                actions = {
                    IconButton(onClick = {
                        routeEntity?.let {
                            viewModel.updateRoute(it.copy(
                                name = routeName,
                                trainNumber = trainNumber,
                                maxSpeed = maxSpeed.toIntOrNull() ?: 80
                            ))
                            onBack()
                        }
                    }) {
                        Icon(Icons.Default.Save, "Guardar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            ) 
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAddPoint = true }, containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, null) } }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val textFieldColors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF2C4A5E),
                unfocusedContainerColor = Color(0xFF2C4A5E)
            )
            
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = routeName, onValueChange = { routeName = it }, label = { Text("Nombre Ruta") }, colors = textFieldColors, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = trainNumber, onValueChange = { trainNumber = it }, label = { Text("Nº Tren") }, colors = textFieldColors, modifier = Modifier.weight(1f))
                    TextField(value = maxSpeed, onValueChange = { maxSpeed = it }, label = { Text("V. Máx") }, colors = textFieldColors, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(points) { point ->
                    val typeInfo = if (point.type == PointType.STATION) {
                        "E: ${point.arrivalTime} S: ${point.departureTime}"
                    } else {
                        "PK: ${point.kilometerPoint} - ${point.endKm ?: point.kilometerPoint} | V.Máx: ${point.speedLimit}"
                    }
                    ListItem(
                        headlineContent = { Text(point.name, color = Color.White) },
                        supportingContent = { Text(if (point.type == PointType.STATION) "PK: ${point.kilometerPoint} | $typeInfo" else typeInfo, color = Color.White.copy(alpha = 0.7f)) },
                        trailingContent = { 
                            Row {
                                IconButton(onClick = { pointToEdit = point }) { Icon(Icons.Default.Edit, null, tint = Color.White) }
                                IconButton(onClick = { scope.launch { viewModel.ferroDao.deleteRoutePoint(point) } }) { Icon(Icons.Default.Delete, null, tint = Color.White) }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }
        }
        if (showAddPoint) {
            AddPointDialog(stations = allStations, onDismiss = { showAddPoint = false }, onConfirm = { point -> scope.launch { viewModel.ferroDao.insertRoutePoint(point.copy(routeId = routeId, order = points.size)) }; showAddPoint = false })
        }
        if (pointToEdit != null) {
            AddPointDialog(
                stations = allStations,
                editingPoint = pointToEdit,
                onDismiss = { pointToEdit = null },
                onConfirm = { point -> 
                    scope.launch { viewModel.ferroDao.insertRoutePoint(point) }
                    pointToEdit = null
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPointDialog(stations: List<StationEntity>, editingPoint: RoutePointEntity? = null, onDismiss: () -> Unit, onConfirm: (RoutePointEntity) -> Unit) {
    var type by remember { mutableStateOf(editingPoint?.type ?: PointType.STATION) }
    var name by remember { mutableStateOf(editingPoint?.name ?: "") }
    var pk by remember { mutableStateOf(editingPoint?.kilometerPoint?.toString() ?: "") }
    var endPk by remember { mutableStateOf(editingPoint?.endKm?.toString() ?: "") }
    var arrival by remember { mutableStateOf(editingPoint?.arrivalTime ?: "") }
    var departure by remember { mutableStateOf(editingPoint?.departureTime ?: "") }
    var limit by remember { mutableStateOf(editingPoint?.speedLimit?.toString() ?: "") }

    var showStationSelector by remember { mutableStateOf(false) }

    val textFieldColors = TextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = Color(0xFF2C4A5E),
        unfocusedContainerColor = Color(0xFF2C4A5E)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if(editingPoint == null) "Añadir Punto" else "Editar Punto", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    FilterChip(selected = type == PointType.STATION, onClick = { type = PointType.STATION }, label = { Text("Estación") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = type == PointType.LIMITATION, onClick = { type = PointType.LIMITATION }, label = { Text("Limitación") })
                }

                if (type == PointType.STATION) {
                    Button(onClick = { showStationSelector = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Seleccionar de Base de Datos")
                    }
                }

                TextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, colors = textFieldColors)
                TextField(value = pk, onValueChange = { pk = it }, label = { Text(if (type == PointType.STATION) "PK" else "PK Inicio") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = textFieldColors)
                
                if (type == PointType.LIMITATION) {
                    TextField(value = endPk, onValueChange = { endPk = it }, label = { Text("PK Final") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = textFieldColors)
                    TextField(value = limit, onValueChange = { limit = it }, label = { Text("V. Máx") }, colors = textFieldColors)
                }
                
                if (type == PointType.STATION) {
                    TextField(value = arrival, onValueChange = { arrival = it }, label = { Text("H. Entrada (HH:mm)") }, colors = textFieldColors)
                    TextField(value = departure, onValueChange = { departure = it }, label = { Text("H. Salida (HH:mm)") }, colors = textFieldColors)
                }
            }
        },
        confirmButton = { Button(onClick = { 
            val updatedPoint = editingPoint?.copy(
                type = type,
                name = name,
                kilometerPoint = pk.replace(',', '.').toDoubleOrNull() ?: 0.0,
                endKm = if (type == PointType.LIMITATION) endPk.replace(',', '.').toDoubleOrNull() else null,
                arrivalTime = arrival,
                departureTime = departure,
                speedLimit = limit.toIntOrNull()
            ) ?: RoutePointEntity(
                routeId = 0, 
                order = 0, 
                type = type, 
                name = name, 
                kilometerPoint = pk.replace(',', '.').toDoubleOrNull() ?: 0.0, 
                endKm = if (type == PointType.LIMITATION) endPk.replace(',', '.').toDoubleOrNull() else null,
                arrivalTime = arrival, 
                departureTime = departure, 
                speedLimit = limit.toIntOrNull()
            )
            onConfirm(updatedPoint)
            onDismiss()
        }) { Text(if(editingPoint == null) "Añadir" else "Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = Color.White) } }
    )

    if (showStationSelector) {
        AlertDialog(
            onDismissRequest = { showStationSelector = false },
            title = { Text("Seleccionar Estación", color = Color.White) },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    color = Color.Transparent
                ) {
                    LazyColumn {
                        items(stations) { station ->
                            ListItem(
                                headlineContent = { Text(station.name, color = Color.White) },
                                supportingContent = { Text("PK: ${station.kilometerPoint}", color = Color.White.copy(0.7f)) },
                                modifier = Modifier.clickable {
                                    name = station.name
                                    pk = station.kilometerPoint.toString()
                                    showStationSelector = false
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showStationSelector = false }) { Text("Cerrar", color = Color.White) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val shifts by viewModel.allWorkShifts.collectAsState(initial = emptyList())
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    Scaffold(topBar = { TopAppBar(title = { Text("Cuaderno de Bitácora", color = Color.White) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            Text("Viajes Guardados", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) { 
                items(shifts) { shift -> 
                    var expanded by remember { mutableStateOf(false) }
                    
                    val routePoints by if (shift.routeId != null) {
                        viewModel.ferroDao.getPointsForRoute(shift.routeId).collectAsState(initial = emptyList())
                    } else {
                        remember { mutableStateOf(emptyList<RoutePointEntity>()) }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tren: ${shift.trainNumber}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                                Text(dateFormat.format(Date(shift.startTime)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.6f))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${String.format(Locale.getDefault(), "%.1f", shift.totalKilometers)} km recorridos", color = Color.White, fontSize = 16.sp)
                            
                            AnimatedVisibility(visible = expanded) {
                                Column(Modifier.padding(top = 12.dp)) {
                                    if (shift.notes.isNotEmpty()) {
                                        Text("Observaciones:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp)
                                        Text(shift.notes, color = Color.White.copy(0.9f), modifier = Modifier.padding(bottom = 8.dp))
                                    }
                                    
                                    if (routePoints.isNotEmpty()) {
                                        Text("Itinerario de la Ruta:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                                        routePoints.forEach { point ->
                                            Text(
                                                text = if(point.type == PointType.STATION) "• ${point.name} (PK ${point.kilometerPoint}) - ${point.arrivalTime ?: "--:--"}"
                                                       else "⚠ Limitación PK ${point.kilometerPoint} (${point.speedLimit} km/h)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                            )
                                        }
                                    }
                                    
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        IconButton(onClick = { viewModel.deleteWorkShift(shift) }) {
                                            Icon(Icons.Default.Delete, "Borrar", tint = Color.Red.copy(0.7f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } 
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSheetScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val points by viewModel.currentRoutePoints.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Itinerario Activo", color = Color.White) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            items(points) { point -> ListItem(headlineContent = { Text(point.name, color = Color.White) }, supportingContent = { Text("PK: ${point.kilometerPoint}", color = Color.White.copy(alpha = 0.7f)) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
        }
    }
}