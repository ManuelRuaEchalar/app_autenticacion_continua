package com.example.autenticacioncontinua

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.autenticacioncontinua.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * LAS MIGRACIONES 7->8, 8->9 Y 9->10 SOBRE UNA COPIA REAL DE LA BASE DE CAMPO.
 *
 * Es la prueba que ninguna otra sustituye. `EsquemaDeMigracionTest` comprueba
 * en la JVM que el SQL coincide con lo que Room espera; esta comprueba lo otro:
 * que los DATOS QUE YA EXISTEN sobreviven. Las tablas de la recogida ambiental
 * tienen del orden del millon de filas y son irrecuperables —esas personas no
 * van a volver a usar el telefono un mes entero—, asi que la migracion se
 * ensaya sobre una copia antes de tocar el terminal de nadie.
 *
 * COMO SE PREPARA. Con el terminal conectado, ANTES de instalar la version
 * nueva de la app:
 *
 *   adb -s <serie> shell "run-as com.example.autenticacioncontinua cp \
 *       databases/continuous_auth_db files/campo_v7.db"
 *
 * LA COPIA VA AL `filesDir` DE LA APP Y NO A /data/local/tmp. Es cuestion de
 * SELinux: un proceso de aplicacion (dominio `untrusted_app`) no puede leer
 * ficheros etiquetados como `shell_data_file`, que es lo que deja ahi un
 * `adb push`. La prueba corre DENTRO del proceso de la app, asi que el unico
 * sitio del que puede leer con garantias es el suyo propio. Verificado en el
 * Redmi ec56958 el 24/08.
 *
 * Y ojo con el orden: la copia hay que hacerla ANTES de que la version nueva
 * abra la base, porque en cuanto la abra ya estara migrada y el "antes" de la
 * prueba dejaria de ser un antes.
 *
 * Y despues:
 *
 *   adb -s <serie> shell am instrument -w \
 *     -e class com.example.autenticacioncontinua.MigracionCampoTest \
 *     com.example.autenticacioncontinua.test/androidx.test.runner.AndroidJUnitRunner
 *
 * SIN LA COPIA, LA PRUEBA SE SALTA en vez de fallar: fallar por no tener el
 * fichero convertiria en rojo una suite que no tiene nada roto, y a la tercera
 * vez nadie la mira. Lo que NO hace es pasar en silencio: el `assumeTrue` deja
 * el motivo escrito en el informe.
 */
@RunWith(AndroidJUnit4::class)
class MigracionCampoTest {

    @Test
    fun laMigracionConservaTodasLasFilasDeLaRecoleccionAmbiental() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val origen = File(ctx.filesDir, NOMBRE_COPIA)
        assumeTrue(
            "no hay copia de campo en ${origen.path}; ver las instrucciones de la clase",
            origen.exists() && origen.length() > 0
        )
        val destino = ctx.getDatabasePath(NOMBRE_ENSAYO)
        destino.parentFile?.mkdirs()
        listOf(destino, File("${destino.path}-wal"), File("${destino.path}-shm"))
            .forEach { it.delete() }
        origen.copyTo(destino, overwrite = true)

        // 1. Contar ANTES, con SQLite pelado: si se abriera con Room ya se
        //    habria migrado y el "antes" no seria un antes.
        val antes = contarSinRoom(destino)
        Log.i(TAG, "antes de migrar: $antes")
        assertTrue(
            "la copia no parece una base de campo: no hay filas de sensores",
            (antes["accelerometer_data"] ?: 0L) > 0L
        )

        // 2. Abrir con Room, que aplica las migraciones y valida el esquema.
        //    Si alguna sentencia no coincide con lo que Room espera, esta
        //    llamada lanza y la prueba falla aqui — que es exactamente el fallo
        //    que se quiere descubrir en el banco y no en casa del participante.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, NOMBRE_ENSAYO)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12
            )
            .build()
        db.openHelper.writableDatabase        // fuerza la migracion
        db.close()

        // 3. Contar DESPUES.
        val despues = contarSinRoom(destino)
        Log.i(TAG, "despues de migrar: $despues")

        for (tabla in INTOCABLES) {
            assertEquals(
                "la migracion cambio el numero de filas de `$tabla`",
                antes[tabla], despues[tabla]
            )
        }

        // 4. Y las tablas nuevas existen y estan vacias.
        for (tabla in NUEVAS) {
            assertEquals("`$tabla` deberia existir y estar vacia", 0L, despues[tabla])
        }

        // Se borran TAMBIEN el -wal y el -shm. Borrar solo el .db dejaba los
        // dos companeros huerfanos en databases/ del terminal del participante
        // (visto el 24/08), donde no molestan pero confunden a quien mire.
        listOf(destino, File("${destino.path}-wal"), File("${destino.path}-shm"))
            .forEach { it.delete() }
    }

    /**
     * Cuenta filas con SQLite directamente. Una tabla que no existe devuelve
     * `null`, que es distinto de cero y hay que poder distinguirlo: cero filas
     * en una tabla que existe es lo esperado tras crearla; ausente significa
     * que la migracion no la creo.
     */
    private fun contarSinRoom(fichero: File): Map<String, Long?> {
        val db = SQLiteDatabase.openDatabase(
            fichero.path, null, SQLiteDatabase.OPEN_READONLY
        )
        return try {
            (INTOCABLES + NUEVAS).associateWith { tabla ->
                try {
                    db.rawQuery("SELECT COUNT(*) FROM `$tabla`", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TAG = "MigracionCampo"
        /** Dentro de `filesDir` de la app: ver la nota de SELinux en la clase. */
        const val NOMBRE_COPIA = "campo_v7.db"
        const val NOMBRE_ENSAYO = "ensayo_migracion_db"

        /** No se pueden tocar: son el trabajo de campo del 16-17/08. */
        val INTOCABLES = listOf(
            "accelerometer_data", "gyroscope_data", "labeled_sessions",
            "daily_session_stats", "resource_measurements", "training_runs",
            "device_events"
        )

        val NUEVAS = listOf(
            "mediciones_recursos", "mediciones_latencia",
            "participantes", "sesiones_controladas", "bloques",
            "muestras_inerciales", "eventos_tecleo", "covariables_sesion"
        )
    }
}
