package com.example.autenticacioncontinua.ui.controlada

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.example.autenticacioncontinua.domain.model.controlada.Participante
import com.example.autenticacioncontinua.presentation.controlada.EstadoParticipantes
import com.example.autenticacioncontinua.presentation.controlada.ParticipantesViewModel
import com.example.autenticacioncontinua.ui.componentes.AreaPrincipal
import com.example.autenticacioncontinua.ui.componentes.BotonPrimario
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.componentes.FilaDeLista
import com.example.autenticacioncontinua.ui.componentes.Separador
import com.example.autenticacioncontinua.ui.componentes.Tarjeta
import com.example.autenticacioncontinua.ui.componentes.TituloDeSeccion
import com.example.autenticacioncontinua.ui.theme.Tema
import com.example.autenticacioncontinua.ui.theme.Tipos

/**
 * Pantalla P2 del plan: lista de participantes con buscador, alta y selección.
 *
 * No toma ninguna decisión: todo el estado y las reglas viven en
 * [ParticipantesViewModel], que se prueba en la JVM. Aquí sólo se dibuja lo que
 * el estado dice y se reenvían los toques.
 */
@Composable
fun PantallaParticipantes(
    estado: EstadoParticipantes,
    onFiltrar: (String) -> Unit,
    onSeleccionar: (Participante) -> Unit,
    onAlta: (String, String, String, String, String) -> Unit,
    onLimpiarMensajes: () -> Unit,
    onContinuar: () -> Unit
) {
    var mostrandoAlta by remember { mutableStateOf(false) }

    AreaPrincipal {
        TituloDeSeccion("Participantes")

        Buscador(estado.filtro) {
            onLimpiarMensajes()
            onFiltrar(it)
        }

        estado.aviso?.let { Aviso(it, esError = false) }
        estado.error?.let { Aviso(it, esError = true) }

        Spacer(Modifier.height(Tema.espaciado.medio))

        if (mostrandoAlta) {
            FormularioDeAlta(
                onCancelar = { mostrandoAlta = false },
                onAceptar = { s, edad, sexo, lat, latin ->
                    onAlta(s, edad, sexo, lat, latin)
                    mostrandoAlta = false
                }
            )
        } else {
            Row {
                BotonPrimario("Nuevo participante", onClick = {
                    onLimpiarMensajes()
                    mostrandoAlta = true
                })
                if (estado.puedeIniciarSesion) {
                    Spacer(Modifier.width(Tema.espaciado.pequeno))
                    BotonSecundario("Continuar", onClick = onContinuar)
                }
            }
        }

        Spacer(Modifier.height(Tema.espaciado.medio))

        if (estado.visibles.isEmpty()) {
            Text(
                if (estado.filtro.isBlank()) "Todavía no hay participantes."
                else "Ningún participante coincide con «${estado.filtro}».",
                fontSize = Tipos.cuerpo,
                color = Tema.colores.textoTerciario
            )
        } else {
            Tarjeta {
                LazyColumn {
                    items(estado.visibles, key = { it.id }) { p ->
                        FilaDeLista(
                            titulo = p.seudonimo,
                            apoyo = descripcion(p),
                            valor = "${p.sesionesHechas}/${Participante.SESIONES_OBJETIVO}",
                            seleccionada = p.id == estado.seleccionado?.id,
                            onClick = { onSeleccionar(p) }
                        )
                        Separador()
                    }
                }
            }
        }

        estado.plan?.let { plan ->
            Spacer(Modifier.height(Tema.espaciado.medio))
            Tarjeta {
                Column {
                    Text(
                        "Visita ${plan.visita} de ${plan.seudonimo}",
                        fontSize = Tipos.cuerpo,
                        fontWeight = FontWeight.Medium,
                        color = Tema.colores.textoPrimario
                    )
                    Spacer(Modifier.height(Tema.espaciado.minimo))
                    Text(
                        if (plan.dispositivoNoEsElEsperado)
                            "Le tocaba el terminal ${plan.dispositivoEsperado} y éste es " +
                                "el ${plan.dispositivoReal}. Se puede continuar; quedará " +
                                "registrado el terminal real."
                        else "Terminal ${plan.dispositivoReal}, que es el que le toca.",
                        fontSize = Tipos.menor,
                        // El aviso NO es un error: es información para decidir.
                        color = if (plan.dispositivoNoEsElEsperado) Tema.colores.acentoTexto
                        else Tema.colores.textoSecundario
                    )
                }
            }
        }

        Spacer(Modifier.height(Tema.espaciado.seccion))
    }
}

private fun descripcion(p: Participante): String =
    listOf(p.tramoEdad, p.sexo, p.lateralidad)
        .filter { it != "ns" }
        .joinToString(" · ")
        .ifBlank { "sin datos" }

