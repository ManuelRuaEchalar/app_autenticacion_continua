package com.example.autenticacioncontinua.ui.controlada

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.autenticacioncontinua.domain.juego.EstadoDeBloque
import com.example.autenticacioncontinua.domain.juego.FaseDeSesion
import com.example.autenticacioncontinua.domain.tecleo.PulsacionCruda
import com.example.autenticacioncontinua.presentation.controlada.EstadoJuego
import com.example.autenticacioncontinua.presentation.controlada.JuegoViewModel
import com.example.autenticacioncontinua.presentation.controlada.EstadoExportacion
import com.example.autenticacioncontinua.presentation.controlada.ResumenBloque
import com.example.autenticacioncontinua.ui.componentes.AreaPrincipal
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.CabeceraDeEstado
import com.example.autenticacioncontinua.ui.componentes.EstadoVisual
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.FilaDeLista
import com.example.autenticacioncontinua.ui.componentes.Separador
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.tecleo.TecladoEnPantalla
import com.example.autenticacioncontinua.ui.theme.EstiloCifra
import com.example.autenticacioncontinua.ui.theme.EstiloTeleprompter
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos
import kotlin.math.roundToInt

/**
 * Pantalla P4 del plan: el minijuego.
 *
 * SIN DISTRACCIONES, Y ESO INCLUYE LO QUE NO ESTÁ. No hay animaciones que
 * compitan con la tarea, ni botón de salir a mitad de bloque, ni nada que
 * parpadee. El participante va a mirarla mientras teclea, y cualquier elemento
 * que capture la atención cambia la tarea que se está midiendo.
 *
 * Sin estado propio y sin ViewModel dentro, igual que [PantallaParticipantes]:
 * recibe lo que hay que pintar y devuelve los toques. Todas las reglas viven en
 * [JuegoViewModel], que se prueba entero en la JVM.
 */
@Composable
fun PantallaJuego(
    estado: EstadoJuego,
    onPulsacion: (PulsacionCruda) -> Unit,
    onTerminar: () -> Unit,
    onReintentarExportacion: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Tema.colores.fondo)
    ) {
        when {
            estado.error != null -> Aviso(estado.error, onTerminar)
            estado.fase == null -> Aviso("Preparando la sesion...", null)
            estado.terminada -> Resumen(estado, onTerminar, onReintentarExportacion)
            else -> EnCurso(estado, onPulsacion)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// Aclimatación y bloques
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun ColumnScope.EnCurso(estado: EstadoJuego, onPulsacion: (PulsacionCruda) -> Unit) {
    val esAclimatacion = estado.fase is FaseDeSesion.Aclimatacion

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        BarraDeEstado(estado)
        Separador()

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(Tema.espaciado.medio),
            contentAlignment = Alignment.Center
        ) {
            if (esAclimatacion) {
                Aclimatacion()
            } else {
                estado.bloque?.let { Teleprompter(it) }
            }
        }
    }

    // El teclado se dibuja también en la aclimatación, y a propósito: la fase
    // existe para que el participante se familiarice con ÉL. Lo que no se hace
    // es registrar nada — de eso se encarga el ViewModel, que ignora las
    // pulsaciones fuera de un bloque.
    TecladoEnPantalla(onPulsacion = onPulsacion)
}

@Composable
private fun Aclimatacion() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Calentamiento",
            style = EstiloCifra,
            color = Tema.colores.textoPrimario
        )
        Spacer(Modifier.height(Tema.espaciado.pequeno))
        Text(
            "Teclea lo que quieras para acostumbrarte al teclado.\n" +
                "Esto no se guarda.",
            style = EstiloTeleprompter,
            color = Tema.colores.textoSecundario
        )
    }
}

/**
 * El texto que se transcribe, con el estado de cada carácter.
 *
 * CUATRO ESTADOS Y NO DOS. Lo ya escrito bien se apaga a texto secundario para
 * que la vista no vuelva atrás; lo escrito mal se queda en rojo aunque el cursor
 * haya avanzado, porque el participante puede decidir corregir o no —y que
 * corrija o no es en sí un rasgo suyo—; el carácter actual va en el acento
 * legible, con fondo, porque es lo único que hay que mirar; y lo que falta, en
 * texto primario.
 *
 * Se usa `acentoTexto` y no `acento` para el carácter actual: el acento de la
 * paleta se queda en 3.20:1 sobre blanco, que basta para un indicador pero no
 * para algo que hay que LEER. Lo detectó `ContrasteTest`.
 */
