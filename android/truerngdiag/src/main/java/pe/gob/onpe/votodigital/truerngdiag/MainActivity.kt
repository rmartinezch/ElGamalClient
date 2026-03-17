package pe.gob.onpe.votodigital.truerngdiag

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var diagnostics: TrueRngUsbDiagnostics
    private lateinit var reportText: TextView
    private lateinit var scanUsbButton: Button
    private lateinit var requestPermissionButton: Button
    private lateinit var readSampleButton: Button
    private lateinit var runStabilityButton: Button
    private lateinit var exportReportButton: Button
    private lateinit var stabilityDurationSpinner: Spinner
    private var lastReport: String = "Sin ejecutar"
    private val stabilityOptions = listOf(
        StabilityOption(15, "15 segundos"),
        StabilityOption(30, "30 segundos"),
        StabilityOption(60, "60 segundos"),
        StabilityOption(120, "120 segundos")
    )

    private val createReportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            appendReport("Exportacion cancelada.")
            return@registerForActivityResult
        }
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(lastReport.toByteArray())
            }
            appendReport("Reporte guardado correctamente.")
        } catch (t: Throwable) {
            appendReport("Error guardando reporte: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) {
                return
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val deviceId = device?.deviceId ?: -1
            appendReport(diagnostics.buildPermissionResult(deviceId, granted))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagnostics = TrueRngUsbDiagnostics(applicationContext)
        reportText = findViewById(R.id.reportText)
        scanUsbButton = findViewById(R.id.scanUsbButton)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        readSampleButton = findViewById(R.id.readSampleButton)
        runStabilityButton = findViewById(R.id.runStabilityButton)
        exportReportButton = findViewById(R.id.exportReportButton)
        stabilityDurationSpinner = findViewById(R.id.stabilityDurationSpinner)

        stabilityDurationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            stabilityOptions.map { it.label }
        )

        registerUsbPermissionReceiver()
        updateReport("Sin ejecutar")

        scanUsbButton.setOnClickListener {
            val result = diagnostics.scan()
            updateReport(result.report)
        }

        requestPermissionButton.setOnClickListener {
            val permissionResult = diagnostics.requestPermissionForFirstSupportedDevice()
            if (!permissionResult.startsWith("REQUEST:")) {
                appendReport(permissionResult)
                return@setOnClickListener
            }
            val deviceId = permissionResult.removePrefix("REQUEST:").toIntOrNull()
            val device = if (deviceId == null) null else diagnostics.getDeviceById(deviceId)
            if (device == null) {
                appendReport("No se encontro el dispositivo para pedir permiso USB.")
                return@setOnClickListener
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                1001,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            getSystemService(UsbManager::class.java).requestPermission(device, pendingIntent)
            appendReport("Solicitud de permiso enviada para deviceId=${device.deviceId}.")
        }

        readSampleButton.setOnClickListener {
            setBusy(true)
            appendReport("Iniciando lectura de muestra de 4096 bytes.")
            Thread {
                var resultText: String
                try {
                    resultText = diagnostics.readSample().report
                } catch (t: Throwable) {
                    resultText = "Error inesperado en lectura de muestra: ${t.message ?: t.javaClass.simpleName}"
                }
                runOnUiThread {
                    appendReport(resultText)
                    setBusy(false)
                }
            }.start()
        }

        runStabilityButton.setOnClickListener {
            val option = stabilityOptions[stabilityDurationSpinner.selectedItemPosition]
            setBusy(true)
            appendReport("Iniciando prueba de estabilidad por ${option.seconds} segundos.")
            Thread {
                var resultText: String
                try {
                    resultText = diagnostics.runStabilityProbe(option.seconds).report
                } catch (t: Throwable) {
                    resultText = "Error inesperado en prueba de estabilidad: ${t.message ?: t.javaClass.simpleName}"
                }
                runOnUiThread {
                    appendReport(resultText)
                    setBusy(false)
                }
            }.start()
        }

        exportReportButton.setOnClickListener {
            createReportDocument.launch(defaultReportFileName())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSafe()
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun unregisterReceiverSafe() {
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "El receiver USB ya estaba desregistrado.", e)
        }
    }

    private fun updateReport(text: String) {
        lastReport = text
        reportText.text = text
    }

    private fun appendReport(text: String) {
        val stamped = buildString {
            append(lastReport)
            append("\n\n[")
            append(timestamp())
            append("] ")
            append(text)
        }
        updateReport(stamped)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun defaultReportFileName(): String {
        return "truerng-diagnostico-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
    }

    private fun setBusy(busy: Boolean) {
        scanUsbButton.isEnabled = !busy
        requestPermissionButton.isEnabled = !busy
        readSampleButton.isEnabled = !busy
        runStabilityButton.isEnabled = !busy
        exportReportButton.isEnabled = !busy
        stabilityDurationSpinner.isEnabled = !busy
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "pe.gob.onpe.votodigital.truerngdiag.USB_PERMISSION"
        private const val TAG = "TrueRngDiagActivity"
    }

    data class StabilityOption(
        val seconds: Int,
        val label: String
    )
}
