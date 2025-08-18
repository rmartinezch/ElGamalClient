package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 *
 * @author rmartinezch
 */
public class ElGamalMain {

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("""
                               El n\u00famero de argumentos es 3, as\u00ed:
                               java -jar ElGamalClient public_Key_file_name plain_votes_file_name ciphered_votes_file_name""");
            return;
        }

        String mainPath = Paths.get("").toAbsolutePath().toString() + File.separator;
        System.out.println("Directorio actual: " + mainPath);

        // Input files exists?
        File[] files = new File[args.length];
        for (int i = 0; i < args.length; i++) {
            files[i] = new File(mainPath + args[i]);
            System.out.println("Parámetro " + (i + 1) + ": " + files[i].getAbsolutePath());
            if (!files[i].exists() && (i != args.length - 1)) {
                System.out.println("No existe el archivo: " + files[i].getAbsolutePath());
                return;
            }
        }

        // Read ElGamal public key
        ElGamalPublicKey publicKey = new ElGamalPublicKey(files[0].getAbsolutePath());
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        // Encoder initialization
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
        
        // Reading of vectorial or simples votes from plain votes file, and it considers the number of inner keys
        String[][] vectorVotes = Tools.readVectorVotesFromFile(files[1].getAbsolutePath(), publicKey.getNumberOfKeys());
        if (vectorVotes == null) {
            System.out.println("Los votos en texto plano no han podido ser leidos.");
            return;
        }

        // Read plain votes in RAM
        /*
        for (int i = 0; i < vectorVotes.length; i++) {
            System.out.println("Vector Voto [" + (i + 1) + "]: " + Arrays.toString(vectorVotes[i]));
        }
//        */

        // Containers of (vectorial or simple) coded votes
        PGroupElement[] encodedVectorVotes = new PGroupElement[vectorVotes.length];
        for (int i = 0; i < encodedVectorVotes.length; i++) {
            encodedVectorVotes[i] = encoder.encodeVote(vectorVotes[i]);
        }

        // Read coded votes
        /*
        for (int i = 0; i < encodedVectorVotes.length; i++) {
            System.out.println("Voto codificado [" + (i+1) + "]: " + encodedVectorVotes[i].toByteTree().toHexString());
        }
//        */

        // Encrypt votes
        ElGamalCipheredVote[] cipheredVotes = new ElGamalCipheredVote[vectorVotes.length];
        RandomSource randomSource = new RandomDevice();
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        for (int i = 0; i < cipheredVotes.length; i++) {
            cipheredVotes[i] = cipher.encryptVote(encodedVectorVotes[i], randomSource);
        }

        // Read ciphered votes
/*
        for (ElGamalCipheredVote cipheredVote : cipheredVotes) {
//            System.out.println(cipheredVote.toString());
            System.out.println(cipheredVote.toHexString());
        }
//         */

        // Serialize ciphered votes
        Tools.serialize(cipheredVotes, files[2].getAbsolutePath());
    }
}