package pe.gob.onpe.votodigital.cifrador.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

class AndroidTrueRngSupport(private val context: Context) {

    data class DeviceStatus(
        val message: String,
        val deviceFound: Boolean,
        val permissionGranted: Boolean
    )

    data class PermissionRequest(
        val message: String,
        val requested: Boolean
    )

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()

    fun scanStatus(): DeviceStatus {
        val device = findDevice()
            ?: return DeviceStatus(
                message = "TrueRNG USB no detectado. Conectalo por OTG y vuelve a intentar.",
                deviceFound = false,
                permissionGranted = false
            )
        val driver = findDriver(device)
        val hasPermission = usbManager.hasPermission(device)
        val message = buildString {
            append("TrueRNG detectado: vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId))
            append("\ndriver=")
            append(driver?.javaClass?.simpleName ?: "no_serial_driver")
            append("\npermiso=")
            append(if (hasPermission) "concedido" else "pendiente")
        }
        return DeviceStatus(message, true, hasPermission)
    }

    fun requestPermission(pendingIntent: PendingIntent): PermissionRequest {
        val device = findDevice()
            ?: return PermissionRequest(
                message = "No se encontro un TrueRNG USB conectado para pedir permiso.",
                requested = false
            )
        if (usbManager.hasPermission(device)) {
            return PermissionRequest(
                message = "El permiso USB para TrueRNG ya estaba concedido.",
                requested = false
            )
        }
        usbManager.requestPermission(device, pendingIntent)
        return PermissionRequest(
            message = "Solicitud de permiso USB enviada para TrueRNG.",
            requested = true
        )
    }

    fun buildPermissionResult(device: UsbDevice?, granted: Boolean): String {
        val label = if (device == null) {
            "TrueRNG"
        } else {
            "vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId)
        }
        return if (granted) {
            "Permiso USB concedido para $label."
        } else {
            "Permiso USB denegado para $label."
        }
    }

    fun openRandomSource(logger: java.util.logging.Logger): AndroidUsbTrueRngRandomSource {
        val device = findDevice()
            ?: throw IllegalStateException("No se detecto un TrueRNG USB conectado en Android.")
        require(usbManager.hasPermission(device)) {
            "Falta permiso USB para el TrueRNG en Android. Usa 'Solicitar permiso TrueRNG'."
        }
        val driver = findDriver(device)
            ?: throw IllegalStateException("Android detecto el dispositivo, pero no encontro driver serial compatible para TrueRNG.")
        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Android no pudo abrir la conexion USB del TrueRNG.")
        return AndroidUsbTrueRngRandomSource.open(device, driver, connection, logger)
    }

    fun findDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull {
            it.vendorId == TRUE_RNG_VENDOR_ID && it.productId == TRUE_RNG_PRODUCT_ID
        }
    }

    private fun findDriver(device: UsbDevice): UsbSerialDriver? {
        return prober.findAllDrivers(usbManager).firstOrNull { it.device.deviceId == device.deviceId }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "pe.gob.onpe.votodigital.cifrador.android.USB_PERMISSION"
        const val TRUE_RNG_VENDOR_ID = 0x04d8
        const val TRUE_RNG_PRODUCT_ID = 0xf5fe

        fun buildPermissionIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(
                context,
                2001,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
        }
    }
}
