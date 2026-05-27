package com.example.autenticacioncontinua.domain.export

import android.net.Uri

interface IDataExportService {
    suspend fun exportToCsv(uri: Uri): Result<Unit>
}
