package pe.gob.onpe.votodigital.truerngdiag

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import pe.gob.onpe.votodigital.cifrador.android.AndroidTrueRngSupport
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.logging.Logger
import kotlin.math.min

class TrueRngUsbDiagnostics(context: Context) {

    data class ScanResult(
        val report: String,
        val hasSupportedDriver: Boolean
    )

    data class ReadResult(
        val report: String,
        val bytesRead: Int,
        val success: Boolean
    )

    data class StabilityResult(
        val report: String,
        val totalBytes: Long,
        val success: Boolean
    )

    private val logger = Logger.getLogger("TrueRngUsbDiagnostics")
    private val trueRngSupport = AndroidTrueRngSupport(context)

    fun scan(): ScanResult {
        val status = trueRngSupport.scanStatus()
        val report = buildString {
            appendLine("Diagnostico TrueRNG desde AAR")
            appendLine("soporte_rng_software=disponible_via_SecureRandom")
            appendLine("soporte_rng_hardware=${if (status.deviceFound) "true" else "false"}")
            appendLine("device_found=${status.deviceFound}")
            appendLine("permission_granted=${status.permissionGranted}")
            appendLine(status.message)
        }.trimEnd()
        return ScanResult(report, status.deviceFound)
    }

    fun requestPermission(pendingIntent: PendingIntent): String {
        return trueRngSupport.requestPermission(pendingIntent).message
    }

    fun buildPermissionResult(device: UsbDevice?, granted: Boolean): String {
        return trueRngSupport.buildPermissionResult(device, granted)
    }

    fun readSample(sampleSize: Int = 4096): ReadResult {
        return try {
            val payload = ByteArray(sampleSize.coerceAtLeast(1))
            val startedAt = System.nanoTime()
            trueRngSupport.openRandomSource(logger).use { randomSource ->
                randomSource.getBytes(payload)
            }
            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            val report = buildString {
                appendLine("Lectura TrueRNG desde AAR")
                appendLine("bytes_read=${payload.size}")
                appendLine("elapsed_ms=$elapsedMs")
                appendLine("throughput_bps=${payload.size * 1000L / elapsedMs}")
                appendLine("unique_bytes=${payload.toSet().size}")
                appendLine("preview_hex=${payload.take(32).joinToString(separator = "") { "%02x".format(it) }}")
                appendLine("Resultado: el AAR pudo abrir el TrueRNG y recibir bytes.")
            }.trimEnd()
            ReadResult(report, payload.size, true)
        } catch (t: Throwable) {
            val report = buildString {
                appendLine("Fallo de lectura TrueRNG desde AAR")
                appendLine("error=${t.javaClass.simpleName}: ${t.message ?: "sin_detalle"}")
            }.trimEnd()
            ReadResult(report, 0, false)
        }
    }

