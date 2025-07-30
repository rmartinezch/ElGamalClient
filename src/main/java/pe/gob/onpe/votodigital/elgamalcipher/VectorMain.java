package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PPGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import java.util.Arrays;

/**
 *
 * @author rmartinezch
 */
public class VectorMain {

    public static void main(String[] args) {
        String mainPath = "/home/rmartinezch/verificatum/eleccion07/01/";
        String publicKeyName = "publicKey";
        ElGamalVectorPublicKey publicKey = new ElGamalVectorPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        ElGamalVectorEncoder vectorEncoder = new ElGamalVectorEncoder(publicKey.getPPGroup());

        /*
        String[][] votes = new String[][]{
            {"01", "02", "10"},
            {"01", "03", "12"},
            {"01", "04", "14"},
            {"01", "05", "16"},
            {"01", "06", "18"},
            {"02", "06", "18"},
            {"03", "07", "20"},
        };
//        */
        // Lectura de vectores desde el archivo de votos en texto plano, considerando el número de llaves
        String[][] votes = Tools.readVectorsFromFile(mainPath + "shuffled_votes.txt", publicKey.getPPGroup().getWidth());

        PPGroupElement[] encodedVotes = new PPGroupElement[votes.length];
        for (int i = 0; i < encodedVotes.length; i++) {
            System.out.println("Vector Voto [" + i + "]: " + Arrays.toString(votes[i]));
            encodedVotes[i] = vectorEncoder.encodeVector(votes[i]);
        }

        // Cifrar el mensaje
        ElGamalVectorCipher cipher = new ElGamalVectorCipher(publicKey);
        RandomSource randomSource = new RandomDevice();
        ElGamalCipheredText[] cipheredVectorTexts = new ElGamalCipheredText[encodedVotes.length];
        for (int i = 0; i < cipheredVectorTexts.length; i++) {
            cipheredVectorTexts[i] = cipher.encrypt(encodedVotes[i], randomSource);
//            System.out.println(cipheredVectorTexts[i].toString());
            System.out.println(cipheredVectorTexts[i].toHexString());
        }

        // serializar
        String outputPath = mainPath + "ciphertexts_ext3";
        Tools.serialize(cipheredVectorTexts, outputPath);
    }
}