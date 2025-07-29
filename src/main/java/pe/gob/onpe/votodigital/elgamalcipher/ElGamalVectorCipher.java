package pe.gob.onpe.votodigital.elgamalcipher;

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
public class ElGamalVectorCipher {
    private final PPGroup ppGroup;
    private final PGroupElement gVec;
    private final PGroupElement yVec;

    public ElGamalVectorCipher(ElGamalVectorPublicKey publicKey) {
        this.ppGroup = publicKey.getPPGroup();
        this.gVec = publicKey.getG();
        this.yVec = publicKey.getY();
    }
    
    public ElGamalCipheredText encrypt(PGroupElement messageVec, RandomSource randomSource) {
        if (!(messageVec instanceof PPGroupElement)) {
            throw new IllegalArgumentException("El mensaje debe ser un elemento de un grupo producto (PPGroupElement).");
        }

        // Obtener el anillo de enteros mod q (mismo para todos los factores del grupo)
        PRing ring = ppGroup.getPRing();
        PRingElement r = ring.randomElement(randomSource, 256);

        // Calcular c1 = g^r
        PGroupElement c1 = gVec.exp(r);

        // Calcular c2 = m * y^r
        PGroupElement yr = yVec.exp(r);
        PGroupElement c2 = messageVec.mul(yr);

        return new ElGamalCipheredText(c1, c2);
    }
}
