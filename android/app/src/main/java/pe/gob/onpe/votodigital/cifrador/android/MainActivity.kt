package pe.gob.onpe.votodigital.cifrador.android

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val resultText = findViewById<TextView>(R.id.resultText)
        val runCheckButton = findViewById<Button>(R.id.runCheckButton)
        val runner = AndroidPhase2Runner()

        runCheckButton.setOnClickListener {
            runCheckButton.isEnabled = false
            resultText.text = "Ejecutando prueba JNI..."
            Thread {
                val check = runner.runNativeSmokeTest()
                runOnUiThread {
                    resultText.text = check.message
                    runCheckButton.isEnabled = true
                }
            }.start()
        }
    }
}
