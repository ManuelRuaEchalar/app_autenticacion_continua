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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.autenticacioncontinua.domain.session.CollectionPolicy
import com.example.autenticacioncontinua.domain.session.SessionState
import com.example.autenticacioncontinua.presentation.FederatedViewModel
import com.example.autenticacioncontinua.presentation.MainViewModel
import com.example.autenticacioncontinua.presentation.UiState
import com.example.autenticacioncontinua.presentation.controlada.JuegoViewModel
import com.example.autenticacioncontinua.service.DataCollectionService
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.CabeceraDeEstado
import com.example.autenticacioncontinua.ui.componentes.EstadoVisual
import com.example.autenticacioncontinua.ui.componentes.Separador
import com.example.autenticacioncontinua.ui.componentes.Tarjeta
import com.example.autenticacioncontinua.ui.componentes.TarjetaDeAviso
import com.example.autenticacioncontinua.ui.componentes.TextoDeEstado
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.controlada.EstudioControlado
import com.example.autenticacioncontinua.ui.theme.AutenticacionContinuaTheme
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * ### La paleta oscura se ha ido (migración del 31/08)
 *
 * Este fichero traía diez constantes de color propias —el azul, el verde, el
 * naranja, el morado y los grises de un tema oscuro tipo GitHub— de cuando la
 * aplicación era sólo la herramienta de recolección. Estaban declaradas como
 * deuda con recuento exacto en `SinColoresLiteralesTest`, y ya no existen: todo
 * lee de `ui/theme`.
 *
 * NO ERA UN ARREGLO COSMÉTICO. La fase de diseño no se hizo para el minijuego,
 * se hizo para la aplicación, y mientras media aplicación viviera en oscuro
 * pasaban tres cosas:
 *
 * 1. **El modo de color quedaba sin fijar de verdad.** El protocolo lo fija
 *    porque en panel OLED el consumo de pantalla depende del color mostrado, y
 *    el consumo es variable dependiente de las propuestas I y II. Con la
 *    herramienta en oscuro y el estudio en claro, cuánto consumía un
 *    participante dependía de en qué pantalla se dejara el teléfono.
 * 2. **El contraste no estaba comprobado.** `ContrasteTest` recorre la paleta
 *    clara; estos diez colores no pasaban por ahí. El naranja de los avisos
 *    (#D29922) da 2.0:1 sobre blanco, y el verde de «grabando» 2.8:1.
 * 3. **Saltar de la herramienta al estudio cambiaba de aplicación a la vista**,
 *    y quien conduce la sesión tiene que reconocer la pantalla de un vistazo.
 */
private enum class Destino { PRINCIPAL, CAPTURA, ESTUDIO }

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }

    /**
     * El ViewModel del minijuego, para poder avisarle de que la visita se ha
     * cortado.
     *
     * Es la MISMA instancia que usa la pantalla: `koinViewModel()` dentro de un
     * `@Composable` resuelve contra el `ViewModelStore` de esta Activity.
     */
    private val juegoViewModel: JuegoViewModel by viewModel()

    /**
     * La aplicación deja de estar delante: llamada entrante, botón de inicio,
     * pantalla apagada.
     *
     * Nadie está tecleando, así que el bloque en curso se marca como
     * interrumpido y la visita se cierra como ABORTADA, conservando los bloques
     * que sí se completaron. Va en `onStop` y no en `onPause` a propósito:
     * `onPause` se dispara también con un diálogo del sistema por encima —un
     * aviso de batería baja, por ejemplo— y eso no justifica tirar una visita.
     *
     * Si el estudio no está abierto, no hace nada.
     */
    override fun onStop() {
        super.onStop()
        juegoViewModel.onPausa("la aplicacion paso a segundo plano")
    }

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
            // EL TEMA ENVUELVE LA APLICACIÓN ENTERA, no sólo el estudio.
            // Antes había dos: un `MaterialTheme` oscuro aquí y el claro dentro
            // de `EstudioControlado`. Con uno solo en la raíz, el modo de color
            // queda fijado por protocolo para todo lo que se dibuje, incluidos
            // los diálogos y los campos de texto de Material.
            AutenticacionContinuaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Tema.colores.fondo
                ) {
                    // Sin librería de navegación: son tres pantallas y dos de
                    // ellas sólo las abre el investigador. Un NavHost aquí sería
                    // más andamiaje que función.
                    val (destino, setDestino) = remember { mutableStateOf(Destino.PRINCIPAL) }
                    when (destino) {
                        Destino.PRINCIPAL -> MainScreen(
                            onAbrirCaptura = { setDestino(Destino.CAPTURA) },
                            onAbrirEstudio = { setDestino(Destino.ESTUDIO) }
                        )

                        Destino.CAPTURA ->
                            LabeledCaptureScreen(onClose = { setDestino(Destino.PRINCIPAL) })

                        Destino.ESTUDIO ->
                            EstudioControlado(onSalir = { setDestino(Destino.PRINCIPAL) })
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
    onAbrirEstudio: () -> Unit = {},
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
            title = { Text("Enviar base de datos", color = Tema.colores.textoPrimario) },
            text = {
                Column {
                    Text(
                        "Escribe el seudónimo que se te asignó. Va en el nombre del " +
                            "fichero para poder identificarlo al recibirlo.",
                        color = Tema.colores.textoSecundario,
                        fontSize = Tipos.menor
                    )
                    Spacer(Modifier.height(Tema.espaciado.medio))
                    OutlinedTextField(
                        value = pidInput,
                        onValueChange = setPidInput,
                        label = { Text("Seudónimo") },
                        placeholder = { Text("P1, P2…") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    setPidDialog(false)
                    viewModel.exportDatabase(pidInput)
                }) { Text("Preparar y enviar", color = Tema.colores.acentoTexto) }
            },
            dismissButton = {
                TextButton(onClick = { setPidDialog(false) }) {
                    Text("Cancelar", color = Tema.colores.textoSecundario)
                }
            },
            containerColor = Tema.colores.superficie,
            titleContentColor = Tema.colores.textoPrimario,
            textContentColor = Tema.colores.textoSecundario
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
            .background(Tema.colores.fondo)
            .padding(horizontal = Tema.espaciado.medio),
        contentPadding = PaddingValues(
            top = Tema.espaciado.seccion,
            bottom = Tema.espaciado.enorme
        ),
        verticalArrangement = Arrangement.spacedBy(Tema.espaciado.medio)
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
            BotonPrimario(
                texto = if (state.isExporting) "Preparando la base…" else "Enviar base de datos",
                onClick = { setPidDialog(true) },
                modifier = Modifier.fillMaxWidth(),
                habilitado = !state.isExporting
            )
        }

        // Entrada a las dos herramientas del investigador. Son SECUNDARIAS y no
        // primarias, y no es un detalle de estilo: quien usa el teléfono a
        // diario no abre ninguna de las dos, y un botón con el peso visual de
        // la acción principal invita a pulsarlo.
        item {
            BotonSecundario(
                texto = "Captura etiquetada",
                onClick = onAbrirCaptura,
                modifier = Modifier.fillMaxWidth(),
                habilitado = !state.isExporting
            )
        }
        item {
            BotonSecundario(
                texto = "Sesión controlada",
                onClick = onAbrirEstudio,
                modifier = Modifier.fillMaxWidth(),
                habilitado = !state.isExporting
            )
        }

        // ── Recorded dates ───────────────────────────────────────────
        item { TituloDeSeccion("Sesiones registradas") }

        if (state.recordedDates.isEmpty()) {
            item {
                Text(
                    "Aún no hay datos registrados.\nUsa tu dispositivo con normalidad " +
                        "y los datos se capturarán automáticamente.",
                    color = Tema.colores.textoSecundario,
                    fontSize = Tipos.menor,
                    lineHeight = Tipos.menor * 1.5f
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
                    title = "Giroscopio",
                    date = state.selectedDate,
                    count = state.totalGyroPoints,
                    unit = "rad/s"
                )
            }
            item { DataTableHeader("X (rad/s)", "Y (rad/s)", "Z (rad/s)") }
            itemsIndexed(state.gyroscopeDataForDate) { index, data ->
                DataTableRow(index, data.x, data.y, data.z)
            }
        }

        // ── Accelerometer data table ─────────────────────────────────
        if (state.accelerometerDataForDate.isNotEmpty()) {
            item { Spacer(modifier = Modifier.height(Tema.espaciado.pequeno)) }
            item {
                SensorTableTitle(
                    title = "Acelerómetro",
                    date = state.selectedDate,
                    count = state.totalAccelPoints,
                    unit = "m/s²"
                )
            }
            item { DataTableHeader("X (m/s²)", "Y (m/s²)", "Z (m/s²)") }
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
        Text(
            text = "Autenticación continua",
            fontSize = Tipos.titulo,
            fontWeight = FontWeight.SemiBold,
            color = Tema.colores.textoPrimario
        )
        Spacer(modifier = Modifier.height(Tema.espaciado.minimo))
        Text(
            text = "IMU: giroscopio + acelerómetro @ 50 Hz",
            fontSize = Tipos.menor,
            color = Tema.colores.textoTerciario
        )
    }
}

// ── Status ───────────────────────────────────────────────────────────────

@Composable
fun StatusCard(state: UiState) {
    val (statusText, estado, description) = when (state.sessionState) {
        SessionState.IDLE -> Triple(
            "En espera", EstadoVisual.NEUTRO,
            "El servicio está activo en segundo plano. La recolección comenzará " +
                "automáticamente tras ${CollectionPolicy.requiredContinuousUsageSeconds} s de uso continuo."
        )
        SessionState.MONITORING_USAGE -> Triple(
            "Detectando uso continuo", EstadoVisual.AVISO,
            "Pantalla encendida. Esperando a que se cumplan " +
                "${CollectionPolicy.requiredContinuousUsageSeconds} s de uso continuo…"
        )
        SessionState.RECORDING -> Triple(
            "Grabando datos IMU", EstadoVisual.EXITO,
            "Capturando giroscopio y acelerómetro a 50 Hz. Puedes cerrar la app."
        )
        SessionState.COOLDOWN -> Triple(
            "En pausa entre capturas", EstadoVisual.NEUTRO,
            if (state.cooldownMinutes > 0)
                "Ya se capturó una ráfaga. La siguiente en unos ${state.cooldownMinutes} min, " +
                    "para repartir las capturas a lo largo del día."
            else
                "Ya se capturó una ráfaga. La siguiente en breve."
        )
        SessionState.DAILY_LIMIT_REACHED -> Triple(
            "Sesión completa", EstadoVisual.EXITO,
            "Se recolectaron los ${CollectionPolicy.DAILY_LIMIT_MINUTES} minutos " +
                "de datos IMU de hoy."
        )
        // Tiene texto propio y no el de RECORDING a propósito: si el dueño ve
        // "Grabando datos IMU" mientras otra persona sostiene el teléfono, no
        // hay forma de notar que se está capturando en el modo equivocado hasta
        // que los datos ya están en la base.
        SessionState.LABELED_CAPTURE -> Triple(
            "Captura etiquetada en curso", EstadoVisual.AVISO,
            "Grabando una ráfaga atribuida a un participante concreto. Estos datos " +
                "NO cuentan como uso ambiental del dueño."
        )

        // Mismo argumento que arriba: si aquí pusiera "Detectando uso continuo"
        // mientras un participante hace su visita, una suspensión que hubiera
        // fallado no se notaría hasta mirar los datos.
        SessionState.SUSPENDIDA_POR_ESTUDIO -> Triple(
            "Recolección suspendida", EstadoVisual.AVISO,
            "Hay una sesión controlada en curso. La recolección ambiental está " +
                "parada para que el uso del participante no entre como uso del dueño; " +
                "se reanuda al terminar la visita."
        )
    }

    val enCurso = state.sessionState == SessionState.RECORDING ||
        state.sessionState == SessionState.MONITORING_USAGE ||
        state.sessionState == SessionState.LABELED_CAPTURE

    Tarjeta(Modifier.fillMaxWidth()) {
        CabeceraDeEstado(
            titulo = statusText,
            estado = estado,
            detalle = description,
            pulsante = enCurso
        )
    }
}

// ── Progress ─────────────────────────────────────────────────────────────

@Composable
fun ProgressCard(state: UiState) {
    val recorded = state.todayStat?.totalMinutesRecorded ?: 0
    val progress =
        (recorded / CollectionPolicy.DAILY_LIMIT_MINUTES.toFloat()).coerceIn(0f, 1f)

    Tarjeta(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Progreso de hoy",
                    fontSize = Tipos.cuerpo,
                    fontWeight = FontWeight.Medium,
                    color = Tema.colores.textoPrimario
                )
                Text(
                    "$recorded / ${CollectionPolicy.DAILY_LIMIT_MINUTES} min",
                    fontSize = Tipos.cuerpo,
                    fontWeight = FontWeight.Medium,
                    color = Tema.colores.textoSecundario
                )
            }
            Spacer(modifier = Modifier.height(Tema.espaciado.medio))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ALTO_BARRA)
                    .clip(RoundedCornerShape(ALTO_BARRA / 2)),
                // El acento es la identidad visual, y la barra de progreso es
                // justo lo que el sistema de diseño reserva para él: un
                // indicador que se mira, no un texto que se lee.
                color = Tema.colores.acento,
                trackColor = Tema.colores.hover,
                gapSize = (-1).dp,
                drawStopIndicator = {}
            )
        }
    }
}

