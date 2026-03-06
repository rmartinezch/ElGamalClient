package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.crypto.CryptoFormatException;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.ByteTreeReader;
import com.verificatum.eio.EIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Portable random source backed by the JVM SecureRandom provider.
 */
public final class PlatformRandomSource extends RandomSource {

    private final SecureRandom secureRandom;

    public PlatformRandomSource() {
        this(new SecureRandom());
    }

    private PlatformRandomSource(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public static PlatformRandomSource newInstance(ByteTreeReader btr)
            throws CryptoFormatException {
        try {
            if (btr.getRemaining() > 0) {
                btr.readString();
            }
            return new PlatformRandomSource();
        } catch (EIOException e) {
            throw new CryptoFormatException("No se pudo reconstruir PlatformRandomSource.", e);
        }
    }

    @Override
    public void getBytes(byte[] array) {
        secureRandom.nextBytes(array);
    }

    @Override
    public ByteTreeBasic toByteTree() {
        return new ByteTree(humanDescription(false).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String humanDescription(boolean verbose) {
        RuntimePlatform platform = RuntimePlatform.current();
        return "PlatformRandomSource(" + secureRandom.getAlgorithm()
                + "," + platform.classifier() + ")";
    }
}
