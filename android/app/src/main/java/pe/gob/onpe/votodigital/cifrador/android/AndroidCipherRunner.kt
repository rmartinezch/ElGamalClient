package pe.gob.onpe.votodigital.cifrador.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.verificatum.crypto.RandomSource
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode
import pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService
import pe.gob.onpe.votodigital.elgamalcipher.LogConfig
import java.io.File
import java.io.IOException
import java.util.logging.Level

class AndroidCipherRunner(private val context: Context) {

    private val trueRngSupport = AndroidTrueRngSupport(context)

    data class CipherExecutionResult(
        val success: Boolean,
        val outputFile: File,
        val logFile: File,
        val byteCount: Long,
        val lineCount: Int,
        val message: String
    )

    data class ImportedInput(
        val targetFile: File,
        val byteCount: Long,
        val message: String
    )

    data class ExportResult(
        val exportedFile: File,
        val byteCount: Long,
        val message: String
    )

    fun encryptSandboxInputs(
        rngMode: CifradorRngMode = CifradorRngMode.SOFTWARE
    ): CipherExecutionResult {
        val inputDir = File(context.filesDir, INPUT_DIR_NAME)
        val exportDir = File(context.filesDir, EXPORT_DIR_NAME)
        val logDir = File(context.filesDir, LOG_DIR_NAME)
        val publicKeyFile = File(inputDir, PUBLIC_KEY_FILE_NAME)
        val votesFile = File(inputDir, VOTES_FILE_NAME)
        val ciphertextsFile = File(exportDir, CIPHERTEXTS_FILE_NAME)

        requireReadable(publicKeyFile, "publicKey")
        requireReadable(votesFile, VOTES_FILE_NAME)

        exportDir.mkdirs()
        logDir.mkdirs()
        if (ciphertextsFile.exists()) {
            ciphertextsFile.delete()
        }

        val logFile = File(logDir, LOG_FILE_NAME)
        if (logFile.exists()) {
            logFile.delete()
        }

        val logger = LogConfig.getLogger(
            logFile.absolutePath,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
        )

        val request = CifradorRequest(
            publicKeyFile.toPath(),
            votesFile.toPath(),
            ciphertextsFile.toPath(),
            rngMode,
            false
        )

        val success = if (rngMode == CifradorRngMode.HARDWARE) {
            encryptWithAndroidHardware(request, logger)
        } else {
            ElGamalCipherService(logger).encrypt(request)
        }
        val byteCount = if (ciphertextsFile.isFile) ciphertextsFile.length() else 0L
        val lineCount = if (ciphertextsFile.isFile) {
            ciphertextsFile.useLines { lines -> lines.count() }
        } else {
            0
        }

        val message = buildString {
            append("publicKey=")
            append(publicKeyFile.absolutePath)
            append("\nvotes=")
            append(votesFile.absolutePath)
            append("\noutput=")
            append(ciphertextsFile.absolutePath)
            append("\nlog=")
            append(logFile.absolutePath)
            append("\nresultado=")
            append(if (success) "OK" else "ERROR")
            append(" bytes=")
            append(byteCount)
            append(" lineas=")
            append(lineCount)
        }

        return CipherExecutionResult(
            success = success && byteCount > 0 && lineCount > 0,
            outputFile = ciphertextsFile,
            logFile = logFile,
            byteCount = byteCount,
            lineCount = lineCount,
            message = message
        )
    }

    fun describeTrueRngStatus(): String {
        return trueRngSupport.scanStatus().message
    }

    private fun encryptWithAndroidHardware(
        request: CifradorRequest,
        logger: java.util.logging.Logger
    ): Boolean {
        var hardwareSource: RandomSource? = null
        try {
            hardwareSource = trueRngSupport.openRandomSource(logger)
            return ElGamalCipherService(logger).encrypt(request, hardwareSource)
        } finally {
            if (hardwareSource is AutoCloseable) {
                try {
                    hardwareSource.close()
                } catch (t: Throwable) {
                    logger.log(Level.FINE, "No se pudo cerrar el TrueRNG Android tras el cifrado.", t)
                }
            }
        }
    }

    fun importPublicKey(sourceUri: Uri): ImportedInput {
        return importIntoSandbox(sourceUri, PUBLIC_KEY_FILE_NAME, "publicKey")
    }

    fun importVotes(sourceUri: Uri): ImportedInput {
        return importIntoSandbox(sourceUri, VOTES_FILE_NAME, VOTES_FILE_NAME)
    }

    fun exportCiphertexts(targetUri: Uri): ExportResult {
        val exportDir = File(context.filesDir, EXPORT_DIR_NAME)
        val ciphertextsFile = File(exportDir, CIPHERTEXTS_FILE_NAME)
        requireReadable(ciphertextsFile, CIPHERTEXTS_FILE_NAME)

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
            ciphertextsFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw IOException("No se pudo abrir el destino de exportacion.")

        return ExportResult(
            exportedFile = ciphertextsFile,
            byteCount = ciphertextsFile.length(),
            message = "ciphertexts_ext exportado desde ${ciphertextsFile.absolutePath}"
        )
    }

    fun currentPublicKeyFile(): File {
        return File(File(context.filesDir, INPUT_DIR_NAME), PUBLIC_KEY_FILE_NAME)
    }

    fun currentVotesFile(): File {
        return File(File(context.filesDir, INPUT_DIR_NAME), VOTES_FILE_NAME)
    }

    private fun requireReadable(file: File, label: String) {
        check(file.isFile) { "Falta archivo de entrada $label en ${file.absolutePath}" }
        check(file.canRead()) { "No se puede leer $label en ${file.absolutePath}" }
    }

    private fun importIntoSandbox(sourceUri: Uri, targetName: String, label: String): ImportedInput {
        val inputDir = File(context.filesDir, INPUT_DIR_NAME)
        val targetFile = File(inputDir, targetName)
        inputDir.mkdirs()
        val sourceDescription = describeSourceUri(sourceUri)

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("No se pudo abrir $label desde $sourceUri")

        return ImportedInput(
            targetFile = targetFile,
            byteCount = targetFile.length(),
            message = "$label importado desde $sourceDescription\ncopiado a ${targetFile.absolutePath}"
        )
    }

    private fun describeSourceUri(sourceUri: Uri): String {
        context.contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val displayName = cursor.getString(nameIndex)
                if (!displayName.isNullOrBlank()) {
                    return "$displayName ($sourceUri)"
                }
            }
        }

        return sourceUri.toString()
    }

    companion object {
        const val INPUT_DIR_NAME = "inputs"
        const val EXPORT_DIR_NAME = "exports"
        const val LOG_DIR_NAME = "logs"
        const val PUBLIC_KEY_FILE_NAME = "publicKey"
        const val VOTES_FILE_NAME = "shuffled_votes.txt"
        const val CIPHERTEXTS_FILE_NAME = "ciphertexts_ext"
        const val LOG_FILE_NAME = "android-cifrador.log"
    }
}
