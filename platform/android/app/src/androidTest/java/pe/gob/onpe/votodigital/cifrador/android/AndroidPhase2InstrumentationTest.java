package pe.gob.onpe.votodigital.cifrador.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AndroidPhase2InstrumentationTest {

    @Test
    public void smokeTestCargaJniYEjecutaOperacionesBasicas() {
        AndroidPhase2Runner.RuntimeCheck result = new AndroidPhase2Runner().runNativeSmokeTest();

        assertTrue(result.getMessage(), result.getHasVecj());
        assertTrue(result.getMessage(), result.getHasVmgj());
        assertTrue(result.getMessage(), result.getCurveCount() > 0);
        assertEquals(result.getMessage(), Integer.valueOf(1), result.getLegendreValue());
    }
}

