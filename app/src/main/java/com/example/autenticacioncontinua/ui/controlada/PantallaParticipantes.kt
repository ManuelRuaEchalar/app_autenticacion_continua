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
import androidx.compose.material3.AlertDialog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onAlta: (String) -> Unit,
    onLimpiarMensajes: () -> Unit,
    onContinuar: () -> Unit,
    onPedirBorrado: (Participante) -> Unit,
    onCancelarBorrado: () -> Unit,
    onConfirmarBorrado: () -> Unit
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
                onAceptar = { s ->
                    onAlta(s)
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

                    // Borrar vive AQUÍ, dentro de la ficha del seleccionado, y
                    // no como icono en cada fila de la lista: así hay que elegir
                    // a alguien antes de poder borrarlo, y no se puede destruir
                    // a un participante rozando la lista al desplazarla.
                    Spacer(Modifier.height(Tema.espaciado.medio))
                    BotonSecundario(
                        "Borrar participante",
                        onClick = {
                            estado.seleccionado?.let(onPedirBorrado)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(Tema.espaciado.seccion))
    }

    estado.borrando?.let { p ->
        DialogoDeBorrado(
            seudonimo = p.seudonimo,
            sesiones = estado.sesionesQueSeBorrarian,
            onCancelar = onCancelarBorrado,
            onConfirmar = onConfirmarBorrado
        )
    }
}

/**
 * Confirmación de borrado.
 *
 * DICE LO QUE SE PIERDE, con el número delante. Borrar arrastra en cascada las
 * sesiones, los bloques y los eventos de tecleo del participante, y un diálogo
 * que sólo pregunta «¿seguro?» se contesta que sí sin leerlo.
 *
 * Y recuerda la alternativa: una sesión que salió mal se INVALIDA con su motivo
 * y se conserva. Borrar es para deshacer un alta equivocada, no para limpiar lo
 * que no gustó.
 */
@Composable
private fun DialogoDeBorrado(
    seudonimo: String,
    sesiones: Int,
    onCancelar: () -> Unit,
    onConfirmar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        containerColor = Tema.colores.superficie,
        titleContentColor = Tema.colores.textoPrimario,
        textContentColor = Tema.colores.textoSecundario,
        title = { Text("Borrar $seudonimo") },
        text = {
            Text(
                if (sesiones == 0) {
                    "No tiene ninguna sesion registrada. Se borrara solo el alta."
                } else {
                    "Se borraran tambien sus $sesiones sesiones, con sus bloques y " +
                        "todas sus pulsaciones. No se puede deshacer.\n\n" +
                        "Si lo que salio mal es una sesion, invalidala en vez de " +
                        "borrar al participante: una sesion invalidada se conserva " +
                        "con su motivo."
                },
                fontSize = Tipos.cuerpo
            )
        },
        confirmButton = { BotonPrimario("Borrar", onClick = onConfirmar) },
        dismissButton = { BotonSecundario("Cancelar", onClick = onCancelar) }
    )
}

/**
 * Lo que se enseña bajo el seudónimo en la lista.
 *
 * Antes eran las covariables (edad, sexo, lateralidad); ya no existen. Queda la
 * fecha de alta, que no es un dato de la persona sino del registro y sirve para
 * distinguir dos seudónimos parecidos y para casarlos con el cuaderno de campo.
 */
private fun descripcion(p: Participante): String =
    if (p.fechaAltaMs <= 0L) "sin fecha de alta"
    else "alta " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(p.fechaAltaMs))

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
 * Alta de participante: SÓLO el seudónimo.
 *
 * No se pide edad, sexo, lateralidad ni competencia en latín (decisión del
 * 30/08). No es que no se enseñen: no existen en la base. Con 20-30
 * participantes esos campos juntos reidentifican a casi cualquiera, y el
 * teléfono se presta a desconocidos; la única forma de que un dato no se filtre
 * es que no esté. Lo que haga falta para describir la muestra va al cuaderno de
 * campo en papel, junto a la correspondencia persona ↔ seudónimo.
 */
@Composable
private fun FormularioDeAlta(
    onCancelar: () -> Unit,
    onAceptar: (String) -> Unit
) {
    var seudonimo by remember { mutableStateOf("") }

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

            Spacer(Modifier.height(Tema.espaciado.pequeno))
            Text(
                "No se guarda ningun otro dato de la persona.",
                fontSize = Tipos.menor,
                color = Tema.colores.textoTerciario
            )

            Spacer(Modifier.height(Tema.espaciado.medio))
            Row {
                BotonPrimario(
                    "Dar de alta",
                    onClick = { onAceptar(seudonimo) },
                    habilitado = seudonimo.isNotBlank()
                )
                Spacer(Modifier.width(Tema.espaciado.pequeno))
                BotonSecundario("Cancelar", onClick = onCancelar)
            }
        }
    }
}

// `SelectorDeOpcion` se eliminó con las covariables (30/08): era el selector en
// fila de edad, sexo, lateralidad y latín, y ya no hay nada que seleccionar en el
// alta. Si vuelve a hacer falta un selector de opciones, va a ui/componentes.
