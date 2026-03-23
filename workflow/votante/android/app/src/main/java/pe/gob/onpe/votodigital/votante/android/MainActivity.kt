package pe.gob.onpe.votodigital.votante.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pe.gob.onpe.votodigital.cifrador.android.AndroidCipherLibraryInfo
import pe.gob.onpe.votodigital.cifrador.android.AndroidCipherRunner

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var infoButton: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.voterWebView)
        infoButton = findViewById(R.id.infoButton)
        val cipherRunner = AndroidCipherRunner(applicationContext)
        val voterBridge = AndroidVoterBridge(applicationContext, cipherRunner)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(voterBridge, "AndroidBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
        infoButton.setOnClickListener {
            showBuildInfoDialog()
        }
        webView.loadUrl(VOTER_APP_URL)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
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
        }.trim()
    }

    companion object {
        private const val VOTER_APP_URL = "file:///android_asset/votante/index.html"
    }
}
