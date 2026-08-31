package com.example.autenticacioncontinua.ui.controlada

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.autenticacioncontinua.data.controlada.IdentidadDelDispositivo
import com.example.autenticacioncontinua.domain.juego.Comprobacion
import com.example.autenticacioncontinua.presentation.controlada.EstadoParticipantes
import com.example.autenticacioncontinua.ui.componentes.AreaPrincipal
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.Separador
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Pantalla P3: lo que hay que comprobar antes de empezar una visita.
 *
 * NO DEJA EMPEZAR SIN LA LISTA, y ésa es toda su razón de ser. De la primera
 * tanda de agosto salieron dos teléfonos con CERO datos por saltarse pasos de
 * configuración, y no se detectó hasta días después porque nada avisaba. Una
 * lista que se recuerda de memoria se cumple el primer día y se olvida el
 * quinto.
 *
 * Las comprobaciones AUTOMÁTICAS —batería, etiqueta del terminal, participante
 * seleccionado— no se pueden marcar a mano: si el programa mide que la batería
 * está al 30%, que alguien diga que no lo está no la sube. Se enseñan con lo
 * que se midió, para poder discutirlo en vez de sólo obedecerlo.
 */
@Composable
fun PantallaVerificacion(
    estado: EstadoParticipantes,
    onAlternar: (String) -> Unit,
    onAsignarEtiqueta: (String) -> Unit,
    onEmpezar: () -> Unit,
    onVolver: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tema.colores.fondo)
            .verticalScroll(rememberScrollState())
    ) {
        AreaPrincipal {
            TituloDeSeccion("Antes de empezar")

            estado.plan?.let { plan ->
                Text(
                    "Visita ${plan.visita} de ${plan.seudonimo} · terminal ${plan.dispositivoReal}",
                    fontSize = Tipos.cuerpo,
                    color = Tema.colores.textoSecundario
                )
            }

            Spacer(Modifier.height(Tema.espaciado.medio))

            for (c in estado.verificacion) {
                FilaDeComprobacion(
                    comprobacion = c,
                    marcada = c.automatica && c.cumplida || c.clave in estado.marcadas,
                    onAlternar = { onAlternar(c.clave) }
                )
                Separador()
            }

            // Asignar la etiqueta aquí y no en un ajuste aparte: es la única
            // comprobación automática que el investigador PUEDE resolver en el
            // momento, y mandarle a otra pantalla a mitad de la lista es la
            // clase de fricción que hace que la lista se salte.
            if (estado.etiquetaDispositivo == IdentidadDelDispositivo.SIN_ASIGNAR) {
                Spacer(Modifier.height(Tema.espaciado.medio))
                Text(
                    "¿Cuál de los dos terminales es éste? Se fija una vez y no se " +
                        "vuelve a cambiar.",
                    fontSize = Tipos.menor,
                    color = Tema.colores.textoSecundario
                )
                Spacer(Modifier.height(Tema.espaciado.pequeno))
                Row {
                    for (e in IdentidadDelDispositivo.ETIQUETAS_VALIDAS) {
                        BotonSecundario("Terminal $e", onClick = { onAsignarEtiqueta(e) })
                        Spacer(Modifier.width(Tema.espaciado.pequeno))
                    }
                }
            }

            Spacer(Modifier.height(Tema.espaciado.grande))

            if (!estado.puedeEmpezarLaVisita) {
                Text(
                    "Falta: " + estado.pendientesDeVerificacion.joinToString(", ") { it.texto },
                    fontSize = Tipos.menor,
                    color = Tema.colores.textoTerciario
                )
                Spacer(Modifier.height(Tema.espaciado.pequeno))
            }

            BotonPrimario(
                "Empezar la visita",
                onClick = onEmpezar,
                modifier = Modifier.fillMaxWidth(),
                habilitado = estado.puedeEmpezarLaVisita
            )
            Spacer(Modifier.height(Tema.espaciado.pequeno))
            BotonSecundario("Volver", onClick = onVolver)
            Spacer(Modifier.height(Tema.espaciado.seccion))
        }
    }
}

@Composable
private fun FilaDeComprobacion(
    comprobacion: Comprobacion,
    marcada: Boolean,
    onAlternar: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Las automáticas no son pulsables: no hay nada que el investigador
            // pueda decidir sobre ellas.
            .then(if (comprobacion.automatica) Modifier else Modifier.clickable(onClick = onAlternar))
            .padding(vertical = Tema.espaciado.pequeno),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Casilla(marcada = marcada, automatica = comprobacion.automatica)
        Spacer(Modifier.width(Tema.espaciado.medio))
        Column(Modifier.weight(1f)) {
            Text(
                comprobacion.texto,
                fontSize = Tipos.cuerpo,
                color = Tema.colores.textoPrimario
            )
            val apoyo = when {
                comprobacion.detalle.isNotEmpty() -> comprobacion.detalle
                comprobacion.automatica -> "comprobado por la aplicacion"
                else -> null
            }
            if (apoyo != null) {
                Text(
                    apoyo,
                    fontSize = Tipos.menor,
                    color = if (comprobacion.automatica && !comprobacion.cumplida)
                        Tema.colores.error else Tema.colores.textoTerciario
                )
            }
        }
    }
}

/**
 * Casilla propia en vez de un `Checkbox` de Material.
 *
 * El `Checkbox` no distingue «marcado por el investigador» de «comprobado por la
 * aplicación», y ésa es justo la distinción que esta pantalla tiene que dejar
 * clara: una es una afirmación de una persona y la otra una medida.
 */
@Composable
private fun Casilla(marcada: Boolean, automatica: Boolean) {
    Box(
        Modifier
            .size(Tema.espaciado.grande)
            .background(
                when {
                    marcada && automatica -> Tema.colores.acento
                    marcada -> Tema.colores.botonPrimario
                    else -> Tema.colores.hover
                },
                RoundedCornerShape(Tema.formas.radioPequeno)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (marcada) {
            Text(
                "✓",
                fontSize = Tipos.menor,
                fontWeight = FontWeight.Bold,
                color = Tema.colores.textoSobreBotonPrimario
            )
        }
    }
}
