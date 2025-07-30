package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import java.util.Arrays;

/**
 *
 * @author rmartinezch
 */
public class ElGamalMain {

    public static void main(String[] args) {
        String mainPath = "/home/rmartinezch/verificatum/eleccion07/01/";
        String publicKeyName = "publicKey";
        ElGamalPublicKey publicKey = new ElGamalPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        // Inicialización del encoder
        ElGamalEncoder encoder;
        if (publicKey.isVectorial()) {
            encoder = new ElGamalEncoder(publicKey.getPPGroup());
        } else {
            encoder = new ElGamalEncoder(publicKey.getECqGroup());
        }

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
        
        // Lectura de votos simples o vectoriales desde el archivo de votos en texto plano, considerando el número de llaves
        String[][] vectorVotes = Tools.readVectorVotesFromFile(mainPath + "shuffled_votes.txt", publicKey.getLength());
        String[] singleVotes = Tools.readSingleVotesFromFile(mainPath + "shuffled_votes.txt");

        // Contenedores de votos codificados, vectoriales y simples
        PPGroupElement[] encodedVectorVotes = new PPGroupElement[vectorVotes.length];
        PGroupElement[] encodedSingleVotes = new PGroupElement[singleVotes.length];
        if (publicKey.isVectorial()) {
            for (int i = 0; i < encodedVectorVotes.length; i++) {
                System.out.println("Vector Voto [" + i + "]: " + Arrays.toString(vectorVotes[i]));
                encodedVectorVotes[i] = encoder.encodeVector(vectorVotes[i]);
            }
        } else {
            for (int i = 0; i < encodedSingleVotes.length; i++) {
                System.out.println("Single Voto [" + i + "]: " + (singleVotes[i]));
                encodedSingleVotes[i] = encoder.encodeSingle(singleVotes[i]);
            }
        }

        // Cifrar el voto
        ElGamalCipher cipher;
        ElGamalCipheredText[] cipheredVotes = new ElGamalCipheredText[singleVotes.length];
        RandomSource randomSource = new RandomDevice();
        if (!publicKey.isVectorial()) {
            cipher = new ElGamalCipher(publicKey.getECqGroup(), publicKey.getG(), publicKey.getY());
            for (int i = 0; i < cipheredVotes.length; i++) {
                cipheredVotes[i] = cipher.encryptSingle(encodedSingleVotes[i], randomSource);
            }
        } else {
            cipher = new ElGamalCipher(publicKey.getPPGroup(), publicKey.getG(), publicKey.getY());
            for (int i = 0; i < cipheredVotes.length; i++) {
                cipheredVotes[i] = cipher.encryptVector(encodedVectorVotes[i], randomSource);
            }
        }

        // lectura de votos cifrados
        for (ElGamalCipheredText cipheredVote : cipheredVotes) {
            System.out.println(cipheredVote.toString());
            System.out.println(cipheredVote.toHexString());
        }

        // serializar
        String outputPath = mainPath + "ciphertexts_ext";
        Tools.serialize(cipheredVotes, outputPath);
    }
}