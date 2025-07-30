package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PPGroupElement;
import com.verificatum.arithm.PRing;
import com.verificatum.arithm.PRingElement;
import com.verificatum.crypto.RandomSource;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCipher {

    private final boolean vectorial;
    private final ECqPGroup ecqGroup;
    private final PPGroup ppGroup;
    private final PGroupElement g;
    private final PGroupElement y;

    /**
     *
     * @param group
     * @param g
     * @param y
     */
    public ElGamalCipher(PGroup group, PGroupElement g, PGroupElement y) {
        if (group instanceof ECqPGroup) {
            this.vectorial = false;
            this.ecqGroup = (ECqPGroup) group;
            this.ppGroup = null;
        } else if (group instanceof PPGroup) {
            this.vectorial = true;
            this.ppGroup = (PPGroup) group;
            this.ecqGroup = null;
        } else {
            throw new IllegalArgumentException("El grupo no es ECqPGroup ni PPGroup: " + group.getClass().getName());
        }
        this.g = g;
        this.y = y;
    }

    /**
     *
     * @param message
     * @param randomSource
     * @return
     */
    public ElGamalCipheredText encryptSingle(PGroupElement message, RandomSource randomSource) {
        if (vectorial) {
            throw new IllegalStateException("Modo vectorial activo. Use encryptVector().");
        }

        PRing ring = ecqGroup.getPRing();
        PRingElement r = ring.randomElement(randomSource, 256);

        PGroupElement c1 = g.exp(r);
        PGroupElement c2 = message.mul(y.exp(r));

        return new ElGamalCipheredText(c1, c2);
    }

    /**
     *
     * @param messageVec
     * @param randomSource
     * @return
     */
    public ElGamalCipheredText encryptVector(PGroupElement messageVec, RandomSource randomSource) {
        if (!vectorial) {
            throw new IllegalStateException("Modo simple activo. Use encryptSingle().");
        }
        if (!(messageVec instanceof PPGroupElement)) {
            throw new IllegalArgumentException("El mensaje debe ser PPGroupElement en modo vectorial.");
        }

        PRing ring = ppGroup.getPRing();
        PRingElement r = ring.randomElement(randomSource, 256);

        PGroupElement c1 = g.exp(r);
        PGroupElement c2 = messageVec.mul(y.exp(r));

        return new ElGamalCipheredText(c1, c2);
    }
}