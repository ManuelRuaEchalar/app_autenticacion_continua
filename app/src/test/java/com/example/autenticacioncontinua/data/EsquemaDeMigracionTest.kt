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
     * Sólo se permite CREATE, `ALTER TABLE ... ADD COLUMN`, y la recreación
     * acotada de [TABLA_RECREABLE].
     *
     * `ADD COLUMN` es la única forma de `ALTER` que SQLite resuelve tocando la
     * cabecera de la tabla en vez de reescribirla entera. Cualquier otro
     * `ALTER`, `DROP`, `DELETE` o `UPDATE` en una migración de este proyecto es
     * un error: significaría reescribir o perder datos de campo — salvo sobre
     * `participantes`, por el motivo escrito en [TABLA_RECREABLE].
     */
    @Test
    fun `las migraciones nuevas solo crean, anaden columnas o recrean participantes`() {
        for (sql in TODAS) {
            val normal = sql.replace(Regex("\\s+"), " ").trim().uppercase()
            // «No menciona ninguna otra tabla» en vez de «sólo menciona
            // participantes»: los nombres de COLUMNA también van entre acentos
            // graves, así que mirar todos los identificadores entrecomillados
            // confunde `id` con una tabla.
            val soloParticipantes = OTRAS_TABLAS.none { "`$it`" in sql }

            val permitida = normal.startsWith("CREATE ") ||
                (normal.startsWith("ALTER TABLE ") && " ADD COLUMN " in normal) ||
                (soloParticipantes && (
                    normal.startsWith("DROP TABLE ") ||
                        normal.startsWith("INSERT INTO ") ||
                        (normal.startsWith("ALTER TABLE ") && " RENAME TO " in normal)
                    ))

            assertTrue("sentencia no aditiva sobre una tabla protegida:\n$sql", permitida)
        }
    }

    /**
     * Y ninguna migración borra una tabla que no sea la declarada recreable.
     *
     * Es el cinturón del test de arriba: si alguien añadiera un `DROP TABLE
     * bloques`, el patrón de nombres podría colar y esta prueba no.
     */
    @Test
    fun `solo se destruye la tabla declarada recreable`() {
        val borradas = TODAS
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.uppercase().startsWith("DROP TABLE") }
            .map { it.substringAfter("`").substringBefore("`") }
        for (t in borradas) {
            assertEquals("se borra `$t`, que no es la tabla recreable", TABLA_RECREABLE, t)
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
            assertTrue("se altera `$t`, que no la crean estas migraciones", t in TABLAS_ALTERABLES)
        }
    }

    // ------------------------------------------------------------------
    // La 10->11 y sus datos
    // ------------------------------------------------------------------

    /**
     * La 10->11 conserva los datos, **con las claves ajenas desactivadas**.
     *
     * Ese «con» no es un detalle: es la PRECONDICIÓN de la migración, y está
     * medida en la prueba de abajo. `sesiones_controladas.participanteId` apunta
     * a `participantes(id)` con borrado EN CASCADA, y `DROP TABLE` hace un
     * DELETE implícito de todas las filas antes de borrar la tabla. Con las
     * claves ajenas activas, esa cascada se lleva por delante las sesiones, los
     * bloques y los eventos de tecleo.
     *
     * Room desactiva las claves ajenas mientras corre las migraciones y las
     * vuelve a activar al abrir, que es por lo que el procedimiento estándar de
     * SQLite —crear, copiar, borrar, renombrar— funciona ahí. Aquí se reproduce
     * esa condición y se comprueba lo que importa: que el participante conserva
     * su id, y con él sus sesiones y sus bloques.
     */
    @Test
    fun `la 10 a 11 conserva participantes, sesiones y bloques`() {
        conectar().use { con ->
            con.createStatement().use { st ->
                // Como Room: desactivadas durante la migración.
                st.execute("PRAGMA foreign_keys = OFF")
                for (sql in AppDatabase.SQL_7_8 + AppDatabase.SQL_8_9 + AppDatabase.SQL_9_10) {
                    st.execute(sql)
                }
                st.execute(
                    "INSERT INTO participantes (id, seudonimo, fechaAltaMs, tramoEdad, " +
                        "sexo, lateralidad, competenciaLatin, notas) " +
                        "VALUES (7, 'P07', 123, '25-34', 'f', 'diestra', 'ninguna', '')"
                )
                st.execute(
                    "INSERT INTO sesiones_controladas (id, participanteId, dispositivoId, " +
                        "inicioMs, finMs, ordenDispositivo, semillaSeleccion, versionApp, " +
                        "versionProtocolo, estado, motivoInvalidacion) " +
                        "VALUES (3, 7, 'A', 1, 2, 1, 99, '1.12', '1.1', 'COMPLETA', '')"
                )
                st.execute(
                    "INSERT INTO bloques (id, sesionId, indice, inicioMs, finMs, idioma, " +
                        "parrafosUsados, pulsaciones, errores, borrados, ppm, precision, " +
                        "interrumpido, motivoInterrupcion) " +
                        "VALUES (5, 3, 0, 1, 2, 'la', 'la_0001', 10, 1, 0, 5.0, 0.9, 0, '')"
                )

                for (sql in AppDatabase.SQL_10_11) st.execute(sql)

                assertEquals(1, contar(con, "participantes"))
                assertEquals("el id se conserva: es lo que enlaza sus sesiones", 7, primerId(con))
                assertEquals("la sesion sigue ahi", 1, contar(con, "sesiones_controladas"))
                assertEquals("y el bloque", 1, contar(con, "bloques"))

                // Y al reactivarlas, ninguna referencia quedó colgando.
                st.execute("PRAGMA foreign_keys = ON")
                st.executeQuery("PRAGMA foreign_key_check").use { rs ->
                    assertTrue("hay claves ajenas rotas tras migrar", !rs.next())
                }
            }
        }
    }

    /**
     * Deja MEDIDO por qué la precondición de arriba es una precondición.
     *
     * Esta prueba comprueba que, con las claves ajenas ACTIVAS, la 10->11
     * destruye las sesiones. No es una prueba de que el código esté bien: es la
     * razón documentada de que la migración exija que estén desactivadas, y
     * existe para que nadie la reordene o la copie a otra migración creyendo que
     * el patrón «crear, copiar, borrar, renombrar» es inocuo.
     *
     * Consecuencia práctica, anotada el 30/08: esta migración se aplica ANTES de
     * que empiece la recogida de campo, con `participantes` prácticamente vacía.
     * Si hubiera que repetir un cambio así con participantes reales dentro, hay
     * que exportar antes.
     */
    @Test
    fun `con las claves ajenas activas la recreacion arrastraria las sesiones`() {
        conectar().use { con ->
            con.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                for (sql in AppDatabase.SQL_7_8 + AppDatabase.SQL_8_9 + AppDatabase.SQL_9_10) {
                    st.execute(sql)
                }
                st.execute(
                    "INSERT INTO participantes (id, seudonimo, fechaAltaMs, tramoEdad, " +
                        "sexo, lateralidad, competenciaLatin, notas) " +
                        "VALUES (7, 'P07', 123, '25-34', 'f', 'diestra', 'ninguna', '')"
                )
                st.execute(
                    "INSERT INTO sesiones_controladas (id, participanteId, dispositivoId, " +
                        "inicioMs, finMs, ordenDispositivo, semillaSeleccion, versionApp, " +
                        "versionProtocolo, estado, motivoInvalidacion) " +
                        "VALUES (3, 7, 'A', 1, 2, 1, 99, '1.12', '1.1', 'COMPLETA', '')"
                )

                for (sql in AppDatabase.SQL_10_11) st.execute(sql)

                assertEquals(
                    "si esto deja de ser 0, SQLite cambio de comportamiento y la " +
                        "nota de la migracion hay que revisarla",
                    0, contar(con, "sesiones_controladas")
                )
            }
        }
    }

    /** Y las columnas que se querían quitar ya no están. */
    @Test
    fun `la 10 a 11 deja participantes solo con id, seudonimo y fecha`() {
        conectar().use { con ->
            aplicarTodas(con)
            assertEquals(
                setOf("id", "seudonimo", "fechaAltaMs"),
                columnasReales(con, "participantes").keys
            )
        }
    }

    private fun contar(con: Connection, tabla: String): Int =
        con.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$tabla`").use { it.getInt(1) }
        }

    private fun primerId(con: Connection): Int =
        con.createStatement().use { st ->
            st.executeQuery("SELECT id FROM participantes").use { it.getInt(1) }
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
        const val VERSION = 12
        const val RUTA =
            "schemas/com.example.autenticacioncontinua.data.local.AppDatabase/12.json"

        val TODAS: List<String> =
            AppDatabase.SQL_7_8 + AppDatabase.SQL_8_9 + AppDatabase.SQL_9_10 +
                AppDatabase.SQL_10_11 + AppDatabase.SQL_11_12

        /**
         * La ÚNICA tabla que una migración puede reescribir o destruir.
         *
         * La 10->11 elimina las covariables de `participantes`, y SQLite no
         * soporta `DROP COLUMN` antes de la 3.35 —el terminal del estudio va con
         * Android 13, o sea SQLite 3.32—, así que la única vía es recrear la
         * tabla y copiar. La prohibición general sigue en pie: `participantes`
         * tiene una fila por persona del estudio, decenas; lo que la regla
         * protege son las tablas de campo, con millones.
         *
         * La excepción es por NOMBRE y no por tipo de sentencia a propósito: si
         * mañana alguien necesita un `DROP TABLE`, tiene que venir aquí y
         * escribir qué tabla y por qué.
         */
        const val TABLA_RECREABLE = "participantes"

        /**
         * Todo lo que una sentencia no aditiva NO puede mencionar.
         *
         * Incluye las tablas de la recogida ambiental —millones de filas de
         * campo— y las del corpus controlado salvo `participantes` y su tabla de
         * paso.
         */
        val OTRAS_TABLAS = listOf(
            "accelerometer_data", "gyroscope_data", "labeled_sessions",
            "mediciones_recursos", "mediciones_latencia",
            "sesiones_controladas", "bloques",
            "muestras_inerciales", "eventos_tecleo", "covariables_sesion"
        )

        /** Tablas que las migraciones crean y que Room declara como entidades. */
        val TABLAS_NUEVAS = listOf(
            "mediciones_recursos", "mediciones_latencia",
            "participantes", "sesiones_controladas", "bloques",
            "muestras_inerciales", "eventos_tecleo", "covariables_sesion"
        )

        /**
         * Las de arriba más la tabla de paso de la 10->11.
         *
         * `participantes_nueva` sólo existe entre el `CREATE` y el `RENAME`, así
         * que no está en el esquema exportado y no se le puede comparar
         * columnas — pero sí puede aparecer en un `ALTER`.
         */
        val TABLAS_ALTERABLES = TABLAS_NUEVAS + "participantes_nueva"
    }
}
