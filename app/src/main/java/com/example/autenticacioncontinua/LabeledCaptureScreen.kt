package com.example.autenticacioncontinua

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autenticacioncontinua.domain.session.CollectionPolicy
import com.example.autenticacioncontinua.domain.session.LabeledCaptureFase
import com.example.autenticacioncontinua.presentation.LabeledCaptureViewModel
import com.example.autenticacioncontinua.presentation.ResumenParticipante
import org.koin.androidx.compose.koinViewModel

/**
 * Modo de laboratorio: grabar una ráfaga sabiendo QUIÉN tiene el teléfono.
 *
 * Es la pantalla del experimento de impostor en el mismo dispositivo. La
 * pregunta que responde ese experimento es si el modelo distingue personas o
 * distingue teléfonos: mientras los impostores vengan de otro terminal, ambas
 * explicaciones encajan con los mismos números. Grabando al impostor en el
 * aparato de la víctima, el hardware es constante entre las dos clases y deja
 * de poder explicar nada.
 *
 * Por eso la casilla "ráfaga de control del dueño" no es un extra: sin ella la
 * comparación sería "vida ambiental del dueño contra sesión dirigida del
 * impostor", y el modelo podría separar el PROTOCOLO en vez de la persona.
 */
@Composable
fun LabeledCaptureScreen(
    onClose: () -> Unit,
    viewModel: LabeledCaptureViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🧪", fontSize = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Captura etiquetada",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                    Text(
                        "${CollectionPolicy.LABELED_BOUT_MINUTES} min por ráfaga · " +
                            "${CollectionPolicy.acclimationSeconds} s de aclimatación",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                TextButton(onClick = onClose, enabled = !state.enCurso) {
                    Text("Cerrar", color = if (state.enCurso) TextSecondary else AccentCyan)
                }
            }
        }

        if (state.enCurso) {
            item { PanelCaptura(state.fase, onCancelar = viewModel::cancelar) }
        } else {
            item {
                FormularioCaptura(
                    participantId = state.participantId,
                    isOwner = state.isOwner,
                    note = state.note,
                    onParticipantId = viewModel::setParticipantId,
                    onIsOwner = viewModel::setIsOwner,
                    onNote = viewModel::setNote,
                    onCapturar = viewModel::capturar
                )
            }
            (state.fase as? LabeledCaptureFase.Fallida)?.let {
                item { Aviso(it.motivo, AccentRed) }
            }
            (state.fase as? LabeledCaptureFase.Terminada)?.let {
                item { Aviso("Ráfaga guardada (${it.duracionMs / 1000} s).", AccentGreen) }
            }
        }

        item { InstruccionesCard() }

        if (state.resumen.isNotEmpty()) {
            item {
                Text(
                    "Capturado hasta ahora",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }
            state.resumen.forEach { r ->
                item { FilaResumen(r) }
            }
        }
    }
}

@Composable
private fun FormularioCaptura(
    participantId: String,
    isOwner: Boolean,
    note: String,
    onParticipantId: (String) -> Unit,
    onIsOwner: (Boolean) -> Unit,
    onNote: (String) -> Unit,
    onCapturar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(20.dp)) {
            OutlinedTextField(
                value = participantId,
                onValueChange = onParticipantId,
                label = { Text("Seudónimo del participante", color = TextSecondary) },
                placeholder = { Text("P1, P2…", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Reutiliza el MISMO seudónimo para las ráfagas de la misma persona, " +
                    "aunque se graben en momentos distintos: es lo que permite después " +
                    "entrenar contra unos impostores y medir contra otros.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isOwner,
                    onCheckedChange = onIsOwner,
                    colors = CheckboxDefaults.colors(checkedColor = AccentGreen)
                )
                Column {
                    Text(
                        "Ráfaga de control del dueño",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Marcar sólo si graba el dueño del teléfono. Estas ráfagas SÍ " +
                            "entrenan su modelo; las de impostor quedan excluidas.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                label = { Text("Nota (postura, mano, tarea)", color = TextSecondary) },
                placeholder = { Text("sentado · mano derecha · scroll", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCapturar,
                modifier = Modifier.fillMaxWidth(),
                enabled = participantId.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
            ) {
                Text("Empezar captura", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PanelCaptura(fase: LabeledCaptureFase?, onCancelar: () -> Unit) {
    val (titulo, detalle, color) = when (fase) {
        is LabeledCaptureFase.Aclimatacion -> Triple(
            "Acomódate: ${fase.segundosRestantes} s",
            "Coge el teléfono y ponte cómodo. La grabación empieza sola.",
            AccentOrange
        )
        is LabeledCaptureFase.Grabando -> Triple(
            "Grabando · ${fase.segundosRestantes} s",
            "Usa el teléfono con normalidad. NO apagues la pantalla.",
            AccentGreen
        )
        is LabeledCaptureFase.Terminada -> Triple(
            "Guardada ✓", "Ráfaga completa.", AccentGreen
        )
        is LabeledCaptureFase.Fallida -> Triple("Error", fase.motivo, AccentRed)
        null -> Triple("Preparando…", "", AccentCyan)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                titulo,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
            Spacer(Modifier.height(8.dp))
            Text(
                detalle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCancelar,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CardBg)
            ) {
                Text("Cancelar", color = AccentRed)
            }
        }
    }
}

@Composable
private fun InstruccionesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "Protocolo",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "Deja que la persona trastee el teléfono 2-3 min ANTES de pulsar. " +
                    "Esa familiarización no se graba, y sin ella lo que se captura es " +
                    "«alguien estrenando un móvil ajeno», no su forma de moverse.",
                "Intercala: P1, P2, dueño, P3, P4, dueño, P2, P1… Si se hacen las " +
                    "cuatro ráfagas de cada uno seguidas, la hora y la temperatura del " +
                    "teléfono quedan correlacionadas con la identidad.",
                "Varía postura y mano entre ráfagas, y anótalo en la nota.",
                "Objetivo: 4 ráfagas por impostor y 2-3 de control del dueño."
            ).forEach {
                Row(Modifier.padding(bottom = 8.dp)) {
                    Text("• ", color = TextSecondary)
                    Text(
                        it,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaResumen(r: ResumenParticipante) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    r.participantId,
                    color = if (r.isOwner) AccentGreen else TextPrimary,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    if (r.isOwner) "control del dueño" else "impostor",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "${r.rafagas} ráfaga(s) · ${r.minutosTotales} min",
                color = AccentCyan,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Aviso(texto: String, color: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Text(
            texto,
            modifier = Modifier.padding(16.dp),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp
        )
    }
}
