package com.example.autenticacioncontinua.ui.controlada

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.autenticacioncontinua.presentation.controlada.JuegoViewModel
import com.example.autenticacioncontinua.presentation.controlada.ParticipantesViewModel
import com.example.autenticacioncontinua.ui.componentes.AreaPrincipal
import com.example.autenticacioncontinua.ui.componentes.BotonSecundario
import com.example.autenticacioncontinua.ui.theme.AutenticacionContinuaTheme
import com.example.autenticacioncontinua.ui.theme.Tema
import org.koin.androidx.compose.koinViewModel

/**
 * El estudio controlado entero: participantes y minijuego.
 *
 * SIN LIBRERÍA DE NAVEGACIÓN, por el mismo motivo que en `MainActivity`: son dos
 * pantallas encadenadas y en un solo sentido. Un `NavHost` sería más andamiaje
 * que función, y traería una pila de retroceso que aquí es justo lo que NO se
 * quiere: volver atrás a mitad de una visita dejaría la sesión abierta.
 *
 * EL RETROCESO DEL SISTEMA SE CAPTURA. Durante el minijuego no hace nada: el
 * bloque está cronometrado y el participante tiene el teléfono en la mano, así
 * que un roce en el borde no puede tirar la visita. Se sale por el botón de la
 * pantalla de resumen, que es cuando la sesión ya está cerrada en la base.
 */
@Composable
fun EstudioControlado(
    onSalir: () -> Unit,
    participantesVm: ParticipantesViewModel = koinViewModel(),
    juegoVm: JuegoViewModel = koinViewModel()
) {
    val estadoP by participantesVm.estado.collectAsState()
    val estadoJ by juegoVm.estado.collectAsState()
    var jugando by remember { mutableStateOf(false) }

    AutenticacionContinuaTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Tema.colores.fondo)
        ) {
            if (jugando) {
                BackHandler(enabled = true) { /* a mitad de visita no se sale */ }
                PantallaJuego(
                    estado = estadoJ,
                    onPulsacion = juegoVm::onPulsacion,
                    onTerminar = {
                        jugando = false
                        // El recuento de sesiones del participante acaba de
                        // cambiar; sin recargar, la lista seguiría diciendo el
                        // número anterior.
                        participantesVm.cargar()
                    }
                )
            } else if (estadoP.verificando) {
                // P3. Entre elegir participante y teclear va la lista, y no se
                // puede saltar: es lo único que separa una visita bien montada
                // de una que produce datos que parecen buenos y no lo son.
                BackHandler(enabled = true) { participantesVm.cerrarVerificacion() }
                PantallaVerificacion(
                    estado = estadoP,
                    onAlternar = participantesVm::alternarComprobacion,
                    onAsignarEtiqueta = participantesVm::asignarEtiqueta,
                    onVolver = participantesVm::cerrarVerificacion,
                    onEmpezar = {
                        val p = estadoP.seleccionado
                        val plan = estadoP.plan
                        if (p != null && plan != null) {
                            participantesVm.cerrarVerificacion()
                            juegoVm.iniciar(p.id, plan.dispositivoReal)
                            jugando = true
                        }
                    }
                )
            } else {
                BackHandler(enabled = true) { onSalir() }
                PantallaParticipantes(
                    estado = estadoP,
                    onFiltrar = participantesVm::filtrar,
                    onSeleccionar = participantesVm::seleccionar,
                    onAlta = participantesVm::alta,
                    onLimpiarMensajes = participantesVm::limpiarMensajes,
                    // "Continuar" ya no arranca el minijuego: pasa por la lista
                    // de verificación. Es el único camino, para que no haya un
                    // atajo que se acabe usando por prisa.
                    onContinuar = participantesVm::abrirVerificacion,
                    onPedirBorrado = participantesVm::pedirBorrado,
                    onCancelarBorrado = participantesVm::cancelarBorrado,
                    onConfirmarBorrado = participantesVm::confirmarBorrado
                )
                AreaPrincipal {
                    Spacer(Modifier.height(Tema.espaciado.medio))
                    BotonSecundario("Volver", onSalir)
                    Spacer(Modifier.height(Tema.espaciado.grande))
                }
            }
        }
    }
}
