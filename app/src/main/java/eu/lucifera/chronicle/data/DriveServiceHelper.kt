package eu.lucifera.chronicle.data

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

class DriveServiceHelper(private val driveService: Drive) {

    suspend fun uploadBackup(content: String): String? = withContext(Dispatchers.IO) {
        val metadata = File()
            .setName("ChronicleBackup.json")
            .setMimeType("application/json")
            .setParents(Collections.singletonList("root"))

        val contentStream = ByteArrayContent.fromString("application/json", content)

        // Check if file already exists
        val existingFile = findBackupFile()
        
        if (existingFile != null) {
            driveService.files().update(existingFile.id, null, contentStream).execute()
            existingFile.id
        } else {
            val googleFile = driveService.files().create(metadata, contentStream).execute()
            googleFile.id
        }
    }

    suspend fun downloadBackup(): String? = withContext(Dispatchers.IO) {
        val existingFile = findBackupFile() ?: return@withContext null
        
        val outputStream = java.io.ByteArrayOutputStream()
        driveService.files().get(existingFile.id).executeMediaAndDownloadTo(outputStream)
        outputStream.toString()
    }

    private fun findBackupFile(): File? {
        val result = driveService.files().list()
            .setQ("name = 'ChronicleBackup.json' and trashed = false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        return result.files.firstOrNull()
    }
}
