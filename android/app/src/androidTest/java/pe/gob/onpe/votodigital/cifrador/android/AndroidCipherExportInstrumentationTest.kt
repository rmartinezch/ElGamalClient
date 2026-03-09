package pe.gob.onpe.votodigital.cifrador.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCipherExportInstrumentationTest {

    @Test
    fun cifraVotosRealesYExportaCiphertexts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = AndroidCipherRunner(context).encryptSandboxInputs()

        assertTrue(result.message, result.success)
        assertTrue(result.message, result.outputFile.isFile)
        assertTrue(result.message, result.byteCount > 0)
        assertTrue(result.message, result.lineCount > 0)
    }
}
