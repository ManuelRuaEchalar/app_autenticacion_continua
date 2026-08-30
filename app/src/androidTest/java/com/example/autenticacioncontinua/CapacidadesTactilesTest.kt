package com.example.autenticacioncontinua

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QUE DECLARA LA PANTALLA TACTIL DE ESTE TERMINAL.
 *
 * La fase 3 pide comprobar si presion y area se leen de verdad, porque hay
 * terminales que devuelven una constante y guardar esa constante como si fuera
 * una medida haria creer al analisis que hay una variable donde solo hay una
 * decision del fabricante.
 *
 * LO QUE ESTA PRUEBA PUEDE Y NO PUEDE HACER. Una prueba instrumentada no puede
 * tocar la pantalla: no hay dedo. Lo que si puede es preguntarle al sistema QUE
 * EJES DECLARA el dispositivo de entrada y con que rango y resolucion
 * (`InputDevice.getMotionRange`). Eso es evidencia indirecta pero solida:
 *
 *   - si un eje NO esta declarado, el terminal no lo mide y punto;
 *   - si esta declarado con rango [0, 1] y resolucion 0, es el caso tipico del
 *     valor normalizado que muchos controladores rellenan con una constante;
 *   - si trae un rango fisico con resolucion, casi seguro que mide.
 *
 * EL VEREDICTO DEFINITIVO LO DA `DetectorDeConstante` EN LA PRIMERA SESION DE
 * ENSAYO, con dedos de verdad: acumula 30 pulsaciones y dice si el canal varia.
 * Esta prueba adelanta el diagnostico y deja el dato anotado antes de que haya
 * un solo participante.
 *
 *   adb -s <serie> shell am instrument -w \
 *     -e class com.example.autenticacioncontinua.CapacidadesTactilesTest \
 *     com.example.autenticacioncontinua.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class CapacidadesTactilesTest {

    @Test
    fun queEjesDeclaraLaPantallaTactil() {
        // `getDeviceIds()` devuelve un IntArray, que no tiene `mapNotNull`.
        val pantallas = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { it.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) }

        Log.i(TAG, "=== DISPOSITIVOS TACTILES ===")
        assertTrue("el terminal no declara ninguna pantalla tactil", pantallas.isNotEmpty())

        for (d in pantallas) {
            Log.i(TAG, "  ${d.name}  (id ${d.id})")
            for ((nombre, eje) in EJES) {
                val r = d.getMotionRange(eje, InputDevice.SOURCE_TOUCHSCREEN)
                if (r == null) {
                    Log.w(TAG, "    $nombre: NO DECLARADO -> este terminal no lo mide")
                    continue
                }
                Log.i(
                    TAG,
                    "    %-12s min=%.4f max=%.4f res=%.6f fuzz=%.6f  %s".format(
                        nombre, r.min, r.max, r.resolution, r.fuzz, veredicto(nombre, r)
                    )
                )
            }
        }

        Log.i(TAG, "")
        Log.i(TAG, "  RECORDATORIO: esto es lo que el terminal DECLARA. Lo que")
        Log.i(TAG, "  entrega de verdad lo dira `DetectorDeConstante` en la primera")
        Log.i(TAG, "  sesion de ensayo, con 30 pulsaciones reales.")
    }

    /**
     * Un rango normalizado [0, 1] con resolucion 0 es la firma del controlador
     * que rellena el campo sin medirlo. No es prueba concluyente —hay
     * controladores que normalizan una medida real— pero es la senal que hay
     * que mirar.
     */
    private fun veredicto(nombre: String, r: InputDevice.MotionRange): String = when {
        r.max <= 0f -> "SOSPECHOSO: rango vacio"
        r.min == 0f && r.max == 1f && r.resolution == 0f ->
            "SOSPECHOSO: normalizado 0..1 sin resolucion; puede ser constante"
        r.resolution > 0f -> "declara resolucion: probablemente mide"
        else -> "rango fisico sin resolucion declarada"
    }

    private companion object {
        const val TAG = "CapacidadesTactiles"

        /**
         * `AXIS_SIZE` va junto a `AXIS_TOUCH_MAJOR` porque algunos terminales
         * rellenan uno y no el otro, y el area de contacto sirve igual desde
         * cualquiera de los dos.
         */
        val EJES = listOf(
            "presion" to MotionEvent.AXIS_PRESSURE,
            "touchMajor" to MotionEvent.AXIS_TOUCH_MAJOR,
            "touchMinor" to MotionEvent.AXIS_TOUCH_MINOR,
            "size" to MotionEvent.AXIS_SIZE,
            "orientacion" to MotionEvent.AXIS_ORIENTATION
        )
    }
}
