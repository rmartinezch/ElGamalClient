package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PRing;
import com.verificatum.arithm.PRingElement;
import com.verificatum.crypto.RandomSource;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCipher {

    PGroup group;
    PGroupElement gToX;

    public ElGamalCipher(ElGamalPublicKey publicKey) {
        this.group = publicKey.getGroup();
        this.gToX = publicKey.getgToX();
    }

    public ElGamalCipheredText encrypt(PGroupElement codedMessage, RandomSource randomSource) {
        // Paso 1: Obtener el anillo asociado al grupo
        PRing pRing = group.getPRing();
        // Paso 2: Generar elemento aleatorio r en el anillo con 128 bits de seguridad
        PRingElement r = pRing.randomElement(randomSource, 128);
        // Paso 3: Calcular el primer componente del cifrado: g^r
        PGroupElement g = group.getg();
        PGroupElement c1 = g.exp(r);
        // Paso 4: Calcular el segundo componente del cifrado: m * (y^r)
        PGroupElement y = gToX;
        PGroupElement yr = y.exp(r);
        PGroupElement c2 = codedMessage.mul(yr);
        System.out.println("c1: " + c1.toByteTree().toHexString());
        System.out.println("c2: " + c2.toByteTree().toHexString());
        // Paso 5: Retornar el par cifrado
        return new ElGamalCipheredText(c1, c2);
    }
}