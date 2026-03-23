package pe.gob.onpe.votodigital.truerngdiag

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pe.gob.onpe.votodigital.cifrador.android.AndroidCipherRunner
import pe.gob.onpe.votodigital.cifrador.android.AndroidCipherLibraryInfo
import pe.gob.onpe.votodigital.cifrador.android.AndroidPhase2Runner
import pe.gob.onpe.votodigital.cifrador.android.AndroidTrueRngSupport
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var diagnostics: TrueRngUsbDiagnostics
    private lateinit var cipherRunner: AndroidCipherRunner
    private lateinit var reportText: TextView
    private lateinit var scanUsbButton: Button
    private lateinit var requestPermissionButton: Button
    private lateinit var readSampleButton: Button
    private lateinit var runStabilityButton: Button
    private lateinit var exportReportButton: Button
    private lateinit var exportCiphertextsButton: Button
    private lateinit var infoButton: Button
    private lateinit var runJniCheckButton: Button
    private lateinit var loadSampleResourcesButton: Button
    private lateinit var encryptSampleButton: Button
    private lateinit var rngModeSpinner: Spinner
    private lateinit var stabilityDurationSpinner: Spinner
    private var lastReport: String = "Sin ejecutar"
    private val rngModeOptions = listOf(
        RngModeOption(CifradorRngMode.SOFTWARE, "sw / SecureRandom"),
        RngModeOption(CifradorRngMode.HARDWARE, "hw / TrueRNG")
    )
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

    private val createCiphertextsDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            appendReport("Exportacion de ciphertexts cancelada.")
            return@registerForActivityResult
        }
        try {
            val result = cipherRunner.exportCiphertexts(uri)
            appendReport("${result.message}\nbytes=${result.byteCount}\ndestino=${uri}")
        } catch (t: Throwable) {
            appendReport("Error exportando ciphertexts_ext: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) {
                return
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            appendReport(diagnostics.buildPermissionResult(device, granted))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagnostics = TrueRngUsbDiagnostics(applicationContext)
        cipherRunner = AndroidCipherRunner(applicationContext)
        reportText = findViewById(R.id.reportText)
        scanUsbButton = findViewById(R.id.scanUsbButton)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)
        readSampleButton = findViewById(R.id.readSampleButton)
        runStabilityButton = findViewById(R.id.runStabilityButton)
        exportReportButton = findViewById(R.id.exportReportButton)
        exportCiphertextsButton = findViewById(R.id.exportCiphertextsButton)
        infoButton = findViewById(R.id.infoButton)
        runJniCheckButton = findViewById(R.id.runJniCheckButton)
        loadSampleResourcesButton = findViewById(R.id.loadSampleResourcesButton)
        encryptSampleButton = findViewById(R.id.encryptSampleButton)
        rngModeSpinner = findViewById(R.id.rngModeSpinner)
        stabilityDurationSpinner = findViewById(R.id.stabilityDurationSpinner)

        stabilityDurationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            stabilityOptions.map { it.label }
        )
        rngModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rngModeOptions.map { it.label }
        )

        registerUsbPermissionReceiver()
        updateReport("Sin ejecutar")

        scanUsbButton.setOnClickListener {
            val result = diagnostics.scan()
            updateReport(result.report)
        }

        requestPermissionButton.setOnClickListener {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                1001,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            appendReport(diagnostics.requestPermission(pendingIntent))
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

        exportCiphertextsButton.setOnClickListener {
            createCiphertextsDocument.launch(defaultCiphertextsFileName())
        }

        infoButton.setOnClickListener {
            showBuildInfoDialog()
        }

        runJniCheckButton.setOnClickListener {
            setBusy(true)
            appendReport("Ejecutando prueba JNI desde el AAR.")
            Thread {
                val resultText = try {
                    AndroidPhase2Runner().runNativeSmokeTest().message
                } catch (t: Throwable) {
                    "Error inesperado en prueba JNI: ${t.message ?: t.javaClass.simpleName}"
                }
                runOnUiThread {
                    appendReport(resultText)
                    setBusy(false)
                }
            }.start()
        }

        loadSampleResourcesButton.setOnClickListener {
            setBusy(true)
            appendReport("Cargando recursos de prueba empaquetados en el APK.")
            Thread {
                val resultText = try {
                    loadBundledSandboxResources()
                } catch (t: Throwable) {
                    "Error cargando recursos de prueba: ${t.message ?: t.javaClass.simpleName}"
                }
                runOnUiThread {
                    appendReport(resultText)
                    setBusy(false)
                }
            }.start()
        }

        encryptSampleButton.setOnClickListener {
            val option = rngModeOptions[rngModeSpinner.selectedItemPosition]
            setBusy(true)
            appendReport("Iniciando cifrado de prueba con modo ${option.label}.")
            Thread {
                val resultText = try {
                    ensureBundledSandboxResources()
                    cipherRunner.encryptSandboxInputs(option.mode).message
                } catch (t: Throwable) {
                    "Error en cifrado de prueba: ${t.message ?: t.javaClass.simpleName}"
                }
                runOnUiThread {
                    appendReport(resultText)
                    setBusy(false)
                }
            }.start()
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

    private fun defaultCiphertextsFileName(): String {
        return "ciphertexts_ext-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
    }

    private fun setBusy(busy: Boolean) {
        scanUsbButton.isEnabled = !busy
        requestPermissionButton.isEnabled = !busy
        readSampleButton.isEnabled = !busy
        runStabilityButton.isEnabled = !busy
        exportReportButton.isEnabled = !busy
        exportCiphertextsButton.isEnabled = !busy
        infoButton.isEnabled = !busy
        runJniCheckButton.isEnabled = !busy
        loadSampleResourcesButton.isEnabled = !busy
        encryptSampleButton.isEnabled = !busy
        rngModeSpinner.isEnabled = !busy
        stabilityDurationSpinner.isEnabled = !busy
    }

    private fun showBuildInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Info de build")
            .setMessage(buildInfoMessage())
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun buildInfoMessage(): String {
        return buildString {
            appendLine("Interfaz")
            appendLine("Nombre: ${BuildConfig.INTERFACE_DISPLAY_NAME}")
            appendLine("Version: ${BuildConfig.VERSION_NAME}")
            appendLine("Fecha/Hora build: ${BuildConfig.INTERFACE_BUILD_TIMESTAMP}")
            appendLine()
            appendLine("Cifrador Android")
            appendLine("Version: ${AndroidCipherLibraryInfo.versionName}")
            appendLine("Fecha/Hora build: ${AndroidCipherLibraryInfo.buildTimestamp}")
            appendLine("RNG soportado: ${AndroidCipherLibraryInfo.rngSupport}")
            appendLine()
            appendLine("Recursos de prueba")
            appendLine("publicKey: recursos/publicKey")
            appendLine("votes: recursos/shuffled_votes.txt")
        }.trim()
    }

    private fun ensureBundledSandboxResources() {
        val publicKeyFile = cipherRunner.currentPublicKeyFile()
        val votesFile = cipherRunner.currentVotesFile()
        if (publicKeyFile.isFile && votesFile.isFile) {
            return
        }
        loadBundledSandboxResources()
    }

    private fun loadBundledSandboxResources(): String {
        val publicKeyBytes = assets.open("recursos/publicKey").use { it.readBytes() }
        val votesBytes = assets.open("recursos/shuffled_votes.txt").use { it.readBytes() }

        val publicKeyFile = writeSandboxFile(cipherRunner.currentPublicKeyFile(), publicKeyBytes)
        val votesFile = writeSandboxFile(cipherRunner.currentVotesFile(), votesBytes)

        return buildString {
            appendLine("Recursos de prueba cargados.")
            appendLine("publicKey=${publicKeyFile.absolutePath} bytes=${publicKeyFile.length()}")
            appendLine("votes=${votesFile.absolutePath} bytes=${votesFile.length()}")
        }.trimEnd()
    }

    private fun writeSandboxFile(target: File, bytes: ByteArray): File {
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            output.write(bytes)
        }
        return target
    }

    companion object {
        private const val ACTION_USB_PERMISSION = AndroidTrueRngSupport.ACTION_USB_PERMISSION
        private const val TAG = "TrueRngDiagActivity"
    }

    data class StabilityOption(
        val seconds: Int,
        val label: String
    )

    data class RngModeOption(
        val mode: CifradorRngMode,
        val label: String
    )
}
