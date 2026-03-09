package pe.gob.onpe.votodigital.elgamalcipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CifradorRngModeTest {

    @Test
    void parsesCliFlags() {
        assertEquals(CifradorRngMode.SOFTWARE, CifradorRngMode.fromCliFlag("-sw").orElseThrow());
        assertEquals(CifradorRngMode.HARDWARE, CifradorRngMode.fromCliFlag("-hw").orElseThrow());
        assertTrue(CifradorRngMode.fromCliFlag("-x").isEmpty());
    }

    @Test
    void reportsHardwareIntent() {
        assertFalse(CifradorRngMode.SOFTWARE.hardwareRequested());
        assertTrue(CifradorRngMode.HARDWARE.hardwareRequested());
    }
}