@Composable
private fun Buscador(valor: String, onCambio: (String) -> Unit) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(Tema.formas.radioMedio),
        placeholder = {
            Text("Buscar por seudónimo", color = Tema.colores.iconoSutil, fontSize = Tipos.cuerpo)
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Tema.colores.superficie,
            unfocusedContainerColor = Tema.colores.superficie,
            focusedTextColor = Tema.colores.textoPrimario,
            unfocusedTextColor = Tema.colores.textoPrimario,
            focusedIndicatorColor = Tema.colores.acento,
            unfocusedIndicatorColor = Tema.colores.borde
        )
    )
}

@Composable
private fun Aviso(texto: String, esError: Boolean) {
    Text(
        texto,
        modifier = Modifier.padding(top = Tema.espaciado.pequeno),
        fontSize = Tipos.menor,
        color = if (esError) Tema.colores.error else Tema.colores.acentoTexto
    )
}

/**
 * Alta de participante.
 *
 * Las covariables se piden por TRAMOS y no exactas —edad en franjas, no en
 * años— porque con 20-30 personas una edad exacta más el sexo y la lateralidad
 * reidentifican a casi cualquiera, y el teléfono se presta a desconocidos.
 */
@Composable
private fun FormularioDeAlta(
    onCancelar: () -> Unit,
    onAceptar: (String, String, String, String, String) -> Unit
) {
    var seudonimo by remember { mutableStateOf("") }
    var edad by remember { mutableStateOf(ParticipantesViewModel.TRAMOS_EDAD.first()) }
    var sexo by remember { mutableStateOf(ParticipantesViewModel.SEXOS.first()) }
    var lateralidad by remember { mutableStateOf(ParticipantesViewModel.LATERALIDADES.first()) }
    var latin by remember { mutableStateOf(ParticipantesViewModel.COMPETENCIAS_LATIN.first()) }

    Tarjeta {
        Column {
            Text(
                "Nuevo participante",
                fontSize = Tipos.subtitulo,
                fontWeight = FontWeight.Medium,
                color = Tema.colores.textoPrimario
            )
            Spacer(Modifier.height(Tema.espaciado.medio))

            OutlinedTextField(
                value = seudonimo,
                onValueChange = { seudonimo = it },
                label = { Text("Seudónimo (p. ej. P01)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Tema.formas.radioMedio),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Tema.colores.superficie,
                    unfocusedContainerColor = Tema.colores.superficie,
                    focusedTextColor = Tema.colores.textoPrimario,
                    unfocusedTextColor = Tema.colores.textoPrimario,
                    focusedIndicatorColor = Tema.colores.acento,
                    unfocusedIndicatorColor = Tema.colores.borde
                )
            )

            SelectorDeOpcion("Edad", ParticipantesViewModel.TRAMOS_EDAD, edad) { edad = it }
            SelectorDeOpcion("Sexo", ParticipantesViewModel.SEXOS, sexo) { sexo = it }
            SelectorDeOpcion(
                "Lateralidad", ParticipantesViewModel.LATERALIDADES, lateralidad
            ) { lateralidad = it }
            SelectorDeOpcion(
                "Latín", ParticipantesViewModel.COMPETENCIAS_LATIN, latin
            ) { latin = it }

            Spacer(Modifier.height(Tema.espaciado.medio))
            Row {
                BotonPrimario(
                    "Dar de alta",
                    onClick = { onAceptar(seudonimo, edad, sexo, lateralidad, latin) },
                    habilitado = seudonimo.isNotBlank()
                )
                Spacer(Modifier.width(Tema.espaciado.pequeno))
                BotonSecundario("Cancelar", onClick = onCancelar)
            }
        }
    }
}

/**
 * Opciones en fila en vez de un desplegable.
 *
 * Son listas de tres a seis valores fijos: un desplegable añade un toque y una
 * animación por cada uno, y el alta se hace con el participante esperando.
 */
@Composable
private fun SelectorDeOpcion(
    etiqueta: String,
    opciones: List<String>,
    seleccionada: String,
    onElegir: (String) -> Unit
) {
    Column(Modifier.padding(top = Tema.espaciado.medio)) {
        Text(etiqueta, fontSize = Tipos.menor, color = Tema.colores.textoTerciario)
        Spacer(Modifier.height(Tema.espaciado.minimo))
        Row {
            for (o in opciones) {
                if (o == seleccionada) {
                    BotonPrimario(o, onClick = { onElegir(o) })
                } else {
                    BotonSecundario(o, onClick = { onElegir(o) })
                }
                Spacer(Modifier.width(Tema.espaciado.minimo))
            }
        }
    }
}