// ── Database Size ────────────────────────────────────────────────────────

@Composable
fun DatabaseSizeCard(state: UiState) {
    Tarjeta(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Base de datos local",
                    fontSize = Tipos.cuerpo,
                    fontWeight = FontWeight.Medium,
                    color = Tema.colores.textoPrimario
                )
                Spacer(modifier = Modifier.height(Tema.espaciado.minimo))
                Text(
                    "Espacio total ocupado por los datos IMU",
                    fontSize = Tipos.menor,
                    color = Tema.colores.textoTerciario
                )
            }
            Text(
                formatBytes(state.databaseSizeBytes),
                fontSize = Tipos.subtitulo,
                fontWeight = FontWeight.Medium,
                color = Tema.colores.textoPrimario
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

/**
 * Una fecha con datos, desplegable.
 *
 * La seleccionada se marca con fondo suave y peso de letra, igual que la
 * entrada elegida de la barra lateral y por el mismo motivo medido: el acento
 * de la paleta no llega al mínimo de contraste sobre los fondos claros para
 * hacer de indicador de selección él solo.
 */
@Composable
fun DateCard(date: String, isSelected: Boolean, onClick: () -> Unit) {
    val forma = RoundedCornerShape(Tema.formas.radioPequeno)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Tema.colores.hover else Tema.colores.superficie,
                forma
            )
            .border(Tema.formas.grosorBorde, Tema.colores.borde, forma)
            .clickable { onClick() }
            .animateContentSize()
            .padding(horizontal = Tema.espaciado.medio, vertical = Tema.espaciado.medio),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            date,
            fontSize = Tipos.cuerpo,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = Tema.colores.textoPrimario
        )
        Text(
            if (isSelected) "▾" else "▸",
            fontSize = Tipos.cuerpo,
            color = Tema.colores.iconoSutil
        )
    }
}

