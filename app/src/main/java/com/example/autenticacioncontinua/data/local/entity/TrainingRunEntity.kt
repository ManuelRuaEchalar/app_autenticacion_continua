package com.example.autenticacioncontinua.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.autenticacioncontinua.domain.model.TrainingRun

/**
 * Una sesión federada terminada.
 *
 * Se persiste el resultado completo, no solo el EER: sin FAR y FRR al lado, un
 * EER decente puede esconder un umbral que rechaza al usuario legítimo el 100%
 * de las veces —que es exactamente lo que pasó en la medición del 2026-08-11—.
 *
 * También se guarda el número de sesiones distintas de la partición: por
 * debajo de tres, `DatasetSplitter` corta por tiempo dentro de una misma
 * grabación y las métricas quedan infladas por fuga. Sin ese dato al lado, un
 * AUC de 0,99 en el historial parecería un éxito.
 */
@Entity(tableName = "training_runs")
data class TrainingRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    val startedAtMs: Long,
    val finishedAtMs: Long,
    val rounds: Int,

    val trainWindows: Int,
    val valWindows: Int,
    val testWindows: Int,
    val sessionCount: Int,

    /** Última validación vista durante la federación (guía la parada temprana). */
    val lastValAuc: Double,
    val lastValEer: Double,

    /** Medición final sobre el conjunto ciego. -1 si no llegó a ejecutarse. */
    val testAuc: Double,
    val testEer: Double,
    val testFar: Double,
    val testFrr: Double,
    val threshold: Float,

    val completed: Boolean,
    val errorMessage: String?
)

fun TrainingRunEntity.toDomain(): TrainingRun = TrainingRun(
    id = id,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    rounds = rounds,
    trainWindows = trainWindows,
    valWindows = valWindows,
    testWindows = testWindows,
    sessionCount = sessionCount,
    lastValAuc = lastValAuc,
    lastValEer = lastValEer,
    testAuc = testAuc,
    testEer = testEer,
    testFar = testFar,
    testFrr = testFrr,
    threshold = threshold,
    completed = completed,
    errorMessage = errorMessage
)

fun TrainingRun.toEntity(): TrainingRunEntity = TrainingRunEntity(
    id = id,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    rounds = rounds,
    trainWindows = trainWindows,
    valWindows = valWindows,
    testWindows = testWindows,
    sessionCount = sessionCount,
    lastValAuc = lastValAuc,
    lastValEer = lastValEer,
    testAuc = testAuc,
    testEer = testEer,
    testFar = testFar,
    testFrr = testFrr,
    threshold = threshold,
    completed = completed,
    errorMessage = errorMessage
)
