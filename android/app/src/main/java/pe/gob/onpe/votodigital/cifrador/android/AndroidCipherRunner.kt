package pe.gob.onpe.votodigital.cifrador.android

import android.content.Context
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode
import pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService
import pe.gob.onpe.votodigital.elgamalcipher.LogConfig
import java.io.File
import java.util.logging.Level

class AndroidCipherRunner(private val context: Context) {

    data class CipherExecutionResult(
        val success: Boolean,
        val outputFile: File,
        val logFile: File,
        val byteCount: Long,
        val lineCount: Int,
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

        val success = ElGamalCipherService(logger).encrypt(request)
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

    private fun requireReadable(file: File, label: String) {
        check(file.isFile) { "Falta archivo de entrada $label en ${file.absolutePath}" }
        check(file.canRead()) { "No se puede leer $label en ${file.absolutePath}" }
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
