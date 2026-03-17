package pe.gob.onpe.votodigital.cifrador.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode
import java.io.File

class MainActivity : AppCompatActivity() {

    data class RngOption(
        val label: String,
        val mode: CifradorRngMode
    )

    private lateinit var runner: AndroidPhase2Runner
    private lateinit var cipherRunner: AndroidCipherRunner
    private lateinit var trueRngSupport: AndroidTrueRngSupport
    private lateinit var jniResultText: TextView
    private lateinit var publicKeyStatusText: TextView
    private lateinit var votesStatusText: TextView
    private lateinit var trueRngStatusText: TextView
    private lateinit var cipherResultText: TextView
    private lateinit var runCheckButton: Button
    private lateinit var pickPublicKeyButton: Button
    private lateinit var pickVotesButton: Button
    private lateinit var requestTrueRngPermissionButton: Button
    private lateinit var encryptButton: Button
    private lateinit var exportButton: Button
    private lateinit var rngModeSpinner: Spinner
    private var lastCipherOutputAvailable = false
    private val rngOptions = listOf(
        RngOption("Software RNG (-sw)", CifradorRngMode.SOFTWARE),
        RngOption("TrueRNG USB (-hw)", CifradorRngMode.HARDWARE)
    )

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AndroidTrueRngSupport.ACTION_USB_PERMISSION) {
                return
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = readUsbDeviceExtra(intent)
            trueRngStatusText.text = buildString {
                append(trueRngSupport.buildPermissionResult(device, granted))
                append("\n")
                append(cipherRunner.describeTrueRngStatus())
            }
        }
    }

    private val openPublicKeyDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        importDocument(
            uri = uri,
            importer = { selectedUri -> cipherRunner.importPublicKey(selectedUri) },
            targetView = publicKeyStatusText,
            emptyMessage = "No se selecciono publicKey."
        )
    }

    private val openVotesDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        importDocument(
            uri = uri,
            importer = { selectedUri -> cipherRunner.importVotes(selectedUri) },
            targetView = votesStatusText,
            emptyMessage = "No se selecciono archivo de votos."
        )
    }

    private val createCiphertextsDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) {
            cipherResultText.append("\nExportacion cancelada.")
            return@registerForActivityResult
        }
        try {
            val exportResult = cipherRunner.exportCiphertexts(uri)
            cipherResultText.text = buildString {
                append(cipherResultText.text)
                append("\n")
                append(exportResult.message)
                append("\nbytes=")
                append(exportResult.byteCount)
            }
        } catch (e: Throwable) {
            cipherResultText.text = "Error exportando ciphertexts_ext:\n${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        runner = AndroidPhase2Runner()
        cipherRunner = AndroidCipherRunner(applicationContext)
        trueRngSupport = AndroidTrueRngSupport(applicationContext)

        jniResultText = findViewById(R.id.jniResultText)
        publicKeyStatusText = findViewById(R.id.publicKeyStatusText)
        votesStatusText = findViewById(R.id.votesStatusText)
        trueRngStatusText = findViewById(R.id.trueRngStatusText)
        cipherResultText = findViewById(R.id.cipherResultText)
        runCheckButton = findViewById(R.id.runCheckButton)
        pickPublicKeyButton = findViewById(R.id.pickPublicKeyButton)
        pickVotesButton = findViewById(R.id.pickVotesButton)
        requestTrueRngPermissionButton = findViewById(R.id.requestTrueRngPermissionButton)
        encryptButton = findViewById(R.id.encryptButton)
        exportButton = findViewById(R.id.exportButton)
        rngModeSpinner = findViewById(R.id.rngModeSpinner)

        rngModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rngOptions.map { it.label }
        )

        registerUsbPermissionReceiver()
        renderImportedFileStatus()
        renderTrueRngStatus()

        runCheckButton.setOnClickListener {
            runCheckButton.isEnabled = false
            jniResultText.text = "Ejecutando prueba JNI..."
            Thread {
                val check = runner.runNativeSmokeTest()
                runOnUiThread {
                    jniResultText.text = check.message
                    runCheckButton.isEnabled = true
                }
            }.start()
        }

        pickPublicKeyButton.setOnClickListener {
            openPublicKeyDocument.launch(arrayOf("*/*"))
        }

        pickVotesButton.setOnClickListener {
            openVotesDocument.launch(arrayOf("text/plain", "*/*"))
        }

        requestTrueRngPermissionButton.setOnClickListener {
            val permissionRequest = trueRngSupport.requestPermission(
                AndroidTrueRngSupport.buildPermissionIntent(this)
            )
            trueRngStatusText.text = buildString {
                append(permissionRequest.message)
                append("\n")
                append(cipherRunner.describeTrueRngStatus())
            }
        }

        encryptButton.setOnClickListener {
            executeCipher()
        }

        exportButton.setOnClickListener {
            if (!lastCipherOutputAvailable) {
                cipherResultText.text = "Primero debes ejecutar el cifrado."
                return@setOnClickListener
            }
            createCiphertextsDocument.launch("ciphertexts_ext")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbPermissionReceiver()
    }

    private fun executeCipher() {
        if (!cipherRunner.currentPublicKeyFile().isFile || !cipherRunner.currentVotesFile().isFile) {
            cipherResultText.text = "Debes importar publicKey y votos antes de cifrar."
            return
        }

        val selectedMode = rngOptions[rngModeSpinner.selectedItemPosition].mode
        setBusy(true)
        cipherResultText.text = when (selectedMode) {
            CifradorRngMode.SOFTWARE -> "Cifrando en Android con SecureRandom..."
            CifradorRngMode.HARDWARE -> "Cifrando en Android con TrueRNG USB..."
        }

        Thread {
            var cipherResult: AndroidCipherRunner.CipherExecutionResult? = null
            var failure: Throwable? = null

            try {
                cipherResult = cipherRunner.encryptSandboxInputs(selectedMode)
            } catch (e: Throwable) {
                failure = e
            }

            runOnUiThread {
                when {
                    cipherResult != null -> {
                        lastCipherOutputAvailable = cipherResult.success
                        exportButton.isEnabled = cipherResult.success
                        cipherResultText.text = cipherResult.message
                    }
                    failure != null -> {
                        lastCipherOutputAvailable = false
                        exportButton.isEnabled = false
                        cipherResultText.text = "Error ejecutando cifrado:\n${failure.message ?: failure.javaClass.simpleName}"
                    }
                }
                setBusy(false)
            }
        }.start()
    }

    private fun importDocument(
        uri: Uri?,
        importer: (Uri) -> AndroidCipherRunner.ImportedInput,
        targetView: TextView,
        emptyMessage: String
    ) {
        if (uri == null) {
            targetView.text = emptyMessage
            return
        }

        try {
            val imported = importer(uri)
            targetView.text = buildString {
                append(imported.message)
                append("\nbytes=")
                append(imported.byteCount)
            }
            lastCipherOutputAvailable = false
            exportButton.isEnabled = false
            cipherResultText.text = "Entradas actualizadas. Ejecuta el cifrado."
        } catch (e: Throwable) {
            targetView.text = "Error importando archivo:\n${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun renderImportedFileStatus() {
        publicKeyStatusText.text = describeSandboxFile(
            file = cipherRunner.currentPublicKeyFile(),
            missingMessage = "publicKey no cargada."
        )
        votesStatusText.text = describeSandboxFile(
            file = cipherRunner.currentVotesFile(),
            missingMessage = "Archivo de votos no cargado."
        )
        exportButton.isEnabled = false
    }

    private fun renderTrueRngStatus() {
        trueRngStatusText.text = cipherRunner.describeTrueRngStatus()
    }

    private fun describeSandboxFile(file: File, missingMessage: String): String {
        if (!file.isFile) {
            return missingMessage
        }
        return "${file.name}\n${file.absolutePath}\nbytes=${file.length()}"
    }

    private fun setBusy(busy: Boolean) {
        runCheckButton.isEnabled = !busy
        pickPublicKeyButton.isEnabled = !busy
        pickVotesButton.isEnabled = !busy
        requestTrueRngPermissionButton.isEnabled = !busy
        rngModeSpinner.isEnabled = !busy
        encryptButton.isEnabled = !busy
        exportButton.isEnabled = !busy && lastCipherOutputAvailable
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(AndroidTrueRngSupport.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun unregisterUsbPermissionReceiver() {
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "El receiver USB ya estaba desregistrado.", e)
        }
    }

    private fun readUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val TAG = "CifradorMainActivity"
    }
}
