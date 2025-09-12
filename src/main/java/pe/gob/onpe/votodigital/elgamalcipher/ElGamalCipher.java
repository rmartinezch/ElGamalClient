package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PRing;
import com.verificatum.arithm.PRingElement;
import com.verificatum.crypto.RandomSource;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class is responsible of asymmetric ciphering of votes (coded messages)
 * 
 * @author rmartinezch
 */
public class ElGamalCipher {

    private final boolean vectorial;
    private final ECqPGroup ecqGroup;
    private final PPGroup ppGroup;
    private final PGroupElement g;
    private final PGroupElement y;

    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            1024 * 1024,
            3,
            true
    );
    
    /**
     * The constructor needs a ElGamal verified public key.
     * @param publicKey ElGamal public key.
     */
    public ElGamalCipher(ElGamalPublicKey publicKey) {
        this.vectorial = publicKey.isVectorial();
        if (this.vectorial) {
            this.ecqGroup = null;
            this.ppGroup = publicKey.getPPGroup();
            logger.info(() -> String.format("La llave ElGamal es vectorial."));
        } else {
            this.ecqGroup = publicKey.getECqGroup();
            this.ppGroup = null;
            logger.info(() -> String.format("La llave ElGamal es simple."));
        }
        this.g = publicKey.getG();
        this.y = publicKey.getY();
    }

    /**
     * It receives an encoded message (vote) and encrypts it with 1) ElGamal 
     * public key and 2) HW/SW Random Number Generator.
     * @param messageVec coded message (vote).
     * @param randomSource Device which represents to the Random Number Generator.
     * @return Ciphered coded message.
     */
    public ElGamalCipheredVote encryptVote(PGroupElement messageVec, RandomSource randomSource) {
        PRing ring;
        if (vectorial) {
            ring = ppGroup.getPRing();
        } else {
            ring = ecqGroup.getPRing();
        }
        PRingElement r = ring.randomElement(randomSource, 256);

        PGroupElement c1 = g.exp(r);
        PGroupElement c2 = messageVec.mul(y.exp(r));

        return new ElGamalCipheredVote(c1, c2);
    }
}