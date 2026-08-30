package com.example.autenticacioncontinua.data

import com.example.autenticacioncontinua.data.local.AppDatabase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * EJECUTA las migraciones sobre SQLite y compara el esquema resultante con el
 * que Room exporta.
 *
 * POR QUÉ ESTA PRUEBA EXISTE. Una migración cuyo esquema no coincida
 * EXACTAMENTE con lo que Room espera —una columna de menos, un tipo distinto,
 * un `NOT NULL` que sobra— no falla al compilar ni al ejecutar los tests: falla
 * al ABRIR la base, con `IllegalStateException`, en el teléfono del
 * participante y sobre los únicos datos de campo que existen. Hasta el 23/08 la
 * única forma de detectarlo era instalar y arrancar.
 *
 * POR QUÉ EJECUTA EL SQL EN VEZ DE COMPARAR CADENAS. La primera versión
 * comparaba el `CREATE TABLE` de cada migración con el `createSql` del esquema
 * exportado, carácter a carácter. Dejó de servir en cuanto la 9->10 añadió
 * columnas con `ALTER TABLE ADD COLUMN`: desde entonces el `CREATE` de la 7->8
 * ya NO coincide con el esquema final, y no debe coincidir. Lo que hay que
 * comparar es el resultado de aplicar todas las migraciones en orden, que es
 * justo lo que hará el teléfono.
 *
 * NO SUSTITUYE a `MigracionCampoTest` —esa comprueba, en un terminal real, que
 * los datos que ya existen sobreviven— pero detecta sin dispositivo la clase de
 * error que más caro sale.
 */
class EsquemaDeMigracionTest {

    private lateinit var esquema: JsonObject

    @Before
    fun cargarEsquema() {
        val f = File(RUTA)
        assertTrue(
            "no se encuentra $RUTA. Lo genera KSP al compilar (room.schemaLocation); " +
                "ejecuta `gradlew :app:kspDebugKotlin` antes.",
            f.exists()
        )
        esquema = JsonParser.parseString(f.readText()).asJsonObject.getAsJsonObject("database")
    }

    @Test
    fun `la version del esquema exportado es la que declara la base`() {
        assertEquals(VERSION, esquema.get("version").asInt)
    }

    /**
     * El corazón de la prueba: aplicar 7->8, 8->9 y 9->10 sobre una base vacía
     * y comprobar que cada tabla nueva queda con EXACTAMENTE las columnas que
     * Room declara, con su tipo y su nulabilidad.
     */
    @Test
    fun `las migraciones producen el esquema que Room espera`() {
        conectar().use { con ->
            aplicarTodas(con)
            for (tabla in TABLAS_NUEVAS) {
                val esperadas = columnasSegunRoom(tabla)
                val reales = columnasReales(con, tabla)
                assertEquals(
                    "las columnas de `$tabla` no coinciden con el esquema de Room",
                    esperadas, reales
                )
            }
        }
    }

