package com.example.autenticacioncontinua

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.autenticacioncontinua.domain.session.CollectionPolicy
import com.example.autenticacioncontinua.domain.session.SessionState
import com.example.autenticacioncontinua.presentation.MainViewModel
import com.example.autenticacioncontinua.presentation.FederatedViewModel
import com.example.autenticacioncontinua.presentation.UiState
import com.example.autenticacioncontinua.service.DataCollectionService
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

// ── Colors ───────────────────────────────────────────────────────────────
// `internal` y no `private`: las tarjetas de protección y del diario viven en
// su propio fichero (ProtectionAndDiary.kt) para que este no siga creciendo, y
// necesitan la misma paleta. Duplicar los hex en dos sitios es cómo acaban
// divergiendo.
internal val DarkBg        = Color(0xFF0D1117)
internal val CardBg        = Color(0xFF161B22)
private val CardBorder    = Color(0xFF30363D)
internal val AccentCyan    = Color(0xFF58A6FF)
internal val AccentGreen   = Color(0xFF3FB950)
internal val AccentOrange  = Color(0xFFD29922)
private val AccentPurple  = Color(0xFFBC8CFF)
internal val AccentRed     = Color(0xFFF85149)
internal val TextPrimary   = Color(0xFFE6EDF3)
internal val TextSecondary = Color(0xFF8B949E)

