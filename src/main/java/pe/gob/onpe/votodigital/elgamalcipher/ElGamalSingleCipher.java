package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PRing;
import com.verificatum.arithm.PRingElement;
import com.verificatum.crypto.RandomSource;

/**
 *
 * @author rmartinezch
 */
public class ElGamalSingleCipher {

    private final ECqPGroup eCqPGroup;
    private final PGroupElement g;
    private final PGroupElement y;

    public ElGamalSingleCipher(ElGamalSinglePublicKey publicKey) {
        this.eCqPGroup = publicKey.getECqGroup();
        this.g = publicKey.getG();
        this.y = publicKey.getY();
    }

    public ElGamalCipheredVote encrypt(PGroupElement codedMessage, RandomSource randomSource) {
        // Obtener el anillo asociado al grupo
        PRing pRing = eCqPGroup.getPRing();
        // Generar elemento aleatorio r en el anillo con 256 bits de seguridad
        PRingElement r = pRing.randomElement(randomSource, 256);
        // Calcular el primer componente del cifrado: g^r
        PGroupElement c1 = g.exp(r);
        // Calcular el segundo componente del cifrado: m * (y^r)
        PGroupElement yr = y.exp(r);
        PGroupElement c2 = codedMessage.mul(yr);
        // Retornar el par cifrado
        return new ElGamalCipheredVote(c1, c2);
    }
}