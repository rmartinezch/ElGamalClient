package pe.gob.onpe.votodigital.truerngdiag

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.SerialTimeoutException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()

    fun scan(): ScanResult {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val drivers = prober.findAllDrivers(usbManager)
        val driverByDeviceName = drivers.associateBy { it.device.deviceName }

        val report = buildString {
            appendLine("Diagnostico USB serial Android")
            appendLine("total_usb_devices=${devices.size}")
            appendLine("supported_serial_devices=${drivers.size}")
            if (devices.isEmpty()) {
                appendLine("No se detectaron dispositivos USB.")
            }
            devices.forEachIndexed { index, device ->
                val driver = driverByDeviceName[device.deviceName]
                appendLine()
                appendLine("[usb_device_${index + 1}]")
                appendLine(describeDevice(device, driver))
            }
            if (drivers.isEmpty()) {
                appendLine()
                appendLine("No se reconocio ningun driver serial compatible.")
                appendLine("Si el TrueRNG aparece arriba sin driver serial, la incompatibilidad esta en la capa USB/driver.")
            }
        }
        return ScanResult(report.trimEnd(), drivers.isNotEmpty())
    }

    fun requestPermissionForFirstSupportedDevice(): String {
        val driver = firstSupportedDriver()
            ?: return "No hay dispositivo USB serial compatible para pedir permiso."
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            return "El permiso USB ya estaba concedido para ${formatDeviceHeader(device)}."
        }
        return "REQUEST:${device.deviceId}"
    }

    fun buildPermissionResult(deviceId: Int, granted: Boolean): String {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
        val deviceLabel = if (device == null) {
            "deviceId=$deviceId"
        } else {
            formatDeviceHeader(device)
        }
        return if (granted) {
            "Permiso USB concedido para $deviceLabel."
        } else {
            "Permiso USB denegado para $deviceLabel."
        }
    }

    fun getDeviceById(deviceId: Int): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun readSample(sampleSize: Int = 4096, timeoutMs: Int = 1000, totalWindowMs: Long = 5000L): ReadResult {
        val driver = firstSupportedDriver()
            ?: return ReadResult(
                report = "No hay driver serial compatible para lectura.",
                bytesRead = 0,
                success = false
            )
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            return ReadResult(
                report = "Falta permiso USB para ${formatDeviceHeader(device)}.",
                bytesRead = 0,
                success = false
            )
        }

        val connection = usbManager.openDevice(device)
            ?: return ReadResult(
                report = "Android no pudo abrir el dispositivo ${formatDeviceHeader(device)}.",
                bytesRead = 0,
                success = false
            )

        val port = driver.ports.firstOrNull()
            ?: return ReadResult(
                report = "El driver serial no expone puertos para ${formatDeviceHeader(device)}.",
                bytesRead = 0,
                success = false
            )

        val startedAt = System.nanoTime()
        val output = ByteArrayOutputStream(sampleSize)
        lateinit var setupNotes: String

        return try {
            port.open(connection)
            setupNotes = configurePort(port)
            val scratch = ByteArray(min(256, sampleSize))
            while (output.size() < sampleSize) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                if (elapsedMs >= totalWindowMs) {
                    break
                }
                val wanted = min(scratch.size, sampleSize - output.size())
                val readCount = try {
                    port.read(scratch, timeoutMs)
                } catch (_: SerialTimeoutException) {
                    0
                }
                if (readCount > 0) {
                    output.write(scratch, 0, min(readCount, wanted))
                }
            }

            val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            val payload = output.toByteArray()
            val success = payload.isNotEmpty()
            ReadResult(
                report = buildString {
                    appendLine("Lectura USB serial")
                    appendLine("device=${formatDeviceHeader(device)}")
                    appendLine("driver=${driver.javaClass.simpleName}")
                    appendLine("port=${port.javaClass.simpleName}")
                    appendLine("setup=$setupNotes")
                    appendLine("bytes_read=${payload.size}")
                    appendLine("elapsed_ms=$elapsedMs")
                    appendLine("throughput_bps=${payload.size * 1000L / elapsedMs}")
                    appendLine("unique_bytes=${payload.toSet().size}")
                    appendLine("preview_hex=${payload.take(32).joinToString(separator = "") { "%02x".format(it) }}")
                    appendLine(
                        if (success) {
                            "Resultado: Android pudo abrir el puerto y recibir bytes."
                        } else {
                            "Resultado: Android abrio el puerto pero no recibio datos en la ventana de lectura."
                        }
                    )
                }.trimEnd(),
                bytesRead = payload.size,
                success = success
            )
        } catch (t: Throwable) {
            ReadResult(
                report = buildString {
                    appendLine("Fallo de lectura USB serial")
                    appendLine("device=${formatDeviceHeader(device)}")
                    appendLine("driver=${driver.javaClass.simpleName}")
                    appendLine("error=${t.javaClass.simpleName}: ${t.message ?: "sin_detalle"}")
                }.trimEnd(),
                bytesRead = 0,
                success = false
            )
        } finally {
            try {
                port.close()
            } catch (_: Throwable) {
            }
            try {
                connection.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun runStabilityProbe(
        durationSeconds: Int,
        timeoutMs: Int = 250,
        chunkSize: Int = 512
    ): StabilityResult {
        val driver = firstSupportedDriver()
            ?: return StabilityResult(
                report = "No hay driver serial compatible para la prueba de estabilidad.",
                totalBytes = 0,
                success = false
            )
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            return StabilityResult(
                report = "Falta permiso USB para ${formatDeviceHeader(device)}.",
                totalBytes = 0,
                success = false
            )
        }

        val connection = usbManager.openDevice(device)
            ?: return StabilityResult(
                report = "Android no pudo abrir el dispositivo ${formatDeviceHeader(device)}.",
                totalBytes = 0,
                success = false
            )

        val port = driver.ports.firstOrNull()
            ?: return StabilityResult(
                report = "El driver serial no expone puertos para ${formatDeviceHeader(device)}.",
                totalBytes = 0,
                success = false
            )

        val buckets = LongArray(durationSeconds.coerceAtLeast(1))
        val digest = MessageDigest.getInstance("SHA-256")
        val firstPreview = ByteArrayOutputStream(64)
        var setupNotes = "sin_notas"
        var totalBytes = 0L
        var readOps = 0
        var nonZeroReads = 0
        var zeroReads = 0
        var longestZeroReadRun = 0
        var currentZeroReadRun = 0
        var failure: Throwable? = null
        val scratch = ByteArray(chunkSize.coerceAtLeast(64))
        val startedAt = System.nanoTime()

        try {
            port.open(connection)
            setupNotes = configurePort(port)
            val deadlineMs = durationSeconds.coerceAtLeast(1) * 1000L
            while (true) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                if (elapsedMs >= deadlineMs) {
                    break
                }
                val bucketIndex = (elapsedMs / 1000L).toInt().coerceIn(0, buckets.lastIndex)
                val readCount = try {
                    port.read(scratch, timeoutMs)
                } catch (_: SerialTimeoutException) {
                    0
                }
                readOps += 1
                if (readCount > 0) {
                    nonZeroReads += 1
                    currentZeroReadRun = 0
                    totalBytes += readCount
                    buckets[bucketIndex] += readCount.toLong()
                    digest.update(scratch, 0, readCount)
                    if (firstPreview.size() < 64) {
                        val remaining = 64 - firstPreview.size()
                        firstPreview.write(scratch, 0, min(remaining, readCount))
                    }
                } else {
                    zeroReads += 1
                    currentZeroReadRun += 1
                    if (currentZeroReadRun > longestZeroReadRun) {
                        longestZeroReadRun = currentZeroReadRun
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                port.close()
            } catch (_: Throwable) {
            }
            try {
                connection.close()
            } catch (_: Throwable) {
            }
        }

        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        val activeSeconds = buckets.count { it > 0L }
        val zeroByteSeconds = buckets.size - activeSeconds
        val longestZeroByteRun = longestZeroByteRun(buckets)
        val minNonZeroSecond = buckets.filter { it > 0L }.minOrNull() ?: 0L
        val maxSecond = buckets.maxOrNull() ?: 0L
        val success = failure == null && totalBytes > 0L

        val report = buildString {
            appendLine("Prueba de estabilidad USB serial")
            appendLine("device=${formatDeviceHeader(device)}")
            appendLine("driver=${driver.javaClass.simpleName}")
            appendLine("port=${port.javaClass.simpleName}")
            appendLine("setup=$setupNotes")
            appendLine("duration_seconds=${durationSeconds.coerceAtLeast(1)}")
            appendLine("elapsed_ms=$elapsedMs")
            appendLine("total_bytes=$totalBytes")
            appendLine("average_bps=${totalBytes * 1000L / elapsedMs}")
            appendLine("read_ops=$readOps")
            appendLine("non_zero_reads=$nonZeroReads")
            appendLine("zero_reads=$zeroReads")
            appendLine("longest_zero_read_run=$longestZeroReadRun")
            appendLine("active_seconds=$activeSeconds")
            appendLine("zero_byte_seconds=$zeroByteSeconds")
            appendLine("longest_zero_byte_run=$longestZeroByteRun")
            appendLine("min_non_zero_second_bytes=$minNonZeroSecond")
            appendLine("max_second_bytes=$maxSecond")
            appendLine("first_preview_hex=${firstPreview.toByteArray().joinToString(separator = "") { "%02x".format(it) }}")
            appendLine("sha256=${digest.digest().joinToString(separator = "") { "%02x".format(it) }}")
            appendLine("bytes_per_second=${buckets.joinToString(separator = ",")}")
            if (failure == null) {
                appendLine(
                    if (totalBytes > 0L) {
                        "Resultado: lectura sostenida completada sin errores fatales."
                    } else {
                        "Resultado: la prueba termino sin excepcion, pero no se recibieron bytes."
                    }
                )
            } else {
                appendLine("Resultado: fallo durante la lectura sostenida.")
                appendLine("error=${failure.javaClass.simpleName}: ${failure.message ?: "sin_detalle"}")
            }
        }.trimEnd()

        return StabilityResult(
            report = report,
            totalBytes = totalBytes,
            success = success
        )
    }

    private fun firstSupportedDriver(): UsbSerialDriver? {
        return prober.findAllDrivers(usbManager).sortedBy { it.device.deviceName }.firstOrNull()
    }

    private fun configurePort(port: UsbSerialPort): String {
        val notes = mutableListOf<String>()
        try {
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            notes += "baud=115200"
        } catch (t: Throwable) {
            notes += "setParameters=${t.javaClass.simpleName}"
        }
        try {
            port.dtr = true
            notes += "dtr=1"
        } catch (t: Throwable) {
            notes += "dtr=${t.javaClass.simpleName}"
        }
        try {
            port.rts = true
            notes += "rts=1"
        } catch (t: Throwable) {
            notes += "rts=${t.javaClass.simpleName}"
        }
        return notes.joinToString(separator = ",")
    }

    private fun describeDevice(device: UsbDevice, driver: UsbSerialDriver?): String {
        return buildString {
            appendLine("header=${formatDeviceHeader(device)}")
            appendLine("device_name=${device.deviceName}")
            appendLine("manufacturer=${safeString { device.manufacturerName }}")
            appendLine("product=${safeString { device.productName }}")
            appendLine("serial=${safeString { device.serialNumber }}")
            appendLine("class=${device.deviceClass} subclass=${device.deviceSubclass} protocol=${device.deviceProtocol}")
            appendLine("interfaces=${device.interfaceCount}")
            repeat(device.interfaceCount) { index ->
                val iface = device.getInterface(index)
                appendLine(
                    "  iface[$index]=class:${iface.interfaceClass} subclass:${iface.interfaceSubclass} protocol:${iface.interfaceProtocol} endpoints:${iface.endpointCount}"
                )
            }
            appendLine("permission=${if (usbManager.hasPermission(device)) "yes" else "no"}")
            appendLine("serial_driver=${driver?.javaClass?.simpleName ?: "none"}")
            append("ports=${driver?.ports?.size ?: 0}")
        }
    }

    private fun formatDeviceHeader(device: UsbDevice): String {
        return "vid=0x%04x pid=0x%04x id=%d".format(device.vendorId, device.productId, device.deviceId)
    }

    private fun safeString(block: () -> String?): String {
        return try {
            block() ?: "n/a"
        } catch (t: Throwable) {
            "unavailable:${t.javaClass.simpleName}"
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
}
