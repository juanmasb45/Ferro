package com.juanma.ferro

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Home)
    object Routes : Screen("routes", "Rutas", Icons.AutoMirrored.Filled.List)
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
    val isOverSpeed by viewModel.isOverSpeed.collectAsState()

    LaunchedEffect(alert) {
        alert?.let { onSpeak(it) }
    }

    LaunchedEffect(isOverSpeed) {
        if (isOverSpeed) {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            try {
                while (true) {
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 200)
                    delay(1000)
                }
            } catch (e: Exception) {
            } finally {
                toneGenerator.release()
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = Color.White
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                val items = listOf(Screen.Dashboard, Screen.Routes, Screen.Settings)
                
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
                    onNavigateToEditPoints = { routeId -> navController.navigate("edit_route_points/$routeId") }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(
                "edit_route_points/{routeId}",
                arguments = listOf(navArgument("routeId") { type = NavType.LongType })
            ) { backStackEntry ->
                val routeId = backStackEntry.arguments?.getLong("routeId") ?: 0L
                EditRoutePointsScreen(
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
    val alert by viewModel.proximityAlert.collectAsState()
    val scheduleStatus by viewModel.scheduleStatus.collectAsState()
    val selectedRouteId by viewModel.selectedRouteId.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val currentLimit by viewModel.currentLimit.collectAsState()
    val isOverSpeed by viewModel.isOverSpeed.collectAsState()
    
    val activeRoute = routes.find { it.id == selectedRouteId }

    var showEditPK by remember { mutableStateOf(false) }
    var showIncidentDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }

    val dialogColors = TextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color.LightGray,
        cursorColor = MaterialTheme.colorScheme.primary
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("FERRO - CDMX: $mexicoTime", style = MaterialTheme.typography.titleLarge, color = Color.White)
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
                        leadingIcon = if (isAscending) { { Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) } } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = !isAscending,
                        onClick = { viewModel.setDirection(false) },
                        label = { Text("Descendente") },
                        leadingIcon = if (!isAscending) { { Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)) } } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = Color.White
                        )
                    )
                }

                alert?.let { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800)), modifier = Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp).align(Alignment.CenterHorizontally), fontWeight = FontWeight.Bold, color = Color.White) } }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$speed", fontSize = 120.sp, fontWeight = FontWeight.Bold, color = if(isOverSpeed) Color.Red else Color.White)
                    Text("km/h", fontSize = 24.sp, color = if(isOverSpeed) Color.Red else Color.White)
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
                        Text(String.format(Locale.getDefault(), "km %.3f", pk), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(Modifier.height(8.dp))
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("PRÓXIMA ESTACIÓN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(viewModel.nextStation.collectAsState().value?.name ?: "---", fontWeight = FontWeight.Bold, color = Color.White)
                            Text("PK: ${viewModel.nextStation.collectAsState().value?.kilometerPoint ?: "---"}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                        }
                    }
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("PRÓX. LIMITACIÓN", style = MaterialTheme.typography.labelSmall, color = Color.Yellow)
                            Text(viewModel.nextLimitation.collectAsState().value?.name ?: "Ninguna", fontWeight = FontWeight.Bold, color = Color.White)
                            viewModel.nextLimitation.collectAsState().value?.let {
                                Text("A ${String.format(Locale.getDefault(), "%.1f", abs(it.kilometerPoint - pk))} km", style = MaterialTheme.typography.bodySmall, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditPK) {
        var textValue by remember { mutableStateOf(pk.toString()) }
        AlertDialog(
            onDismissRequest = { showEditPK = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Ajustar PK Manual", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Kilometraje") },
                        colors = dialogColors
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    textValue.replace(",", ".").toDoubleOrNull()?.let { viewModel.setPK(it) }
                    showEditPK = false
                }) { Text("Guardar", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showEditPK = false }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    if (showIncidentDialog) {
        AlertDialog(
            onDismissRequest = { showIncidentDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Registrar Incidencia", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Selecciona el tipo de incidencia ocurrida en el PK ${String.format(Locale.getDefault(), "%.3f", pk)}:", color = Color.White)
                    Button(
                        onClick = { viewModel.addIncident("Finalización de ATV"); showIncidentDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) { Text("Finalización de ATV", color = Color.White) }
                    
                    Button(
                        onClick = { viewModel.addIncident("Incidencia de Circulación"); showIncidentDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) { Text("Incidencia de Circulación", color = Color.White) }
                    
                    Button(
                        onClick = { viewModel.addIncident("Incidencia de Vehículo"); showIncidentDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("Incidencia de Vehículo", color = Color.White) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showIncidentDialog = false }) { Text("Cancelar", color = Color.LightGray) } }
        )
    }

    if (showFinishDialog) {
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Finalizar Viaje", color = Color.White) },
            text = {
                Column {
                    Text("¿Deseas cerrar la jornada actual?", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = notes, 
                        onValueChange = { notes = it }, 
                        label = { Text("Notas/Observaciones") },
                        colors = dialogColors
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.finishWorkShift(notes)
                    showFinishDialog = false
                }) { Text("Finalizar", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { showFinishDialog = false }) { Text("Volver", color = Color.LightGray) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesListScreen(viewModel: MainViewModel, onNavigateToEditPoints: (Long) -> Unit) {
    val routes by viewModel.allRoutes.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var routeToEdit by remember { mutableStateOf<RouteEntity?>(null) }
    var routeToDelete by remember { mutableStateOf<RouteEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val dialogColors = TextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color.LightGray,
        cursorColor = MaterialTheme.colorScheme.primary
    )

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                val json = viewModel.getAllRoutesExportJson()
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(json.toByteArray())
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                    viewModel.importRoutesFromJson(reader.readText())
                }
            }
        }
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Mis Rutas", color = Color.White) },
                actions = {
                    IconButton(onClick = { importLauncher.launch("application/json") }) { Icon(Icons.Default.UploadFile, "Importar", tint = Color.White) }
                    IconButton(onClick = { exportLauncher.launch("respaldo_rutas_ferro.json") }) { Icon(Icons.Default.Save, "Exportar", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            ) 
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White) { Icon(Icons.Default.Add, "Nueva Ruta") }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            itemsIndexed(routes) { _, route ->
                ListItem(
                    headlineContent = { Text(route.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("Tren: ${route.trainNumber} | Máx: ${route.maxSpeed} km/h") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = {
                                scope.launch {
                                    val json = viewModel.getSingleRouteExportJson(route)
                                    val fileName = "ruta_${route.name.replace(" ", "_")}.json"
                                    val file = File(context.cacheDir, fileName)
                                    file.writeText(json)
                                    
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newRawUri("Ruta Ferro", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Compartir Ruta"))
                                }
                            }) { Icon(Icons.Default.Share, "Compartir", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { viewModel.invertRoute(route) }) { Icon(Icons.AutoMirrored.Filled.CompareArrows, "Invertir") }
                            IconButton(onClick = { viewModel.cloneRoute(route) }) { Icon(Icons.Default.CopyAll, "Clonar") }
                            IconButton(onClick = { routeToEdit = route }) { Icon(Icons.Default.Edit, "Editar", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { routeToDelete = route }) { Icon(Icons.Default.Delete, "Borrar", tint = MaterialTheme.colorScheme.error) }
                        }
                    },
                    modifier = Modifier.clickable { viewModel.selectRoute(route.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = Color.White, supportingColor = Color.LightGray)
                )
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var number by remember { mutableStateOf("") }
        var speed by remember { mutableStateOf("80") }
        
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Nueva Ruta", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre de la ruta") }, colors = dialogColors)
                    OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Número de tren") }, colors = dialogColors)
                    OutlinedTextField(
                        value = speed, 
                        onValueChange = { speed = it }, 
                        label = { Text("Velocidad Máxima") }, 
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = dialogColors
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = speed.toIntOrNull() ?: 80
                    scope.launch { viewModel.ferroDao.insertRoute(RouteEntity(name = name, trainNumber = number, maxSpeed = s, trainLength = 0)) }
                    showAddDialog = false
                }) { Text("Crear", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    if (routeToEdit != null) {
        var name by remember { mutableStateOf(routeToEdit?.name ?: "") }
        var number by remember { mutableStateOf(routeToEdit?.trainNumber ?: "") }
        var speed by remember { mutableStateOf(routeToEdit?.maxSpeed.toString()) }

        AlertDialog(
            onDismissRequest = { routeToEdit = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Editar Datos de Ruta", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, colors = dialogColors)
                    OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Tren") }, colors = dialogColors)
                    OutlinedTextField(value = speed, onValueChange = { speed = it }, label = { Text("Vel. Máxima") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = dialogColors)
                    
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { 
                            onNavigateToEditPoints(routeToEdit!!.id)
                            routeToEdit = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Icon(Icons.Default.LocationOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Editar Paradas y Limitaciones")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    routeToEdit?.let { 
                        viewModel.updateRoute(it.copy(name = name, trainNumber = number, maxSpeed = speed.toIntOrNull() ?: 80))
                    }
                    routeToEdit = null
                }) { Text("Guardar", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { routeToEdit = null }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }

    if (routeToDelete != null) {
        AlertDialog(
            onDismissRequest = { routeToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Eliminar Ruta", color = Color.White) },
            text = { Text("¿Seguro que deseas eliminar '${routeToDelete?.name}'?", color = Color.White) },
            confirmButton = { TextButton(onClick = { routeToDelete?.let { viewModel.deleteRoute(it) }; routeToDelete = null }) { Text("Eliminar", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { routeToDelete = null }) { Text("Cancelar", color = Color.LightGray) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditRoutePointsScreen(viewModel: MainViewModel, routeId: Long, onBack: () -> Unit) {
    var routeEntity by remember { mutableStateOf<RouteEntity?>(null) }
    val points by viewModel.ferroDao.getPointsForRoute(routeId).collectAsState(initial = emptyList())
    
    val itemList = remember { mutableStateListOf<RoutePointEntity>() }
    val haptic = LocalHapticFeedback.current
    
    LaunchedEffect(points) {
        if (points.isNotEmpty()) {
            itemList.clear()
            itemList.addAll(points.sortedBy { it.order })
        }
    }

    var showAddPoint by remember { mutableStateOf(false) }
    var pointToEdit by remember { mutableStateOf<RoutePointEntity?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val dialogColors = TextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = Color.LightGray,
        cursorColor = MaterialTheme.colorScheme.primary
    )

    LaunchedEffect(routeId) {
        routeEntity = viewModel.ferroDao.getRouteById(routeId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Puntos de ${routeEntity?.name ?: ""}", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddPoint = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White) { Icon(Icons.Default.AddLocation, "Añadir Punto") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Text("Mantén pulsado cualquier punto para desplazar", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
            
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(itemList, key = { _, item -> item.id }) { index, point ->
                    val scheduleText = when {
                        index == 0 && point.type == PointType.STATION -> "Salida: ${point.departureTime ?: "--:--"}"
                        index == itemList.size - 1 && point.type == PointType.STATION -> "Llegada: ${point.arrivalTime ?: "--:--"}"
                        point.type == PointType.STATION -> {
                            val arr = point.arrivalTime
                            val dep = point.departureTime
                            when {
                                !arr.isNullOrBlank() && !dep.isNullOrBlank() -> "Arr: $arr - Dep: $dep"
                                !arr.isNullOrBlank() -> "Llegada: $arr"
                                !dep.isNullOrBlank() -> "Salida: $dep"
                                else -> "--:--"
                            }
                        }
                        else -> ""
                    }

                    var isDragging by remember { mutableStateOf(false) }
                    var offsetY by remember { mutableStateOf(0f) }
                    val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp, label = "elevation")

                    Card(
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f) else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .offset { IntOffset(0, offsetY.roundToInt()) }
                            .zIndex(if (isDragging) 10f else 1f)
                            .pointerInput(itemList) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { 
                                        isDragging = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = { 
                                        isDragging = false
                                        offsetY = 0f
                                        viewModel.reorderPoints(routeId, itemList.toList())
                                    },
                                    onDragCancel = { 
                                        isDragging = false 
                                        offsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetY += dragAmount.y
                                        
                                        val currentIdx = itemList.indexOfFirst { it.id == point.id }
                                        if (currentIdx != -1) {
                                            val threshold = 100f 
                                            if (offsetY > threshold && currentIdx < itemList.size - 1) {
                                                itemList.add(currentIdx + 1, itemList.removeAt(currentIdx))
                                                offsetY -= 140f 
                                            } else if (offsetY < -threshold && currentIdx > 0) {
                                                itemList.add(currentIdx - 1, itemList.removeAt(currentIdx))
                                                offsetY += 140f
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        ListItem(
                            headlineContent = { Text(point.name, color = Color.White, fontWeight = if(isDragging) FontWeight.Bold else FontWeight.Normal) },
                            supportingContent = { 
                                Text(
                                    when(point.type) {
                                        PointType.STATION -> "Estación - PK: ${point.kilometerPoint} ${if(scheduleText.isNotEmpty()) "- $scheduleText" else ""}"
                                        PointType.LIMITATION -> "LIMITACIÓN: ${point.speedLimit} km/h - Inicio: ${point.kilometerPoint} Fin: ${point.endKm ?: "--"}"
                                    },
                                    color = Color.LightGray
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (point.type == PointType.STATION) Icons.Default.Place else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (point.type == PointType.STATION) MaterialTheme.colorScheme.primary else Color.Yellow,
                                    modifier = Modifier.size(28.dp)
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { pointToEdit = point }) { Icon(Icons.Default.Edit, "Editar", tint = MaterialTheme.colorScheme.primary) }
                                    IconButton(onClick = { scope.launch { viewModel.ferroDao.deleteRoutePoint(point) } }) { Icon(Icons.Default.Delete, "Borrar", tint = Color.Red) }
                                    Icon(Icons.Default.Reorder, null, tint = Color.Gray, modifier = Modifier.padding(start = 4.dp))
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                }
            }
        }
    }

    if (showAddPoint || pointToEdit != null) {
        val editing = pointToEdit != null
        var name by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.name ?: "") }
        var pk by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.kilometerPoint?.toString() ?: "") }
        var type by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.type ?: PointType.STATION) }
        var limit by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.speedLimit?.toString() ?: "") }
        var endKm by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.endKm?.toString() ?: "") }
        var arrival by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.arrivalTime ?: "") }
        var departure by remember(showAddPoint, pointToEdit) { mutableStateOf(pointToEdit?.departureTime ?: "") }

        AlertDialog(
            onDismissRequest = { showAddPoint = false; pointToEdit = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(if (editing) "Editar Punto" else "Añadir Punto", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        FilterChip(
                            selected = type == PointType.STATION, 
                            onClick = { type = PointType.STATION }, 
                            label = { Text("Estación") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = Color.White
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = type == PointType.LIMITATION, 
                            onClick = { type = PointType.LIMITATION }, 
                            label = { Text("Limitación") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = Color.White
                            )
                        )
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, colors = dialogColors)
                    OutlinedTextField(
                        value = pk, 
                        onValueChange = { pk = it }, 
                        label = { Text("PK Inicio") }, 
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = dialogColors
                    )
                    if (type == PointType.STATION) {
                        OutlinedTextField(value = arrival, onValueChange = { arrival = it }, label = { Text("Hora llegada (HH:mm)") }, colors = dialogColors)
                        OutlinedTextField(value = departure, onValueChange = { departure = it }, label = { Text("Hora salida (HH:mm)") }, colors = dialogColors)
                    } else {
                        OutlinedTextField(
                            value = limit, 
                            onValueChange = { limit = it }, 
                            label = { Text("Vel. Máxima") }, 
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = dialogColors
                        )
                        OutlinedTextField(
                            value = endKm, 
                            onValueChange = { endKm = it }, 
                            label = { Text("PK Final") }, 
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = dialogColors
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = pk.replace(",", ".").toDoubleOrNull() ?: 0.0
                    val l = limit.toIntOrNull()
                    val e = endKm.replace(",", ".").toDoubleOrNull()
                    val currentPointId = pointToEdit?.id ?: 0L
                    val currentOrder = pointToEdit?.order ?: itemList.size

                    scope.launch {
                        viewModel.ferroDao.insertRoutePoint(RoutePointEntity(
                            id = currentPointId,
                            routeId = routeId,
                            order = currentOrder,
                            type = type,
                            name = name,
                            kilometerPoint = k,
                            speedLimit = if (type == PointType.LIMITATION) l else null,
                            endKm = if (type == PointType.LIMITATION) e else null,
                            arrivalTime = if (type == PointType.STATION) arrival.ifBlank { null } else null,
                            departureTime = if (type == PointType.STATION) departure.ifBlank { null } else null
                        ))
                    }
                    showAddPoint = false
                    pointToEdit = null
                }) { Text(if (editing) "Guardar" else "Añadir", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showAddPoint = false; pointToEdit = null }) { Text("Cancelar", color = Color.LightGray) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSheetScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val points by viewModel.currentRouteProgress.collectAsState()
    val zoneId = ZoneId.of("America/Mexico_City")
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Libro de Itinerario", color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            itemsIndexed(points) { index, progress ->
                val point = progress.point
                val visit = progress.visit
                
                val theoreticalTime = when {
                    index == 0 -> "Salida: ${point.departureTime ?: "--:--"}"
                    index == points.size - 1 -> "Llegada: ${point.arrivalTime ?: "--:--"}"
                    else -> {
                        val arr = point.arrivalTime
                        val dep = point.departureTime
                        when {
                            !arr.isNullOrBlank() && !dep.isNullOrBlank() -> "Arr: $arr - Dep: $dep"
                            !arr.isNullOrBlank() -> "Arr: $arr"
                            !dep.isNullOrBlank() -> "Dep: $dep"
                            else -> "--:--"
                        }
                    }
                }

                val actualTime = visit?.let {
                    val time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(it.actualTime), zoneId).format(timeFormatter)
                    val delay = if (it.delayMinutes != 0L) {
                        " (${if(it.delayMinutes > 0) "+" else ""}${it.delayMinutes})"
                    } else ""
                    "REAL: $time$delay"
                } ?: ""

                ListItem(
                    headlineContent = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(point.name, fontWeight = FontWeight.Bold, color = Color.White)
                            if (visit != null) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    supportingContent = { 
                        Column {
                            val pkText = if (point.type == PointType.LIMITATION) {
                                "PK: ${point.kilometerPoint} al ${point.endKm ?: "--"}"
                            } else {
                                "PK: ${point.kilometerPoint}"
                            }
                            Text("$pkText | ${if(point.type == PointType.STATION) "Estación" else "Limitación"}", color = Color.LightGray)
                            if (point.type == PointType.STATION) {
                                Text("Teórico: $theoreticalTime", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (point.type == PointType.STATION) {
                                if (actualTime.isNotEmpty()) {
                                    Text(actualTime, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(theoreticalTime.split(": ").last(), color = Color.DarkGray)
                                }
                            } else {
                                Text("${point.speedLimit} km/h", color = Color.Yellow)
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (visit != null) Color.White.copy(alpha = 0.05f) else Color.Transparent)
                )
                HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val shifts by viewModel.allWorkShifts.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val context = LocalContext.current
    var shiftForSummary by remember { mutableStateOf<WorkShiftEntity?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Text("Cuaderno de Bitácora", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(16.dp))
        
        Text("Historial de Jornadas", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shifts) { _, shift ->
                val routeName = routes.find { it.id == shift.routeId }?.name ?: "Ruta desconocida"
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(shift.startTime))
                
                val durationText = if (shift.endTime != null) {
                    val mins = (shift.endTime - shift.startTime) / 60000
                    " | Duración: ${mins / 60}h ${mins % 60}m"
                } else ""

                ListItem(
                    headlineContent = { Text(routeName, color = Color.White) },
                    supportingContent = { Text("Inicio: $date | Dist: ${String.format(Locale.getDefault(), "%.2f", shift.totalKilometers)} km$durationText", color = Color.LightGray) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { shiftForSummary = shift }) { Icon(Icons.Default.Summarize, "Resumen", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { viewModel.deleteWorkShift(shift) }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = Color.DarkGray)
            }
        }
    }

    if (shiftForSummary != null) {
        var summaryText by remember { mutableStateOf("Cargando...") }
        LaunchedEffect(shiftForSummary) {
            summaryText = viewModel.getShiftSummaryText(shiftForSummary!!)
        }

        AlertDialog(
            onDismissRequest = { shiftForSummary = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Resumen de Jornada", color = Color.White) },
            text = {
                Text(summaryText, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, summaryText)
                    }
                    context.startActivity(Intent.createChooser(intent, "Compartir resumen"))
                }) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Compartir")
                }
            },
            dismissButton = {
                TextButton(onClick = { shiftForSummary = null }) { Text("Cerrar", color = Color.LightGray) }
            }
        )
    }
}