// ── Data Tables ──────────────────────────────────────────────────────────

@Composable
fun SensorTableTitle(title: String, date: String, count: Int, unit: String) {
    Column(modifier = Modifier.padding(top = Tema.espaciado.pequeno)) {
        Text(
            "$title · $date",
            fontSize = Tipos.subtitulo,
            fontWeight = FontWeight.SemiBold,
            color = Tema.colores.textoPrimario
        )
        Text(
            "$count muestras · $unit",
            fontSize = Tipos.menor,
            color = Tema.colores.textoTerciario
        )
    }
}

@Composable
fun DataTableHeader(col1: String, col2: String, col3: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Tema.colores.fondoSecundario,
                RoundedCornerShape(
                    topStart = Tema.formas.radioPequeno,
                    topEnd = Tema.formas.radioPequeno
                )
            )
            .padding(horizontal = Tema.espaciado.pequeno, vertical = Tema.espaciado.pequeno)
    ) {
        HeaderCell("#", Modifier.width(ANCHO_INDICE))
        HeaderCell(col1, Modifier.weight(1f))
        HeaderCell(col2, Modifier.weight(1f))
        HeaderCell(col3, Modifier.weight(1f))
    }
}

@Composable
fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text, modifier,
        fontSize = Tipos.menor,
        fontWeight = FontWeight.Medium,
        color = Tema.colores.textoSecundario,
        textAlign = TextAlign.Center
    )
}

