package pe.gob.onpe.votodigital.cifrador.android;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidCipherExportInstrumentationTest {

    @Test
    public void cifraVotosRealesYExportaCiphertexts() {
        Context context = ApplicationProvider.getApplicationContext();
        AndroidCipherRunner.CipherExecutionResult result =
                new AndroidCipherRunner(context).encryptSandboxInputs();

        assertTrue(result.getMessage(), result.getSuccess());
        assertTrue(result.getMessage(), result.getOutputFile().isFile());
        assertTrue(result.getMessage(), result.getByteCount() > 0);
        assertTrue(result.getMessage(), result.getLineCount() > 0);
    }
}

