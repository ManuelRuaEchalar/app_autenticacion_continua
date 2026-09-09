package com.example.autenticacioncontinua

import androidx.compose.foundation.background
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.autenticacioncontinua.domain.session.CollectionPolicy
import com.example.autenticacioncontinua.domain.session.LabeledCaptureFase
import com.example.autenticacioncontinua.presentation.LabeledCaptureViewModel
import com.example.autenticacioncontinua.presentation.ResumenParticipante
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.CabeceraDeEstado
import com.example.autenticacioncontinua.ui.componentes.EstadoVisual
import com.example.autenticacioncontinua.ui.componentes.FilaDeLista
import com.example.autenticacioncontinua.ui.componentes.Tarjeta
import com.example.autenticacioncontinua.ui.componentes.TarjetaDeAviso
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos
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
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Captura etiquetada",
                        fontSize = Tipos.titulo,
                        fontWeight = FontWeight.SemiBold,
                        color = Tema.colores.textoPrimario
                    )
                    Spacer(Modifier.height(Tema.espaciado.minimo))
                    Text(
                        "${CollectionPolicy.LABELED_BOUT_MINUTES} min por ráfaga · " +
                            "${CollectionPolicy.acclimationSeconds} s de aclimatación",
                        fontSize = Tipos.menor,
                        color = Tema.colores.textoTerciario
                    )
                }
                BotonSecundario(
                    texto = "Cerrar",
                    onClick = onClose,
                    habilitado = !state.enCurso
                )
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
                item { TarjetaDeAviso(it.motivo, EstadoVisual.ERROR) }
            }
            (state.fase as? LabeledCaptureFase.Terminada)?.let {
                item {
                    TarjetaDeAviso(
                        "Ráfaga guardada (${it.duracionMs / 1000} s).",
                        EstadoVisual.EXITO
                    )
                }
            }
        }

        item { InstruccionesCard() }

        if (state.resumen.isNotEmpty()) {
            item { TituloDeSeccion("Capturado hasta ahora") }
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
    Tarjeta(Modifier.fillMaxWidth()) {
        Column {
            OutlinedTextField(
                value = participantId,
                onValueChange = onParticipantId,
                label = { Text("Seudónimo del participante") },
                placeholder = { Text("P1, P2…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Tema.espaciado.minimo))
            Text(
                "Reutiliza el MISMO seudónimo para las ráfagas de la misma persona, " +
                    "aunque se graben en momentos distintos: es lo que permite después " +
                    "entrenar contra unos impostores y medir contra otros.",
                fontSize = Tipos.menor,
                color = Tema.colores.textoSecundario,
                lineHeight = Tipos.menor * 1.5f
            )

            Spacer(Modifier.height(Tema.espaciado.medio))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = isOwner,
                    onCheckedChange = onIsOwner,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Tema.colores.acento,
                        uncheckedColor = Tema.colores.iconoSutil,
                        checkmarkColor = Tema.colores.textoSobreBotonPrimario
                    )
                )
                Column(Modifier.padding(top = Tema.espaciado.pequeno)) {
                    Text(
                        "Ráfaga de control del dueño",
                        color = Tema.colores.textoPrimario,
                        fontSize = Tipos.cuerpo
                    )
                    Text(
                        "Marcar sólo si graba el dueño del teléfono. Estas ráfagas SÍ " +
                            "entrenan su modelo; las de impostor quedan excluidas.",
                        color = Tema.colores.textoSecundario,
                        fontSize = Tipos.menor,
                        lineHeight = Tipos.menor * 1.5f
                    )
                }
            }

            Spacer(Modifier.height(Tema.espaciado.medio))
            OutlinedTextField(
                value = note,
                onValueChange = onNote,
                label = { Text("Nota (postura, mano, tarea)") },
                placeholder = { Text("sentado · mano derecha · scroll") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Tema.espaciado.medio))
            BotonPrimario(
                texto = "Empezar captura",
                onClick = onCapturar,
                modifier = Modifier.fillMaxWidth(),
                habilitado = participantId.isNotBlank()
            )
        }
    }
}

@Composable
private fun PanelCaptura(fase: LabeledCaptureFase?, onCancelar: () -> Unit) {
    val (titulo, detalle, estado) = when (fase) {
        is LabeledCaptureFase.Aclimatacion -> Triple(
            "Acomódate: ${fase.segundosRestantes} s",
            "Coge el teléfono y ponte cómodo. La grabación empieza sola.",
            EstadoVisual.AVISO
        )
        is LabeledCaptureFase.Grabando -> Triple(
            "Grabando · ${fase.segundosRestantes} s",
            "Usa el teléfono con normalidad. NO apagues la pantalla.",
            EstadoVisual.EXITO
        )
        is LabeledCaptureFase.Terminada -> Triple(
            "Guardada", "Ráfaga completa.", EstadoVisual.EXITO
        )
        is LabeledCaptureFase.Fallida -> Triple("Error", fase.motivo, EstadoVisual.ERROR)
        null -> Triple("Preparando…", "", EstadoVisual.NEUTRO)
    }

    val enCurso = fase is LabeledCaptureFase.Aclimatacion || fase is LabeledCaptureFase.Grabando

    Tarjeta(Modifier.fillMaxWidth()) {
        Column {
            CabeceraDeEstado(
                titulo = titulo,
                estado = estado,
                detalle = detalle.ifBlank { null },
                pulsante = enCurso
            )
            Spacer(Modifier.height(Tema.espaciado.medio))
            BotonSecundario(
                texto = "Cancelar",
                onClick = onCancelar,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InstruccionesCard() {
    Tarjeta(Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Protocolo",
                fontSize = Tipos.subtitulo,
                fontWeight = FontWeight.SemiBold,
                color = Tema.colores.textoPrimario
            )
            Spacer(Modifier.height(Tema.espaciado.pequeno))
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
                Row(Modifier.padding(bottom = Tema.espaciado.pequeno)) {
                    Text("·  ", color = Tema.colores.iconoSutil, fontSize = Tipos.menor)
                    Text(
                        it,
                        color = Tema.colores.textoSecundario,
                        fontSize = Tipos.menor,
                        lineHeight = Tipos.menor * 1.5f
                    )
                }
            }
        }
    }
}

@Composable
private fun FilaResumen(r: ResumenParticipante) {
    FilaDeLista(
        titulo = r.participantId,
        apoyo = if (r.isOwner) "control del dueño" else "impostor",
        valor = "${r.rafagas} ráfaga(s) · ${r.minutosTotales} min"
    )
}