/**
 * Una muestra del sensor.
 *
 * SIN CEBRA. La versión oscura alternaba el fondo de las filas pares, y sobre
 * la paleta clara ese sombreado o es invisible o ensucia; con el separador de
 * un píxel del sistema de diseño la tabla ya se lee, que es lo que la cebra
 * intentaba conseguir.
 */
@Composable
fun DataTableRow(index: Int, x: Float, y: Float, z: Float) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Tema.colores.superficie)
                .padding(horizontal = Tema.espaciado.pequeno, vertical = Tema.espaciado.pequeno)
        ) {
            CeldaDeDato("${index + 1}", Modifier.width(ANCHO_INDICE), atenuada = true)
            CeldaDeDato(String.format("%.4f", x), Modifier.weight(1f))
            CeldaDeDato(String.format("%.4f", y), Modifier.weight(1f))
            CeldaDeDato(String.format("%.4f", z), Modifier.weight(1f))
        }
        Separador()
    }
}

@Composable
private fun CeldaDeDato(texto: String, modifier: Modifier, atenuada: Boolean = false) {
    Text(
        texto, modifier,
        fontSize = Tipos.menor,
        color = if (atenuada) Tema.colores.textoTerciario else Tema.colores.textoPrimario,
        textAlign = TextAlign.Center
    )
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
            title = { Text("Configurar servidor FL", color = Tema.colores.textoPrimario) },
            text = {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = setHostInput,
                    label = { Text("Host / IP") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateHost(hostInput)
                    setShowDialog(false)
                }) { Text("Guardar", color = Tema.colores.acentoTexto) }
            },
            dismissButton = {
                TextButton(onClick = { setShowDialog(false) }) {
                    Text("Cancelar", color = Tema.colores.textoSecundario)
                }
            },
            containerColor = Tema.colores.superficie,
            titleContentColor = Tema.colores.textoPrimario,
            textContentColor = Tema.colores.textoSecundario
        )
    }

    Tarjeta(Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Aprendizaje federado (Flower)",
                fontSize = Tipos.subtitulo,
                fontWeight = FontWeight.SemiBold,
                color = Tema.colores.textoPrimario
            )
            Spacer(modifier = Modifier.height(Tema.espaciado.minimo))
            Text(
                "Host: ${state.serverHost}",
                color = Tema.colores.textoTerciario,
                fontSize = Tipos.menor
            )
            Spacer(modifier = Modifier.height(Tema.espaciado.medio))

            Text(
                state.statusMessage,
                color = Tema.colores.textoSecundario,
                fontSize = Tipos.menor,
                lineHeight = Tipos.menor * 1.5f
            )

            Spacer(modifier = Modifier.height(Tema.espaciado.medio))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Tema.espaciado.pequeno)
            ) {
                BotonSecundario(
                    texto = "Configurar",
                    onClick = { setShowDialog(true) },
                    modifier = Modifier.weight(1f),
                    habilitado = !state.isRunning
                )
                BotonPrimario(
                    texto = if (state.isRunning) "Ejecutando…" else "Iniciar FL",
                    onClick = { viewModel.startFederatedLearning() },
                    modifier = Modifier.weight(1f),
                    habilitado = !state.isRunning
                )
            }

            if (state.isRunning) {
                Spacer(modifier = Modifier.height(Tema.espaciado.medio))
                CircularProgressIndicator(
                    modifier = Modifier.size(DIAMETRO_GIRO),
                    color = Tema.colores.acento,
                    strokeWidth = 2.dp
                )
            }

            state.lastRun?.let { run ->
                Spacer(modifier = Modifier.height(Tema.espaciado.grande))
                UltimoResultado(run)
            }

            if (state.history.size > 1) {
                Spacer(modifier = Modifier.height(Tema.espaciado.grande))
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
        fontSize = Tipos.cuerpo,
        fontWeight = FontWeight.Medium,
        color = Tema.colores.textoPrimario
    )
    Spacer(modifier = Modifier.height(Tema.espaciado.pequeno))

    if (!run.hasBlindTest) {
        TarjetaDeAviso(
            "La sesión no llegó a medirse sobre el conjunto de prueba.",
            EstadoVisual.AVISO
        )
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MetricaCompacta("EER", String.format("%.2f%%", run.testEer * 100), destacada = true)
        MetricaCompacta("AUC", String.format("%.4f", run.testAuc), destacada = true)
        MetricaCompacta("FAR", String.format("%.2f%%", run.testFar * 100))
        MetricaCompacta("FRR", String.format("%.2f%%", run.testFrr * 100))
    }
    Spacer(modifier = Modifier.height(Tema.espaciado.pequeno))
    Text(
        "${run.rounds} rondas · ${run.trainWindows} ventanas de entrenamiento · " +
            "${run.testWindows} de prueba · ${run.sessionCount} sesiones de uso",
        color = Tema.colores.textoTerciario,
        fontSize = Tipos.menor,
        lineHeight = Tipos.menor * 1.5f
    )

    if (run.leakageSuspected) {
        Spacer(modifier = Modifier.height(Tema.espaciado.pequeno))
        TarjetaDeAviso(
            "Sólo ${run.sessionCount} sesión(es) de uso distintas. El modelo se " +
                "evalúa casi con los mismos datos con los que entrenó, así que este " +
                "número es mejor de lo real. Sigue usando el móvil con normalidad " +
                "unos días.",
            EstadoVisual.AVISO
        )
    }
    if (run.thresholdUnusable) {
        Spacer(modifier = Modifier.height(Tema.espaciado.pequeno))
        TarjetaDeAviso(
            "Con el umbral calibrado (${String.format("%.3f", run.threshold)}) el " +
                "sistema rechazaría al usuario legítimo casi siempre. El umbral aún " +
                "no es utilizable en tiempo real.",
            EstadoVisual.AVISO
        )
    }
}

