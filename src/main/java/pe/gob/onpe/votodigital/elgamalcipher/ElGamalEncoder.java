package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class encode a message into a coded message, which owns to a defined group.
 * @author rmartinezch
 */
public class ElGamalEncoder {

    private final boolean vectorial;
    private final ECqPGroup ecqGroup;
    private final PPGroup ppGroup;
    private final PGroup[] baseGroups;
    private final int numberOfKeys;
    
    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );

    public ElGamalEncoder(ElGamalPublicKey publicKey) {
        this.vectorial = publicKey.isVectorial();
        if (this.vectorial) {
            this.ecqGroup = null;
            this.ppGroup = publicKey.getPPGroup();
            this.baseGroups = publicKey.getBaseGroups();
            logger.info(() -> "La llave ElGamal es vectorial.");
        } else {
            this.ecqGroup = publicKey.getECqGroup();
            this.ppGroup = null;
            this.baseGroups = null;
            logger.info(() -> "La llave ElGamal es simple.");
        }
        this.numberOfKeys = publicKey.getNumberOfKeys();
    }

    /**
     * Encode a vote
     * @param vectorVote
     * @return
     */
    public PGroupElement encodeVote(String[] vectorVote) {
        if (vectorVote.length != numberOfKeys) {
            throw new IllegalArgumentException("Número de campos (" + vectorVote.length + ") no coincide con keywidth (" + numberOfKeys + ").");
        }

        PGroupElement[] encodedElements = new PGroupElement[vectorVote.length];
        for (int i = 0; i < vectorVote.length; i++) {
            ECqPGroup g;
            if (vectorial) {
                g = (ECqPGroup) baseGroups[i];
            } else {
                g = ecqGroup;
            }
            byte[] msgBytes = vectorVote[i].getBytes(StandardCharsets.UTF_8);
            if (msgBytes.length > g.getEncodeLength()) {
                throw new IllegalArgumentException("Componente " + i + " demasiado largo para codificar.");
            }
            encodedElements[i] = g.encode(msgBytes, 0, msgBytes.length);
        }

        PGroupElement vectorElement;
        if (vectorial) {
            vectorElement = ppGroup.product(encodedElements);
        } else {
            vectorElement = encodedElements[0];
        }
        return vectorElement;
    }

}