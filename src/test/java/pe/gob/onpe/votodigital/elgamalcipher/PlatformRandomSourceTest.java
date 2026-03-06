package pe.gob.onpe.votodigital.elgamalcipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class PlatformRandomSourceTest {

    @Test
    void producesRequestedNumberOfBytes() {
        PlatformRandomSource source = new PlatformRandomSource();
        byte[] bytes = source.getBytes(32);

        assertEquals(32, bytes.length);
        assertFalse(isAllZero(bytes), "SecureRandom no debe devolver un bloque nulo completo");
    }

    @Test
    void serializesHumanReadableDescription() throws Exception {
        PlatformRandomSource source = new PlatformRandomSource();
        String serialized = ByteTree.byteTreeToString((ByteTree) source.toByteTree());

        assertTrue(serialized.contains("PlatformRandomSource"));
        assertTrue(serialized.contains(RuntimePlatform.current().classifier()));
    }

    @Test
    void softwareFactoryUsesPortableRandomSource() {
        RandomSource source = RandomSourceFactory.create(false, Logger.getAnonymousLogger());

        assertInstanceOf(PlatformRandomSource.class, source);
    }

    private boolean isAllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