    fun runStabilityProbe(
        durationSeconds: Int,
        chunkSize: Int = 512
    ): StabilityResult {
        val safeDurationSeconds = durationSeconds.coerceAtLeast(1)
        val safeChunkSize = chunkSize.coerceAtLeast(64)
        val metrics = StabilityMetrics(
            buckets = LongArray(safeDurationSeconds),
            digest = MessageDigest.getInstance("SHA-256"),
            firstPreview = ByteArrayOutputStream(64)
        )
        val startedAt = System.nanoTime()
        var failure: Throwable? = null

        try {
            trueRngSupport.openRandomSource(logger).use { randomSource ->
                val scratch = ByteArray(safeChunkSize)
                while (true) {
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                    if (elapsedMs >= safeDurationSeconds * 1000L) {
                        break
                    }
                    val bucketIndex = (elapsedMs / 1000L).toInt().coerceIn(0, metrics.buckets.lastIndex)
                    try {
                        randomSource.getBytes(scratch)
                        updateStabilityMetrics(metrics, scratch, scratch.size, bucketIndex)
                    } catch (t: Throwable) {
                        failure = t
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
        }

        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        val activeSeconds = metrics.buckets.count { it > 0L }
        val zeroByteSeconds = metrics.buckets.size - activeSeconds
        val longestZeroByteRun = longestZeroByteRun(metrics.buckets)
        val minNonZeroSecond = metrics.buckets.filter { it > 0L }.minOrNull() ?: 0L
        val maxSecond = metrics.buckets.maxOrNull() ?: 0L
        val success = failure == null && metrics.totalBytes > 0L
        val failureDetail = failure

        val report = buildString {
            appendLine("Prueba de estabilidad TrueRNG desde AAR")
            appendLine("duration_seconds=$safeDurationSeconds")
            appendLine("elapsed_ms=$elapsedMs")
            appendLine("chunk_size=$safeChunkSize")
            appendLine("total_bytes=${metrics.totalBytes}")
            appendLine("average_bps=${metrics.totalBytes * 1000L / elapsedMs}")
            appendLine("read_ops=${metrics.readOps}")
            appendLine("non_zero_reads=${metrics.nonZeroReads}")
            appendLine("zero_reads=${metrics.zeroReads}")
            appendLine("longest_zero_read_run=${metrics.longestZeroReadRun}")
            appendLine("active_seconds=$activeSeconds")
            appendLine("zero_byte_seconds=$zeroByteSeconds")
            appendLine("longest_zero_byte_run=$longestZeroByteRun")
            appendLine("min_non_zero_second_bytes=$minNonZeroSecond")
            appendLine("max_second_bytes=$maxSecond")
            appendLine(
                "first_preview_hex=${
                    metrics.firstPreview.toByteArray().joinToString(separator = "") { "%02x".format(it) }
                }"
            )
            appendLine("sha256=${metrics.digest.digest().joinToString(separator = "") { "%02x".format(it) }}")
            appendLine("bytes_per_second=${metrics.buckets.joinToString(separator = ",")}")
            if (failureDetail == null) {
                appendLine(
                    if (metrics.totalBytes > 0L) {
                        "Resultado: lectura sostenida completada usando el backend TrueRNG del AAR."
                    } else {
                        "Resultado: la prueba termino sin excepcion, pero no se recibieron bytes."
                    }
                )
            } else {
                appendLine("Resultado: fallo durante la lectura sostenida del backend TrueRNG del AAR.")
                appendLine("error=${failureDetail.javaClass.simpleName}: ${failureDetail.message ?: "sin_detalle"}")
            }
        }.trimEnd()

        return StabilityResult(
            report = report,
            totalBytes = metrics.totalBytes,
            success = success
        )
    }

    private fun updateStabilityMetrics(
        metrics: StabilityMetrics,
        scratch: ByteArray,
        readCount: Int,
        bucketIndex: Int
    ) {
        metrics.readOps += 1
        if (readCount > 0) {
            metrics.nonZeroReads += 1
            metrics.currentZeroReadRun = 0
            metrics.totalBytes += readCount
            metrics.buckets[bucketIndex] += readCount.toLong()
            metrics.digest.update(scratch, 0, readCount)
            if (metrics.firstPreview.size() < 64) {
                val remaining = 64 - metrics.firstPreview.size()
                metrics.firstPreview.write(scratch, 0, min(remaining, readCount))
            }
            return
        }

        metrics.zeroReads += 1
        metrics.currentZeroReadRun += 1
        if (metrics.currentZeroReadRun > metrics.longestZeroReadRun) {
            metrics.longestZeroReadRun = metrics.currentZeroReadRun
        }
    }

    private fun longestZeroByteRun(buckets: LongArray): Int {
        var longest = 0
        var current = 0
        for (value in buckets) {
            if (value == 0L) {
                current += 1
                if (current > longest) {
                    longest = current
                }
            } else {
                current = 0
            }
        }
        return longest
    }

    private data class StabilityMetrics(
        val buckets: LongArray,
        val digest: MessageDigest,
        val firstPreview: ByteArrayOutputStream,
        var totalBytes: Long = 0L,
        var readOps: Int = 0,
        var nonZeroReads: Int = 0,
        var zeroReads: Int = 0,
        var longestZeroReadRun: Int = 0,
        var currentZeroReadRun: Int = 0
    )
}
