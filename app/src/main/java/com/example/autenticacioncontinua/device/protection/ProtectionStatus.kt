package com.example.autenticacioncontinua.device.protection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Estado de las concesiones que mantienen viva la recolección (pendiente E).
 *
 * POR QUÉ EXISTE. El 2026-08-15, con tres dispositivos en la misma red, NINGUNA
 * de las tres sesiones federadas llegó al final: un móvil cayó en la ronda 18,
 * otro en la 27, y el servicio de recolección del propio equipo de desarrollo
 * apareció muerto al empezar la tarde. Al revisarlo se vio que
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ni siquiera estaba declarado en el
 * manifiesto: la app NO PODÍA pedir la exención, había que buscarla a mano en
 * Ajustes. Por eso no la tenía nadie.
 *
 * QUÉ SE PUEDE COMPROBAR Y QUÉ NO:
 *  - La exención de batería SÍ se puede consultar
 *    ([PowerManager.isIgnoringBatteryOptimizations]).
 *  - El autostart de MIUI NO. Es una extensión propia de Xiaomi sin API
 *    pública; no hay forma honesta de saber si está concedido. Sólo se puede
 *    abrir la pantalla y pedirle al usuario que lo confirme, que es lo que
 *    hace [autostartIntent]. De ahí que [Proteccion.autostartVerificable] sea
 *    false: la UI debe decir "no se puede comprobar", no inventarse un tick
 *    verde.
 */
class ProtectionStatus(private val context: Context) {

    fun current(): Proteccion {
        val bateria = bateriaExenta()
        return Proteccion(
            bateriaExenta = bateria,
            esXiaomi = ES_XIAOMI,
            autostartVerificable = false
        )
    }

    /**
     * ¿Está la app fuera de la optimización de batería?
     *
     * En el dispositivo de desarrollo esto valía `false` el 2026-08-12
     * (`dumpsys deviceidle whitelist` vacío) y `true` el 15, así que la
     * comprobación distingue de verdad los dos estados.
     */
    fun bateriaExenta(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Diálogo del sistema para pedir la exención.
     *
     * Requiere `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` en el manifiesto. Sin
     * ese permiso el sistema lanza la pantalla general de ajustes en el mejor
     * caso, y no pasa nada en el peor.
     */
    fun solicitudBateriaIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Ajustes de la app, por si el diálogo directo no está disponible. */
    fun ajustesAppIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Pantalla de inicio automático de MIUI, si existe.
     *
     * Se prueban los componentes conocidos y se devuelve el primero que el
     * sistema sepa resolver. Devuelve `null` en dispositivos que no son
     * Xiaomi o donde la pantalla no existe: es preferible no ofrecer el botón
     * a mandar al participante a una pantalla que va a fallar.
     */
    fun autostartIntent(): Intent? {
        for ((paquete, clase) in COMPONENTES_AUTOSTART) {
            val intent = Intent().setComponent(ComponentName(paquete, clase))
            val resoluble = context.packageManager
                .queryIntentActivities(intent, 0)
                .isNotEmpty()
            if (resoluble) return intent
        }
        Log.i(TAG, "Este dispositivo no expone pantalla de autostart conocida")
        return null
    }

    private companion object {
        const val TAG = "ProtectionStatus"

        val ES_XIAOMI: Boolean
            get() = listOf(Build.MANUFACTURER, Build.BRAND)
                .any { it.equals("xiaomi", true) || it.equals("redmi", true) }

        /** Pares (paquete, actividad) de la pantalla de autostart de MIUI. */
        val COMPONENTES_AUTOSTART = listOf(
            "com.miui.securitycenter" to
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.letv.android.letvsafe" to
                    "com.letv.android.letvsafe.AutobootManageActivity",
            "com.huawei.systemmanager" to
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
    }
}

/**
 * @param autostartVerificable si el sistema permite CONSULTAR el estado del
 *   autostart. Siempre false hoy; el campo existe para que la UI no tenga que
 *   asumirlo y para que se note si algún día deja de ser cierto.
 */
data class Proteccion(
    val bateriaExenta: Boolean,
    val esXiaomi: Boolean,
    val autostartVerificable: Boolean
) {
    /**
     * "Protegido" es sólo lo que se puede AFIRMAR. En un Xiaomi nunca se
     * puede: el autostart no es consultable, así que lo honesto es no dar el
     * visto bueno y decir qué falta por confirmar.
     */
    val protegido: Boolean get() = bateriaExenta && !esXiaomi

    val resumen: String
        get() = when {
            !bateriaExenta -> "DESPROTEGIDO — el sistema puede matar la recolección"
            esXiaomi -> "Batería concedida. Falta confirmar el inicio automático"
            else -> "Protegido"
        }
}
