package pe.gob.onpe.votodigital.cifrador.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPhase2InstrumentationTest {

    @Test
    fun smokeTestCargaJniYEjecutaOperacionesBasicas() {
        val result = AndroidPhase2Runner().runNativeSmokeTest()

        assertTrue(result.message, result.hasVecj)
        assertTrue(result.message, result.hasVmgj)
        assertTrue(result.message, result.curveCount > 0)
        assertEquals(result.message, 1, result.legendreValue)
    }
}
