package pe.gob.onpe.votodigital.cifrador.android

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.verificatum.crypto.RandomSource
import com.verificatum.eio.ByteTree
import com.verificatum.eio.ByteTreeBasic
import java.nio.charset.StandardCharsets
import java.util.logging.Logger
import kotlin.math.min

class AndroidUsbTrueRngRandomSource private constructor(
    private val device: UsbDevice,
    private val driver: UsbSerialDriver,
    private val connection: UsbDeviceConnection,
    private val port: UsbSerialPort,
    private val logger: Logger
) : RandomSource(), AutoCloseable {

    private val deviceLabel = "vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId)

    override fun getBytes(array: ByteArray) {
        synchronized(this) {
            var offset = 0
            val scratch = ByteArray(array.size.coerceAtMost(256))
            while (offset < array.size) {
                val readCount = try {
                    port.read(scratch, READ_TIMEOUT_MS)
                } catch (e: SerialTimeoutException) {
                    0
                } catch (e: Throwable) {
                    throw IllegalStateException("No se pudo leer del TrueRNG USB en Android.", e)
                }
                if (readCount <= 0) {
                    throw IllegalStateException(
                        "El TrueRNG USB no produjo datos en Android dentro del timeout de lectura."
                    )
                }
                val copyCount = min(readCount, array.size - offset)
                System.arraycopy(scratch, 0, array, offset, copyCount)
                offset += copyCount
            }
        }
    }

    override fun toByteTree(): ByteTreeBasic {
        return ByteTree(humanDescription(false).toByteArray(StandardCharsets.UTF_8))
    }

    override fun humanDescription(verbose: Boolean): String {
        return "AndroidUsbTrueRngRandomSource($deviceLabel)"
    }

    override fun close() {
        synchronized(this) {
            try {
                port.close()
            } catch (_: Throwable) {
            }
            try {
                connection.close()
            } catch (_: Throwable) {
            }
            logger.info("Android -hw: puerto TrueRNG USB cerrado.")
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 1000

        fun open(
            device: UsbDevice,
            driver: UsbSerialDriver,
            connection: UsbDeviceConnection,
            logger: Logger
        ): AndroidUsbTrueRngRandomSource {
            val port = driver.ports.firstOrNull()
                ?: throw IllegalStateException("El driver serial TrueRNG no expone puertos en Android.")
            try {
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                port.rts = true
                logger.info("Android -hw: usando TrueRNG USB serial en ${device.deviceName}.")
                return AndroidUsbTrueRngRandomSource(device, driver, connection, port, logger)
            } catch (t: Throwable) {
                try {
                    port.close()
                } catch (_: Throwable) {
                }
                try {
                    connection.close()
                } catch (_: Throwable) {
                }
                throw IllegalStateException("No se pudo inicializar el TrueRNG USB en Android.", t)
            }
        }
    }
}
