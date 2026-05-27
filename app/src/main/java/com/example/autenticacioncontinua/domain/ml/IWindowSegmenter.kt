package com.example.autenticacioncontinua.domain.ml

interface IWindowSegmenter {
    suspend fun getWindows(minWindows: Int = 1): List<FloatArray>?
}
