package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.EIOException;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalMain {

    private static final Logger logger = LogConfig.getLogger(
            "./logs/sistema.log",
            Level.INFO,
            true,
            true,
            true,
            true,
            1024 * 1024,
            3,
            true
    );
    
    public static void main(String[] args) {
        logger.info("Iniciamos la lectura de parámetros en la ejecución");
        if (args.length != 4) {
            logger.warning("""
                               El n\u00famero de argumentos es 4, as\u00ed:
                               java -jar ElGamalCipher-1.0-SNAPSHOT-jar-with-dependencies.jar public_Key_file_name plain_votes_file_name ciphered_votes_file_name -(hw/sw)""");
            return;
        }

        String mainPath = Paths.get("").toAbsolutePath().toString() + File.separator;
        logger.info("Directorio actual: " + mainPath);

        // Input files exists?
        File[] files = new File[args.length];
        for (int i = 0; i < args.length - 1; i++) {
            files[i] = new File(mainPath + args[i]);
            logger.info("Parámetro " + (i + 1) + ": " + files[i].getAbsolutePath());
            if (!files[i].exists() && (i != args.length - 2)) {
                logger.severe("No existe el archivo: " + files[i].getAbsolutePath());
                return;
            }
        }

        // Select the Random Number Generator of HW/SW
        boolean trueRNG;
        switch (args[3]) {
            case "-hw" -> trueRNG = true;
            case "-sw" -> trueRNG = false;
            default -> {
                logger.warning("Cuarto parámetro inválido: " + args[args.length - 1]);
                return;
            }
        }

        // Read ElGamal public key
        ElGamalPublicKey publicKey = new ElGamalPublicKey(files[0].getAbsolutePath());
        if (!publicKey.isLoaded()) {
            return;
        }
        logger.info(publicKey.toString());

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
            logger.severe("Los votos en texto plano no han podido ser leidos.");
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
        
        // Integration of the Random Number Generator of HW/SW
//        boolean trueRNG = true;
        String device;
        File rngDevice;
        RandomSource randomSource;
        if (trueRNG) {
            device = "/dev/TrueRNG0";                   // Dispositivo de linux donde está indexado el generador de hardware USB
            rngDevice = new File(device);
            if (!rngDevice.exists()) {
                logger.severe("El dispositivo no responde: " + rngDevice.getAbsolutePath());
                return;
            }
            randomSource = new RandomDevice(rngDevice); // Representación del dispositivo en el formato de Verificatum
        } else {
            randomSource = new RandomDevice();          // por defecto /dev/urandom
        }
        logger.info("randomSource.toByteTree().toHexString(): " + randomSource.toByteTree().toHexString());

        // From randomSource.toByteTree().toHexString() we know that randomSource has a ByteTree leaf
        // Get ByteTreeBasic representation
        ByteTreeBasic btb = randomSource.toByteTree();
        // Explicit cast to ByteTree
        if (!(btb instanceof ByteTree)) {
            throw new IllegalStateException("El objeto no es un ByteTree.");
        }
        ByteTree bt = (ByteTree) btb;
        try {
            String deviceFromByteTree = ByteTree.byteTreeToString(bt);
            logger.info("deviceFromByteTree: " + deviceFromByteTree);
        } catch (EIOException ex) {
            Logger.getLogger(ElGamalSingleMain.class.getName()).log(Level.SEVERE, null, ex);
        }

        // Encrypt votes
        ElGamalCipheredVote[] cipheredVotes = new ElGamalCipheredVote[vectorVotes.length];
//        RandomSource randomSource = new RandomDevice();
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