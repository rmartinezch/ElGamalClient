package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
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
        ElGamalEncoder encoder = new ElGamalEncoder(publicKey);

        /*
        String[][] vectorVotes = new String[][]{
            {"010210"},
            {"010312"},
            {"010414"},
            {"010516"},
            {"010618"},
            {"020618"},
            {"030720"},
//            {"01", "02", "10"},
//            {"01", "03", "12"},
//            {"01", "04", "14"},
//            {"01", "05", "16"},
//            {"01", "06", "18"},
//            {"02", "06", "18"},
//            {"03", "07", "20"},
        };
//        */
        
        // Lectura de votos simples o vectoriales desde el archivo de votos en texto plano, considerando el número de llaves
        String[][] vectorVotes = Tools.readVectorVotesFromFile(mainPath + "shuffled_votes.txt", publicKey.getNumberOfKeys());

        // Contenedores de votos codificados, vectoriales y simples
        PGroupElement[] encodedVectorVotes = new PGroupElement[vectorVotes.length];
        for (int i = 0; i < encodedVectorVotes.length; i++) {
            System.out.println("Vector Voto [" + i + "]: " + Arrays.toString(vectorVotes[i]));
            encodedVectorVotes[i] = encoder.encodeVote(vectorVotes[i]);
        }

        // Cifrar el voto
        ElGamalCipheredVote[] cipheredVotes = new ElGamalCipheredVote[vectorVotes.length];
        RandomSource randomSource = new RandomDevice();
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        for (int i = 0; i < cipheredVotes.length; i++) {
            cipheredVotes[i] = cipher.encryptVote(encodedVectorVotes[i], randomSource);
        }

        // lectura de votos cifrados
        for (ElGamalCipheredVote cipheredVote : cipheredVotes) {
//            System.out.println(cipheredVote.toString());
            System.out.println(cipheredVote.toHexString());
        }

        // serializar
        String outputPath = mainPath + "ciphertexts_ext";
        Tools.serialize(cipheredVotes, outputPath);
    }
}