@Composable
private fun Teleprompter(bloque: EstadoDeBloque) {
    Text(
        text = textoAnotado(bloque),
        style = EstiloTeleprompter,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun textoAnotado(bloque: EstadoDeBloque): AnnotatedString {
    val colores = Tema.colores
    return buildAnnotatedString {
        bloque.texto.forEachIndexed { i, c ->
            when {
                i < bloque.posicion && i in bloque.fallados ->
                    withStyle(SpanStyle(color = colores.error, fontWeight = FontWeight.Medium)) {
                        append(c)
                    }

                i < bloque.posicion ->
                    withStyle(SpanStyle(color = colores.textoSecundario)) { append(c) }

                i == bloque.posicion ->
                    withStyle(
                        SpanStyle(
                            color = colores.acentoTexto,
                            background = colores.hover,
                            fontWeight = FontWeight.Medium
                        )
                    ) { append(c) }

                else -> withStyle(SpanStyle(color = colores.textoPrimario)) { append(c) }
            }
        }
    }
}

/** Tiempo restante, precisión y pulsaciones por minuto. Discreta y arriba. */
@Composable
private fun BarraDeEstado(estado: EstadoJuego) {
    val bloque = estado.bloque
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Tema.espaciado.medio, vertical = Tema.espaciado.pequeno),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cifra(reloj(estado.restanteMs), etiqueta(estado))
            if (bloque != null) {
                Cifra("${(bloque.precision * 100).roundToInt()}%", "precision")
                Cifra("${bloque.ppm.roundToInt()}", "ppm")
            }
        }
        LinearProgressIndicator(
            progress = { estado.fraccion },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = Tema.colores.acento,
            trackColor = Tema.colores.borde
        )
    }
}

@Composable
private fun Cifra(valor: String, etiqueta: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, style = EstiloCifra, color = Tema.colores.textoPrimario)
        Text(etiqueta, fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
    }
}

private fun etiqueta(estado: EstadoJuego): String = when (val f = estado.fase) {
    is FaseDeSesion.Bloque -> "bloque ${f.indice + 1} de 3 · ${nombreIdioma(f.idioma)}"
    is FaseDeSesion.Aclimatacion -> "calentamiento"
    else -> ""
}

private fun nombreIdioma(clave: String) = if (clave == "la") "latin" else "espanol"

private fun reloj(ms: Long): String {
    val s = (ms + 999) / 1000          // hacia arriba: el 0 aparece al acabar, no antes
    return "%d:%02d".format(s / 60, s % 60)
}

// ═════════════════════════════════════════════════════════════════════
// Resumen
// ═════════════════════════════════════════════════════════════════════

/**
 * Cierre de la visita.
 *
 * Enseña los tres bloques con sus cifras y, si alguno se interrumpió, lo dice
 * con su motivo. El investigador lo copia al cuaderno de campo: un bloque
 * interrumpido que no se anota reaparece meses después como un dato raro sin
 * explicación.
 *
 * NO SE SALE DE AQUÍ SIN HABER GUARDADO (R5, fase 9). El paquete de la visita
 * se escribe solo al llegar a esta pantalla y el botón de salir está
 * deshabilitado hasta que se ha escrito Y releído. Es deliberado que no haya un
 * botón de «exportar»: algo obligatorio que dependa de que alguien lo pulse se
 * incumple el día que hay prisa, y ese día no se distingue de los demás hasta
 * que meses después falta una visita.
 */