    @Test
    fun `las migraciones crean los indices que Room declara`() {
        conectar().use { con ->
            aplicarTodas(con)
            val creados = con.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index'").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
                }
            }
            for (tabla in TABLAS_NUEVAS) {
                val indices = entidad(tabla).getAsJsonArray("indices") ?: continue
                for (elemento in indices) {
                    val nombre = elemento.asJsonObject.get("name").asString
                    assertTrue("falta el indice `$nombre`", nombre in creados)
                }
            }
        }
    }

    /** El SQL tiene que ser SQL válido; un paréntesis de menos se ve aquí. */
    @Test
    fun `todas las sentencias de migracion se ejecutan sin error`() {
        conectar().use { con -> aplicarTodas(con) }
    }

    /**
     * Las tablas de la recogida ambiental no aparecen en NINGUNA migración
     * nueva.
     *
     * Es la restricción que fijó el usuario el 23/08 y la que protege el
     * trabajo de campo: tocar `accelerometer_data` obligaría a SQLite a
     * reescribir 1.3 millones de filas durante el arranque de la app. Esta
     * prueba la convierte en algo que no se puede violar por descuido.
     */
    @Test
    fun `ninguna migracion nueva toca las tablas de la recoleccion ambiental`() {
        val intocables = listOf("accelerometer_data", "gyroscope_data", "labeled_sessions")
        for (sql in TODAS) {
            for (tabla in intocables) {
                assertTrue("una migracion nueva menciona `$tabla`:\n$sql", !sql.contains(tabla))
            }
        }
    }

    /**
     * Sólo se permite CREATE y `ALTER TABLE ... ADD COLUMN`.
     *
     * `ADD COLUMN` es la única forma de `ALTER` que SQLite resuelve tocando la
     * cabecera de la tabla en vez de reescribirla entera, y aquí sólo se usa
     * sobre `mediciones_recursos`, que se creó vacía minutos antes. Cualquier
     * otro `ALTER`, `DROP`, `DELETE` o `UPDATE` en una migración de este
     * proyecto es un error: significaría reescribir o perder datos de campo.
     */
    @Test
    fun `las migraciones nuevas solo crean o anaden columnas`() {
        for (sql in TODAS) {
            val normal = sql.replace(Regex("\\s+"), " ").trim().uppercase()
            val permitida = normal.startsWith("CREATE ") ||
                (normal.startsWith("ALTER TABLE ") && " ADD COLUMN " in normal)
            assertTrue("sentencia no aditiva:\n$sql", permitida)
        }
    }

    /** Y ningún ALTER puede caer sobre una tabla que no sea de las nuevas. */
    @Test
    fun `los ALTER solo caen sobre tablas creadas por estas mismas migraciones`() {
        val alteradas = TODAS
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.uppercase().startsWith("ALTER TABLE") }
            .map { it.substringAfter("`").substringBefore("`") }
        for (t in alteradas) {
            assertTrue("se altera `$t`, que no la crean estas migraciones", t in TABLAS_NUEVAS)
        }
    }

    // ------------------------------------------------------------------

    private fun conectar(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun aplicarTodas(con: Connection) {
        con.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (sql in TODAS) st.execute(sql)
        }
    }

    /**
     * Nombre -> "TIPO NOT NULL" tal y como lo declara el esquema de Room.
     *
     * `notNull` se lee con `?:` porque Room OMITE el campo cuando vale false, y
     * pedirlo a secas devuelve null y revienta.
     */
    private fun columnasSegunRoom(tabla: String): Map<String, String> =
        entidad(tabla).getAsJsonArray("fields").associate {
            val campo = it.asJsonObject
            val noNulo = campo.get("notNull")?.asBoolean ?: false
            campo.get("columnName").asString to
                campo.get("affinity").asString + if (noNulo) " NOT NULL" else ""
        }

    /** Lo mismo, leído de la base ya migrada. */
    private fun columnasReales(con: Connection, tabla: String): Map<String, String> =
        con.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$tabla`)").use { rs ->
                buildMap {
                    while (rs.next()) {
                        // La clave primaria de Room es NOT NULL aunque SQLite
                        // marque notnull=0 en un `INTEGER PRIMARY KEY`, que es
                        // alias de rowid y nunca puede ser nulo.
                        val esPk = rs.getInt("pk") > 0
                        val noNulo = rs.getInt("notnull") == 1 || esPk
                        put(
                            rs.getString("name"),
                            rs.getString("type") + if (noNulo) " NOT NULL" else ""
                        )
                    }
                }
            }
        }

    private fun entidad(tabla: String): JsonObject =
        esquema.getAsJsonArray("entities")
            .map { it.asJsonObject }
            .firstOrNull { it.get("tableName").asString == tabla }
            ?: throw AssertionError("la tabla `$tabla` no esta en el esquema exportado")

    private companion object {
        const val VERSION = 10
        const val RUTA =
            "schemas/com.example.autenticacioncontinua.data.local.AppDatabase/10.json"

        val TODAS: List<String> =
            AppDatabase.SQL_7_8 + AppDatabase.SQL_8_9 + AppDatabase.SQL_9_10

        val TABLAS_NUEVAS = listOf(
            "mediciones_recursos", "mediciones_latencia",
            "participantes", "sesiones_controladas", "bloques",
            "muestras_inerciales", "eventos_tecleo", "covariables_sesion"
        )
    }
}
