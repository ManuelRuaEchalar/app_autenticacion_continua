package com.example.autenticacioncontinua

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autenticacioncontinua.device.protection.ProtectionStatus
import com.example.autenticacioncontinua.domain.model.DeviceEvent
import com.example.autenticacioncontinua.domain.model.DeviceEventType
import com.example.autenticacioncontinua.domain.repository.IDeviceEventRepository
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Estado de las concesiones que mantienen viva la recolección (pendiente E).
 *
 * Es la pantalla que hay que mirar CON el participante al darlo de alta. El
 * 2026-08-15 tres dispositivos entrenaron a la vez y ninguno llegó al final:
 * dos se murieron a mitad de la federación y el tercero ya había aparecido
 * muerto por la tarde. La causa de fondo era que nadie tenía la exención de
 * batería —la app ni siquiera podía pedirla, faltaba el permiso en el
 * manifiesto— y ese estado no se veía en ningún sitio.
 *
 * Se relee en cada ON_RESUME a propósito: el usuario sale a Ajustes, concede
 * el permiso y vuelve, y la tarjeta debe reflejarlo sin reiniciar la app.
 */
@Composable
fun ProtectionCard(protection: ProtectionStatus = koinInject()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var estado by remember { mutableStateOf(protection.current()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) estado = protection.current()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val color = when {
        !estado.bateriaExenta -> AccentRed
        estado.esXiaomi -> AccentOrange
        else -> AccentGreen
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    if (estado.protegido) "Protegido" else "Protección incompleta",
                    color = color,
                    style = MaterialTheme.typography.titleMedium
                        .copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                estado.resumen,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(12.dp))
            LineaConcesion("Exención de batería", estado.bateriaExenta, comprobable = true)
            if (estado.esXiaomi) {
                LineaConcesion("Inicio automático (MIUI)", false, comprobable = false)
            }

            if (!estado.bateriaExenta) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        // Si el diálogo directo no existe en este dispositivo se
                        // cae a los ajustes de la app, en vez de no hacer nada:
                        // un botón que no responde es peor que uno que te deja a
                        // un toque del sitio correcto.
                        val ok = runCatching {
                            context.startActivity(protection.solicitudBateriaIntent())
                        }.isSuccess
                        if (!ok) {
                            runCatching { context.startActivity(protection.ajustesAppIntent()) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text(
                        "Conceder exención de batería",
                        color = DarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (estado.esXiaomi) {
                val intentAutostart = remember { protection.autostartIntent() }
                if (intentAutostart != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(intentAutostart) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Abrir inicio automático", color = AccentOrange)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineaConcesion(etiqueta: String, concedido: Boolean, comprobable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sin API para consultarlo, un tick verde sería una afirmación que no
        // podemos respaldar. El interrogante es la respuesta honesta, y además
        // le dice al participante que eso hay que mirarlo a mano.
        Text(
            when {
                !comprobable -> "?"
                concedido -> "✓"
                else -> "✗"
            },
            color = when {
                !comprobable -> AccentOrange
                concedido -> AccentGreen
                else -> AccentRed
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(etiqueta, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
        if (!comprobable) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "no se puede comprobar",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Diario de a bordo del dispositivo (pendiente A2).
 *
 * Con participantes remotos, poder pedirles una captura de esta tarjeta vale
 * más que cualquier mejora del modelo: hoy la depuración se hace a ciegas. El
 * contador de "revivido" es el número que de verdad importa — si sale alto,
 * ese dispositivo está desprotegido por mucho que la tarjeta de arriba diga
 * otra cosa.
 */
@Composable
fun DeviceDiaryCard(eventos: IDeviceEventRepository = koinInject()) {
    var lineas by remember { mutableStateOf<List<DeviceEvent>>(emptyList()) }
    var revividos by remember { mutableStateOf(0) }
    var expandido by remember { mutableStateOf(false) }

    LaunchedEffect(expandido) {
        lineas = eventos.recent(if (expandido) 60 else 8)
        revividos = eventos.countSince(
            DeviceEventType.SERVICE_REVIVED,
            System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Diario del dispositivo",
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium
                    .copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (revividos > 0)
                    "El servicio tuvo que revivirse $revividos vez/veces en 24 h"
                else
                    "El servicio no ha necesitado revivirse en 24 h",
                color = if (revividos > 0) AccentOrange else TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (lineas.isEmpty()) {
                Text(
                    "Sin eventos registrados todavía.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            val formato = remember {
                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
            }
            lineas.forEach { evento ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        formato.format(Date(evento.timestampMs)),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            evento.type.name,
                            color = when (evento.type) {
                                DeviceEventType.SERVICE_REVIVED,
                                DeviceEventType.FL_FAILED -> AccentRed
                                DeviceEventType.SERVICE_STOPPED,
                                DeviceEventType.GATE_REJECTED -> AccentOrange
                                else -> AccentGreen
                            },
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontWeight = FontWeight.Bold)
                        )
                        if (evento.detail.isNotBlank()) {
                            Text(
                                evento.detail,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { expandido = !expandido }) {
                Text(if (expandido) "Ver menos" else "Ver más", color = AccentCyan)
            }
        }
    }
}