@Composable
private fun Resumen(
    estado: EstadoJuego,
    onTerminar: () -> Unit,
    onReintentarExportacion: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AreaPrincipal {
            TituloDeSeccion("Sesion terminada")
            Text(
                "${estado.seudonimo} · visita ${estado.visita} · ${estado.estadoFinal?.name}",
                fontSize = Tipos.cuerpo,
                color = Tema.colores.textoSecundario
            )
            Spacer(Modifier.height(Tema.espaciado.medio))

            for (b in estado.resumen) FilaDeResumen(b)

            if (estado.resumen.isEmpty()) {
                Text(
                    "No se completo ningun bloque.",
                    fontSize = Tipos.cuerpo,
                    color = Tema.colores.textoTerciario
                )
            }

            Spacer(Modifier.height(Tema.espaciado.grande))
            EstadoDelGuardado(estado.exportacion, onReintentarExportacion)

            Spacer(Modifier.height(Tema.espaciado.medio))
            BotonPrimario(
                texto = if (estado.puedeSalir) "Terminar" else "Guardando la visita…",
                onClick = onTerminar,
                modifier = Modifier.fillMaxWidth(),
                habilitado = estado.puedeSalir
            )
            Spacer(Modifier.height(Tema.espaciado.grande))
        }
    }
}

/**
 * Qué ha pasado con el paquete de la visita.
 *
 * SE ENSEÑA EL NOMBRE Y LA HUELLA, no un «guardado» a secas. Los dos van al
 * cuaderno de campo: el nombre para localizar el fichero en la copia por USB, y
 * los doce primeros dígitos del SHA-256 para poder cotejar en el PC que lo que
 * llegó es lo que salió. Un mensaje de éxito sin nada que apuntar deja al
 * investigador sin forma de comprobar la copia después.
 */
@Composable
private fun EstadoDelGuardado(estado: EstadoExportacion, onReintentar: () -> Unit) {
    when (estado) {
        is EstadoExportacion.Pendiente, is EstadoExportacion.EnCurso ->
            CabeceraDeEstado(
                titulo = "Guardando la visita",
                estado = EstadoVisual.NEUTRO,
                detalle = "Escribiendo el paquete y volviendo a leerlo para comprobarlo.",
                pulsante = true
            )

        is EstadoExportacion.Hecha -> {
            CabeceraDeEstado(
                titulo = "Visita guardada y verificada",
                estado = EstadoVisual.EXITO,
                detalle = "${estado.nombre} · ${estado.kb} KB"
            )
            Spacer(Modifier.height(Tema.espaciado.pequeno))
            Text(
                "SHA-256: ${estado.huellaCorta}…",
                fontSize = Tipos.menor,
                color = Tema.colores.textoSecundario
            )
            Text(
                estado.filas.entries.joinToString(" · ") { "${it.key.removeSuffix(".csv")} ${it.value}" },
                fontSize = Tipos.menor,
                color = Tema.colores.textoTerciario
            )
        }

        is EstadoExportacion.Fallida -> {
            CabeceraDeEstado(
                titulo = "No se pudo guardar la visita",
                estado = EstadoVisual.ERROR,
                detalle = "${estado.motivo}. Los datos siguen en la base del telefono: " +
                    "no se ha perdido nada, pero esta visita no tiene copia todavia."
            )
            Spacer(Modifier.height(Tema.espaciado.pequeno))
            BotonSecundario("Reintentar el guardado", onReintentar, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FilaDeResumen(b: ResumenBloque) {
    FilaDeLista(
        titulo = "Bloque ${b.indice + 1} · ${nombreIdioma(b.idioma)}",
        apoyo = buildString {
            append("${b.pulsaciones} pulsaciones, ${b.errores} errores, ${b.borrados} borrados")
            if (b.interrumpido) append(" · INTERRUMPIDO: ${b.motivo}")
        },
        valor = "${b.ppm.roundToInt()} ppm · ${(b.precision * 100).roundToInt()}%"
    )
}

@Composable
private fun Aviso(texto: String, onTerminar: (() -> Unit)?) {
    AreaPrincipal {
        Spacer(Modifier.height(Tema.espaciado.seccion))
        Text(texto, fontSize = Tipos.cuerpo, color = Tema.colores.textoSecundario)
        if (onTerminar != null) {
            Spacer(Modifier.height(Tema.espaciado.medio))
            BotonPrimario("Volver", onTerminar)
        }
    }
}
