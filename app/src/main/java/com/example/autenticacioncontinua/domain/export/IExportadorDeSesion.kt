package com.example.autenticacioncontinua.domain.export

import java.io.File

/** Lo que queda tras exportar una visita, para poder enseñarlo y comprobarlo. */
data class PaqueteDeSesion(
    val fichero: File,
    /** SHA-256 del zip. Es lo que se compara con el PC. */
    val huella: String,
    val bytes: Long,
    val filasPorTabla: Map<String, Int>
) {
    /** Los primeros doce dígitos bastan para cotejar a ojo en la mesa de trabajo. */
    val huellaCorta: String get() = huella.take(12)
}

/**
 * Exportación de UNA visita, al terminarla.
 *
 * ### Por qué no se reutiliza `exportDatabaseZip`
 *
 * Aquélla comprime la base ENTERA —230 MB en el móvil 1 a fecha de hoy, casi
 * todo corpus ambiental— y existe para recoger el corpus de fondo de un
 * participante remoto cada varios días. Usarla al final de cada visita de cinco
 * minutos sería copiar los mismos 230 MB diez veces por persona, llenar el
 * teléfono a mitad del estudio y tardar minutos con el participante esperando.
 *
 * Lo que hay que salvar de una visita son sus tres bloques: unas 30 000
 * muestras inerciales y unos 650 eventos de tecleo. Comprimido, un par de MB.
 */
interface IExportadorDeSesion {

    /**
     * Escribe el paquete de la sesión y devuelve dónde quedó.
     *
     * @param sesionId la visita que se acaba de cerrar.
     */
    suspend fun exportar(sesionId: Long): Result<PaqueteDeSesion>

    /**
     * Vuelve a leer un paquete ya escrito y comprueba que sigue íntegro.
     *
     * No es una comodidad de las pruebas: es lo que permite que el investigador
     * verifique una copia semanas después sin más herramienta que la propia
     * aplicación, y es la mitad de la prueba que pide la fase 9 —«el fichero
     * exportado se vuelve a leer y coincide fila a fila»—.
     */
    suspend fun verificar(fichero: File): Result<VerificacionDePaquete>
}

/** Resultado de releer un paquete. */
data class VerificacionDePaquete(
    val huella: String,
    val tablasIntactas: List<String>,
    val tablasCorruptas: List<String>,
    val filasPorTabla: Map<String, Int>
) {
    val integro: Boolean get() = tablasCorruptas.isEmpty() && tablasIntactas.isNotEmpty()
}