@Composable
private fun MetricaCompacta(etiqueta: String, valor: String, destacada: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(etiqueta, color = Tema.colores.textoTerciario, fontSize = Tipos.menor)
        Text(
            valor,
            color = if (destacada) Tema.colores.textoPrimario else Tema.colores.textoSecundario,
            fontSize = Tipos.cuerpo,
            fontWeight = if (destacada) FontWeight.SemiBold else FontWeight.Normal
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
        fontSize = Tipos.cuerpo,
        fontWeight = FontWeight.Medium,
        color = Tema.colores.textoPrimario
    )
    Spacer(modifier = Modifier.height(Tema.espaciado.pequeno))

    history.forEach { run ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = Tema.espaciado.minimo),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formato.format(java.util.Date(run.finishedAtMs)),
                color = Tema.colores.textoTerciario,
                fontSize = Tipos.menor
            )
            TextoDeEstado(
                texto = when {
                    !run.completed -> "abortada"
                    !run.hasBlindTest -> "sin medición"
                    else -> "EER ${String.format("%.2f%%", run.testEer * 100)}"
                },
                estado = when {
                    !run.completed -> EstadoVisual.AVISO
                    run.leakageSuspected -> EstadoVisual.AVISO
                    else -> EstadoVisual.NEUTRO
                },
                tamano = Tipos.menor,
                peso = FontWeight.Medium
            )
            Text(
                "${run.rounds} rondas · ${run.sessionCount} ses.",
                color = Tema.colores.textoTerciario,
                fontSize = Tipos.menor
            )
        }
    }
}

private val ALTO_BARRA = 8.dp
private val ANCHO_INDICE = 40.dp
private val DIAMETRO_GIRO = 24.dp
