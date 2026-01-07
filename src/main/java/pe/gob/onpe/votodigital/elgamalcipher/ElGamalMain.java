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
            true
    );
    
    public static void main(String[] args) {
        logger.info(() -> "Iniciamos la lectura de parámetros en la ejecución");
        if (args.length < 4 || args.length > 5) {
            logger.warning(() -> String.format("""
                               El n\u00famero de argumentos es 4 o 5, as\u00ed:
                               java -jar ElGamalCipher-1.0-SNAPSHOT-jar-with-dependencies.jar public_Key_file_name plain_votes_file_name ciphered_votes_file_name -(hw/sw) [-p]"""));
            return;
        }

        String mainPath = Paths.get("").toAbsolutePath().toString() + File.separator;
        logger.info(() -> String.format("Directorio actual: %s", mainPath));

        // Input files exists?
        File[] files = new File[3]; // Fixed size for the 3 main files
        for (int i = 0; i < 3; i++) {
            files[i] = new File(mainPath + args[i]);
            final int index = i;
            logger.info(() -> String.format("Parámetro %d %s %s", (index + 1), ":", files[index].getAbsoluteFile()));
            // Check existence for input files (0: publicKey, 1: plainVotes), but not for output file (2: cipheredVotes)
            if (!files[i].exists() && (i != 2)) {
                logger.severe(() -> String.format("No existe el archivo: %s", files[index].getAbsolutePath()));
                return;
            }
        }

        // Select the Random Number Generator of HW/SW
        boolean trueRNG;
        switch (args[3]) {
            case "-hw" -> trueRNG = true;
            case "-sw" -> trueRNG = false;
            default -> {
                logger.warning(() -> String.format("Cuarto parámetro inválido: %s", args[args.length - 1]));
                return;
            }
        }
        
        final boolean showProgressBar = (args.length == 5 && "-p".equals(args[4]));

        // Read ElGamal public key
        ElGamalPublicKey publicKey = new ElGamalPublicKey(files[0].getAbsolutePath());
        if (!publicKey.isLoaded()) {
            return;
        }
        logger.info(publicKey::toString);

        // Encoder initialization
        ElGamalEncoder encoder = new ElGamalEncoder(publicKey);
        logger.info(() -> "Iniciando codificaci\u00f3n de votos en paralelo...");
        
        // Reading of vectorial or simples votes from plain votes file, and it considers the number of inner keys
        String[][] vectorVotes = Tools.readVectorVotesFromFile(files[1].getAbsolutePath(), publicKey.getNumberOfKeys());
        if (vectorVotes.length == 0) {
            logger.severe(() -> "No se pudieron leer votos (archivo inexistente, vacío o con formato inválido).");
            return;
        }

        // Containers of (vectorial or simple) coded votes
        PGroupElement[] encodedVectorVotes = Arrays.stream(vectorVotes)
                .parallel()
                .map(encoder::encodeVote)
                .toArray(PGroupElement[]::new);

        logger.info(() -> "Codificaci\u00f3n finalizada. Iniciando cifrado en paralelo...");

        // Select RNG device
        // Use ThreadLocal to give each thread its own RandomSource, avoiding synchronization bottlenecks
        ThreadLocal<RandomSource> threadLocalRng = ThreadLocal.withInitial(() -> selectRandomSource(trueRNG));

        // Encrypt votes
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        
        java.util.concurrent.atomic.AtomicInteger progressCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        int totalVotes = encodedVectorVotes.length;
        Thread progressThread = null;
        
        if (showProgressBar) {
            progressThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        int current = progressCounter.get();
                        int percent = (int) ((current * 100.0) / totalVotes);
                        StringBuilder bar = new StringBuilder("[");
                        int bars = percent / 2; // 50 chars for 100%
                        for (int i = 0; i < 50; i++) {
                            if (i < bars) bar.append("=");
                            else bar.append(" ");
                        }
                        bar.append("] ").append(percent).append("%\r");
                        System.out.print(bar.toString());
                        
                        if (current >= totalVotes) break;
                        Thread.sleep(200); // 5 updates per second
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            progressThread.start();
        }

        ElGamalCipheredVote[] cipheredVotes = Arrays.stream(encodedVectorVotes)
                .parallel()
                .map(vote -> {
                    ElGamalCipheredVote v = cipher.encryptVote(vote, threadLocalRng.get());
                    if (showProgressBar) progressCounter.incrementAndGet();
                    return v;
                })
                .toArray(ElGamalCipheredVote[]::new);
        
        if (showProgressBar && progressThread != null) {
            try {
                progressThread.interrupt();
                progressThread.join();
                System.out.println("\n"); // New line after progress bar
            } catch (InterruptedException ex) {
                logger.warning(ex::toString);
            }
        }
        
        logger.info(() -> "Cifrado finalizado.");

        // Serialize ciphered votes
        Tools.serialize(cipheredVotes, files[2].getAbsolutePath());
    }
    
    /**
     * Select the RNG by hardware or software
     * @param rngOption True if it is hardware, otherwise False
     * @return the RNG device
     */
    private static RandomSource selectRandomSource(boolean rngOption) {
        // Integration of the Random Number Generator of HW/SW
        String device;
        File rngDevice;
        RandomSource rngSource;
        if (rngOption) {
            device = "/dev/TrueRNG0";                   // Linux device where the USB hardware generator is indexed
            rngDevice = new File(device);
            if (!rngDevice.exists()) {
                logger.severe(() -> String.format("El dispositivo de hardware no responde: %s", rngDevice.getAbsolutePath()));
                logger.warning(() -> "Se seleccionó el dispositivo de Software por defecto");
                return new RandomDevice();              // Return default Software device /dev/urandom
            }
            rngSource = new RandomDevice(rngDevice);    // Representation of the device in the Verificatum format
        } else {
            rngSource = new RandomDevice();             // Default software device /dev/urandom
        }
        // From randomSource.toByteTree().toHexString() we know that randomSource has a ByteTree leaf
        logger.info(() -> String.format("randomSource.toByteTree().toHexString(): %s", rngSource.toByteTree().toHexString()));

        // Get ByteTreeBasic representation
        ByteTreeBasic btb = rngSource.toByteTree();
        // Explicit cast to ByteTree
        if (!(btb instanceof ByteTree)) {
            logger.warning(() -> "El objeto no puede ser convertido a un ByteTree.");
        } else {
            ByteTree bt = (ByteTree) btb;
            try {
                String deviceFromByteTree = ByteTree.byteTreeToString(bt);
                logger.info(() -> String.format("deviceFromByteTree: %s", deviceFromByteTree));
            } catch (EIOException ex) {
                logger.severe(() -> String.format("La ubicación del dispositivo no puede ser obtenida desde el ByteTree:\n%s", ex.toString()));
            }
        }
        return rngSource;
    }
}