package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PPGroupElement;
import java.util.Arrays;

/**
 *
 * @author rmartinezch
 */
public class VectorMain {

    public static void main(String[] args) {
        System.out.println("Leyendo la llave pública ElGamal.");
        String mainPath = "/home/rmartinezch/verificatum/eleccion06/01/";
        String publicKeyName = "publicKey";
        ElGamalVectorPublicKey publicKey = new ElGamalVectorPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        ElGamalVectorEncoder vectorEncoder = new ElGamalVectorEncoder(publicKey.getPPGroup());

        String[][] votes = new String[][]{
            {"01", "02", "10"},
            {"01", "03", "12"},
            {"01", "04", "14"},
            {"01", "05", "16"},
            {"01", "06", "18"},
            {"01", "07", "20"},
        };

        PPGroupElement[] encodedVotes = new PPGroupElement[votes.length];
        for (int i = 0; i < encodedVotes.length; i++) {
            System.out.println("Vector Voto [" + i + "]: " + Arrays.toString(votes[i]));
            encodedVotes[i] = vectorEncoder.encodeVector(votes[i]);
        }
    }
}