private val AppColorScheme = darkColorScheme(
    background = DarkBg,
    surface = CardBg,
    primary = AccentCyan,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onPrimary = DarkBg
)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val serviceIntent = Intent(this, DataCollectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Sin librería de navegación: son dos pantallas y una de
                    // ellas sólo la abre el investigador. Un NavHost aquí sería
                    // más andamiaje que función.
                    val (enCaptura, setEnCaptura) = remember { mutableStateOf(false) }
                    if (enCaptura) {
                        LabeledCaptureScreen(onClose = { setEnCaptura(false) })
                    } else {
                        MainScreen(onAbrirCaptura = { setEnCaptura(true) })
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Composables
// ═════════════════════════════════════════════════════════════════════════

@Composable
fun MainScreen(
    onAbrirCaptura: () -> Unit = {},
    viewModel: MainViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val (pidDialog, setPidDialog) = remember { mutableStateOf(false) }
    val (pidInput, setPidInput) = remember { mutableStateOf("") }

    // Cuando el zip está listo se abre el selector de aplicación. Va en un
    // LaunchedEffect y no en el onClick porque la exportación es asíncrona:
    // con dos días de recolección el checkpoint y la compresión tardan varios
    // segundos, y lanzar el intent antes de tenerlo compartiría un fichero a
    // medias.
    LaunchedEffect(state.databaseZip) {
        state.databaseZip?.let { zip ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zip
            )
            val enviar = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(enviar, "Enviar la base de datos"))
            viewModel.clearDatabaseZip()
        }
    }

    if (pidDialog) {
        AlertDialog(
            onDismissRequest = { setPidDialog(false) },
            title = { Text("Enviar base de datos", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "Escribe el seudónimo que se te asignó. Va en el nombre del " +
                            "fichero para poder identificarlo al recibirlo.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pidInput,
                        onValueChange = setPidInput,
                        label = { Text("Seudónimo", color = TextSecondary) },
                        placeholder = { Text("P1, P2…", color = TextSecondary) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    setPidDialog(false)
                    viewModel.exportDatabase(pidInput)
                }) { Text("Preparar y enviar", color = AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { setPidDialog(false) }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }

    LaunchedEffect(state.exportSuccessMessage) {
        state.exportSuccessMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearExportMessages()
        }
    }

    LaunchedEffect(state.exportErrorMessage) {
        state.exportErrorMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearExportMessages()
        }
    }

    LaunchedEffect(Unit) { viewModel.loadRecordedDates() }
    LaunchedEffect(state.sessionState) {
        if (state.sessionState == SessionState.DAILY_LIMIT_REACHED) {
            viewModel.loadRecordedDates()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { HeaderSection() }
        // Va lo primero, antes incluso del estado de la sesión: si el
        // dispositivo está desprotegido, nada de lo que diga el resto de la
        // pantalla se va a cumplir. Es la pantalla que hay que mirar CON cada
        // participante al darlo de alta (pendiente E).
        item { ProtectionCard() }
        item { StatusCard(state) }
        item { ProgressCard(state) }
        item { DatabaseSizeCard(state) }

        // El entrenamiento federado va ARRIBA, no al final de la lista.
        // Estaba después de las tablas de giroscopio y acelerómetro, que
        // pintan una fila por muestra: con una fecha seleccionada quedaban
        // cientos de filas por medio y el botón resultaba inalcanzable.
        item { FederatedLearningSection() }
        item { DeviceDiaryCard() }

        // Envío de la base al investigador. Sustituye al botón de CSV, que
        // cargaba las dos tablas enteras en memoria: con los dos días de
        // recolección de un participante eso es ~1 M de entidades y no cabe en
        // el heap de un teléfono de gama media. El método sigue existiendo en
        // `DataExportServiceImpl` para inspeccionar históricos pequeños a mano.
        item {
            androidx.compose.material3.Button(
                onClick = { setPidDialog(true) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                enabled = !state.isExporting,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                if (state.isExporting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = DarkBg,
                            modifier = Modifier.size(24.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Preparando la base...", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Enviar base de datos", color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            androidx.compose.material3.Button(
                onClick = onAbrirCaptura,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isExporting,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = CardBg
                )
            ) {
                Text("🧪  Captura etiquetada", color = AccentOrange, fontWeight = FontWeight.Bold)
            }
        }

        // ── Recorded dates ───────────────────────────────────────────
        item {
            Text(
                "Sesiones Registradas",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (state.recordedDates.isEmpty()) {
            item {
                Text(
                    "Aún no hay datos registrados.\nUsa tu dispositivo normalmente y los datos se capturarán automáticamente.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            }
        } else {
            itemsIndexed(state.recordedDates) { _, date ->
                DateCard(
                    date = date,
                    isSelected = date == state.selectedDate,
                    onClick = { viewModel.selectDate(date) }
                )
            }
        }

        // ── Gyroscope data table ─────────────────────────────────────
        if (state.gyroscopeDataForDate.isNotEmpty()) {
            item {
                SensorTableTitle(
                    emoji = "🌀",
                    title = "Giroscopio",
                    date = state.selectedDate,
                    count = state.totalGyroPoints,
                    unit = "rad/s",
                    color = AccentCyan
                )
            }
            item { DataTableHeader("X (rad/s)", "Y (rad/s)", "Z (rad/s)", AccentCyan) }
            itemsIndexed(state.gyroscopeDataForDate) { index, data ->
                DataTableRow(index, data.x, data.y, data.z)
            }
        }

        // ── Accelerometer data table ─────────────────────────────────
        if (state.accelerometerDataForDate.isNotEmpty()) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item {
                SensorTableTitle(
                    emoji = "📐",
                    title = "Acelerómetro",
                    date = state.selectedDate,
                    count = state.totalAccelPoints,
                    unit = "m/s²",
                    color = AccentGreen
                )
            }
            item { DataTableHeader("X (m/s²)", "Y (m/s²)", "Z (m/s²)", AccentGreen) }
            itemsIndexed(state.accelerometerDataForDate) { index, data ->
                DataTableRow(index, data.x, data.y, data.z)
            }
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────────

@Composable
fun HeaderSection() {
    Column {
        Text(text = "🔐", fontSize = 36.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Autenticación Continua",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "IMU: Giroscopio + Acelerómetro @ 50 Hz",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

// ── Status ───────────────────────────────────────────────────────────────

@Composable
fun StatusCard(state: UiState) {
    val (statusText, statusColor, description) = when (state.sessionState) {
        SessionState.IDLE -> Triple(
            "En espera", AccentCyan,
            "El servicio está activo en segundo plano. La recolección comenzará " +
                "automáticamente tras ${CollectionPolicy.requiredContinuousUsageSeconds} s de uso continuo."
        )
        SessionState.MONITORING_USAGE -> Triple(
            "Detectando uso continuo", AccentOrange,
            "Pantalla encendida. Esperando a que se cumplan " +
                "${CollectionPolicy.requiredContinuousUsageSeconds} s de uso continuo…"
        )
        SessionState.RECORDING -> Triple(
            "Grabando datos IMU", AccentGreen,
            "Capturando giroscopio y acelerómetro a 50 Hz. Puedes cerrar la app."
        )
        SessionState.COOLDOWN -> Triple(
            "En pausa entre capturas", AccentCyan,
            if (state.cooldownMinutes > 0)
                "Ya se capturó una ráfaga. La siguiente en unos ${state.cooldownMinutes} min, " +
                    "para repartir las capturas a lo largo del día."
            else
                "Ya se capturó una ráfaga. La siguiente en breve."
        )
        SessionState.DAILY_LIMIT_REACHED -> Triple(
            "Sesión completa ✓", AccentPurple,
            "Se recolectaron los ${CollectionPolicy.DAILY_LIMIT_MINUTES} minutos " +
                "de datos IMU de hoy."
        )
        // Tiene texto propio y no el de RECORDING a propósito: si el dueño ve
        // "Grabando datos IMU" mientras otra persona sostiene el teléfono, no
        // hay forma de notar que se está capturando en el modo equivocado hasta
        // que los datos ya están en la base.
        SessionState.LABELED_CAPTURE -> Triple(
            "Captura etiquetada en curso", AccentOrange,
            "Grabando una ráfaga atribuida a un participante concreto. Estos datos " +
                "NO cuentan como uso ambiental del dueño."
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.sessionState == SessionState.RECORDING ||
                    state.sessionState == SessionState.MONITORING_USAGE ||
                    state.sessionState == SessionState.LABELED_CAPTURE
                ) {
                    PulsingDot(color = statusColor)
                } else {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, lineHeight = 20.sp)
        }
    }
}

@Composable
fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse_alpha"
    )
    Box(Modifier.size(12.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

// ── Progress ─────────────────────────────────────────────────────────────

@Composable
fun ProgressCard(state: UiState) {
    val recorded = state.todayStat?.totalMinutesRecorded ?: 0
    val progress =
        (recorded / CollectionPolicy.DAILY_LIMIT_MINUTES.toFloat()).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Progreso de hoy", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "$recorded / ${CollectionPolicy.DAILY_LIMIT_MINUTES} min",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = AccentCyan
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = AccentCyan,
                trackColor = CardBorder
            )
        }
    }
}

// ── Database Size ────────────────────────────────────────────────────────

@Composable
fun DatabaseSizeCard(state: UiState) {
    val sizeStr = formatBytes(state.databaseSizeBytes)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("💾  Base de datos local", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Espacio total ocupado por los datos IMU", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Text(
                sizeStr,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AccentOrange
            )
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024          -> "$bytes B"
        bytes < 1024 * 1024   -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else                  -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

// ── Date cards ───────────────────────────────────────────────────────────

@Composable
fun DateCard(date: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) AccentCyan.copy(alpha = 0.12f) else CardBg

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, AccentCyan) else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📅  $date",
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) AccentCyan else TextPrimary
            )
            Text(if (isSelected) "▼" else "▶", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

// ── Data Tables ──────────────────────────────────────────────────────────

@Composable
fun SensorTableTitle(emoji: String, title: String, date: String, count: Int, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text("$emoji  ", fontSize = 20.sp)
        Column {
            Text(
                "$title – $date",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = color
            )
            Text(
                "$count muestras · $unit",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun DataTableHeader(col1: String, col2: String, col3: String, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.1f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            HeaderCell("#", Modifier.width(40.dp), accentColor)
            HeaderCell(col1, Modifier.weight(1f), accentColor)
            HeaderCell(col2, Modifier.weight(1f), accentColor)
            HeaderCell(col3, Modifier.weight(1f), accentColor)
        }
    }
}

@Composable
fun HeaderCell(text: String, modifier: Modifier, color: Color) {
    Text(
        text, modifier,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        color = color,
        textAlign = TextAlign.Center
    )
}

@Composable
fun DataTableRow(index: Int, x: Float, y: Float, z: Float) {
    val bgColor = if (index % 2 == 0) CardBg else CardBg.copy(alpha = 0.6f)
    Column {
        Row(
            Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("${index + 1}", Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
            Text(String.format("%.4f", x), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary, textAlign = TextAlign.Center)
            Text(String.format("%.4f", y), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary, textAlign = TextAlign.Center)
            Text(String.format("%.4f", z), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary, textAlign = TextAlign.Center)
        }
        HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}

// ── Federated Learning Section ───────────────────────────────────────────

@Composable
fun FederatedLearningSection(viewModel: FederatedViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val (showDialog, setShowDialog) = remember { mutableStateOf(false) }
    val (hostInput, setHostInput) = remember { mutableStateOf(state.serverHost) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { setShowDialog(false) },
            title = { Text("Configurar Servidor FL", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = setHostInput,
                    label = { Text("Host / IP", color = TextSecondary) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateHost(hostInput)
                    setShowDialog(false)
                }) { Text("Guardar", color = AccentCyan) }
            },
            dismissButton = {
                TextButton(onClick = { setShowDialog(false) }) { Text("Cancelar", color = TextSecondary) }
            },
            containerColor = CardBg
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "🤖 Aprendizaje Federado (Flower)",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = AccentPurple
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Host: ${state.serverHost}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(state.statusMessage, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                androidx.compose.material3.Button(
                    onClick = { setShowDialog(true) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = CardBorder),
                    enabled = !state.isRunning
                ) {
                    Text("Configurar", color = TextPrimary)
                }
                
                androidx.compose.material3.Button(
                    onClick = { viewModel.startFederatedLearning() },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AccentPurple),
                    enabled = !state.isRunning
                ) {
                    if (state.isRunning) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = DarkBg, modifier = Modifier.size(20.dp).padding(end = 8.dp), strokeWidth = 2.dp
                        )
                        Text("Ejecutando...", color = DarkBg)
                    } else {
                        Text("Iniciar FL", color = DarkBg)
                    }
                }
            }

            state.lastRun?.let { run ->
                Spacer(modifier = Modifier.height(20.dp))
                UltimoResultado(run)
            }

            if (state.history.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                HistorialEntrenamientos(state.history)
            }
        }
    }
}

/**
 * Resultado de la última sesión, sobre el conjunto CIEGO.
 *
 * Se muestran EER, FAR y FRR juntos a propósito: un EER razonable con FRR≈1
 * describe un sistema que rechaza siempre al usuario legítimo, y eso sólo se
 * ve mirando las tres tasas a la vez.
 */
@Composable
private fun UltimoResultado(run: com.example.autenticacioncontinua.domain.model.TrainingRun) {
    Text(
        "Último resultado (prueba ciega)",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = TextPrimary
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (!run.hasBlindTest) {
        Text(
            "La sesión no llegó a medirse sobre el conjunto de prueba.",
            color = AccentOrange,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricaCompacta("EER", String.format("%.2f%%", run.testEer * 100), AccentCyan)
        MetricaCompacta("AUC", String.format("%.4f", run.testAuc), AccentCyan)
        MetricaCompacta("FAR", String.format("%.2f%%", run.testFar * 100), TextSecondary)
        MetricaCompacta("FRR", String.format("%.2f%%", run.testFrr * 100), TextSecondary)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "${run.rounds} rondas · ${run.trainWindows} ventanas de entrenamiento · " +
            "${run.testWindows} de prueba · ${run.sessionCount} sesiones de uso",
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )

    if (run.leakageSuspected) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "⚠ Sólo ${run.sessionCount} sesión(es) de uso distintas. El modelo se " +
                "evalúa casi con los mismos datos con los que entrenó, así que este " +
                "número es mejor de lo real. Sigue usando el móvil con normalidad " +
                "unos días.",
            color = AccentOrange,
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (run.thresholdUnusable) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "⚠ Con el umbral calibrado (${String.format("%.3f", run.threshold)}) el " +
                "sistema rechazaría al usuario legítimo casi siempre. El umbral aún " +
                "no es utilizable en tiempo real.",
            color = AccentOrange,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetricaCompacta(etiqueta: String, valor: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(etiqueta, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(
            valor,
            color = color,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * Historial de sesiones, lo más reciente primero.
 *
 * Sin esto el usuario sólo veía "finalizado exitosamente" y no tenía forma de
 * saber si su modelo mejora sesión a sesión o se ha estancado.
 */
@Composable
private fun HistorialEntrenamientos(
    history: List<com.example.autenticacioncontinua.domain.model.TrainingRun>
) {
    val formato = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }

    Text(
        "Historial",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = TextPrimary
    )
    Spacer(modifier = Modifier.height(8.dp))

    history.forEach { run ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formato.format(java.util.Date(run.finishedAtMs)),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                when {
                    !run.completed -> "abortada"
                    !run.hasBlindTest -> "sin medición"
                    else -> "EER ${String.format("%.2f%%", run.testEer * 100)}"
                },
                color = when {
                    !run.completed -> AccentOrange
                    run.leakageSuspected -> AccentOrange
                    else -> TextPrimary
                },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "${run.rounds} rondas · ${run.sessionCount} ses.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}