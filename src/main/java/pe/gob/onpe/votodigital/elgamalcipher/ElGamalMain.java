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
import java.util.concurrent.atomic.AtomicInteger;
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
            true, true, true, true, true
    );
    private static final String HARDWARE_RNG_DEVICE = "/dev/TrueRNG0";
    private static final String APP_VERSION = "1.1.0";

    public static void main(String[] args) {
        logger.info(() -> String.format("Iniciando ElGamalCipher v%s", APP_VERSION));
        logger.info(() -> "Iniciamos la lectura de parámetros en la ejecución");
        if (args.length < 4 || args.length > 5) {
            printUsage();
            return;
        }

        String mainPath = Paths.get("").toAbsolutePath().toString() + File.separator;
        logger.info(() -> String.format("Directorio actual: %s", mainPath));

        File[] files = validateFiles(mainPath, args);
        if (files.length == 0) {
            return;
        }

        boolean trueRNG;
        if ("-hw".equals(args[3])) {
            trueRNG = true;
        } else if ("-sw".equals(args[3])) {
            trueRNG = false;
        } else {
            logger.warning(() -> String.format("Cuarto parámetro inválido: %s", args[3]));
            return;
        }

        boolean showProgressBar = (args.length == 5 && "-p".equals(args[4]));
        
        processCiphering(files, trueRNG, showProgressBar);
    }

    private static void printUsage() {
        logger.warning(() -> String.format("""
                           El n\u00famero de argumentos es 4 o 5, as\u00ed:
                           java -jar ElGamalCipher-1.0-SNAPSHOT-jar-with-dependencies.jar public_Key_file_name plain_votes_file_name ciphered_votes_file_name -(hw/sw) [-p]"""));
    }

    private static File[] validateFiles(String mainPath, String[] args) {
        File[] files = new File[3]; 
        for (int i = 0; i < 3; i++) {
            files[i] = new File(mainPath + args[i]);
            final int index = i;
            logger.info(() -> String.format("Parámetro %d : %s", (index + 1), files[index].getAbsoluteFile()));
            // Check existence for input files (0: publicKey, 1: plainVotes), but not for output (2)
            if (!files[i].exists() && (i != 2)) {
                logger.severe(() -> String.format("No existe el archivo: %s", files[index].getAbsolutePath()));
                return new File[0];
            }
        }
        return files;
    }

    private static void processCiphering(File[] files, boolean trueRNG, boolean showProgressBar) {
        // Read ElGamal public key
        ElGamalPublicKey publicKey = new ElGamalPublicKey(files[0].getAbsolutePath());
        if (!publicKey.isLoaded()) {
            return;
        }
        logger.info(publicKey::toString);

        // Encoder initialization
        ElGamalEncoder encoder = new ElGamalEncoder(publicKey);
        logger.info(() -> "Iniciando codificaci\u00f3n de votos en paralelo...");

        String[][] vectorVotes = Tools.readVectorVotesFromFile(files[1].getAbsolutePath(), publicKey.getNumberOfKeys());
        if (vectorVotes.length == 0) {
            logger.severe(() -> "No se pudieron leer votos (archivo inexistente, vacío o con formato inválido).");
            return;
        }

        PGroupElement[] encodedVectorVotes = Arrays.stream(vectorVotes)
                .parallel()
                .map(encoder::encodeVote)
                .toArray(PGroupElement[]::new);

        logger.info(() -> "Codificaci\u00f3n finalizada. Iniciando cifrado...");

        encryptAndSave(files[2], encodedVectorVotes, publicKey, trueRNG, showProgressBar);
    }

    private static void encryptAndSave(File outputFile, PGroupElement[] encodedVectorVotes, ElGamalPublicKey publicKey, boolean trueRNG, boolean showProgressBar) {
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        AtomicInteger progressCounter = new AtomicInteger(0);
        int totalVotes = encodedVectorVotes.length;
        
        Thread progressThread = null;
        if (showProgressBar) {
            progressThread = startProgressBar(progressCounter, totalVotes);
        }

        ElGamalCipheredVote[] cipheredVotes;
        if (trueRNG) {
            cipheredVotes = encryptWithHardware(cipher, encodedVectorVotes, progressCounter, showProgressBar);
        } else {
            cipheredVotes = encryptWithSoftware(cipher, encodedVectorVotes, progressCounter, showProgressBar);
        }

        stopProgressBar(progressThread);
        
        logger.info(() -> "Cifrado finalizado.");
        Tools.serialize(cipheredVotes, outputFile.getAbsolutePath());
    }

    private static Thread startProgressBar(AtomicInteger progressCounter, int totalVotes) {
        return Thread.ofVirtual().start(() -> {
            try {
                // NOSONAR: "always evaluates to true" is false here for the interrupted check pattern
                while (!Thread.currentThread().isInterrupted()) { 
                    updateProgress(progressCounter.get(), totalVotes);
                    if (progressCounter.get() >= totalVotes) break;
                    Thread.sleep(100); 
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        });
    }

    private static void updateProgress(int current, int totalVotes) {
        int percent = (int) ((current * 100.0) / totalVotes);
        StringBuilder bar = new StringBuilder("Cifrando: [");
        int bars = percent / 2;
        for (int i = 0; i < 50; i++) {
            bar.append(i < bars ? "=" : " ");
        }
        bar.append("] ").append(percent).append("%\r");
        System.out.print(bar.toString()); // NOSONAR: System.out is required for console progress bar updates
    }

    private static void stopProgressBar(Thread progressThread) {
        if (progressThread != null) {
            try {
                progressThread.interrupt();
                progressThread.join();
                System.out.println(); // NOSONAR: System.out used for CLI formatting
            } catch (InterruptedException ex) {
                logger.warning(ex::toString);
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ElGamalCipheredVote[] encryptWithHardware(ElGamalCipher cipher, PGroupElement[] votes, AtomicInteger counter, boolean track) {
        logger.info(() -> "Modo Hardware RNG detectado: Usando procesamiento secuencial.");
        RandomSource hwRng = selectRandomSource(true);
        return Arrays.stream(votes)
                .map(vote -> {
                    ElGamalCipheredVote v = cipher.encryptVote(vote, hwRng);
                    if (track) counter.incrementAndGet();
                    return v;
                })
                .toArray(ElGamalCipheredVote[]::new);
    }

    private static ElGamalCipheredVote[] encryptWithSoftware(ElGamalCipher cipher, PGroupElement[] votes, AtomicInteger counter, boolean track) {
        logger.info(() -> "Modo Software RNG detectado: Usando procesamiento paralelo.");
        ThreadLocal<RandomSource> threadLocalRng = ThreadLocal.withInitial(() -> selectRandomSource(false));
        return Arrays.stream(votes)
                .parallel()
                .map(vote -> {
                    ElGamalCipheredVote v = cipher.encryptVote(vote, threadLocalRng.get());
                    if (track) counter.incrementAndGet();
                    return v;
                })
                .toArray(ElGamalCipheredVote[]::new);
    }

    private static RandomSource selectRandomSource(boolean rngOption) {
        RandomSource rngSource;
        if (rngOption) {
            File rngDevice = new File(HARDWARE_RNG_DEVICE);
            if (!rngDevice.exists()) {
                logger.severe(() -> String.format("El dispositivo de hardware no responde: %s", rngDevice.getAbsolutePath()));
                logger.warning(() -> "Se seleccionó el dispositivo de Software por defecto");
                return new RandomDevice();
            }
            rngSource = new RandomDevice(rngDevice);
        } else {
            rngSource = new RandomDevice();
        }

        logger.info(() -> String.format("randomSource.toByteTree().toHexString(): %s", rngSource.toByteTree().toHexString()));

        ByteTreeBasic btb = rngSource.toByteTree();
        if (btb instanceof ByteTree bt) {
            try {
                String deviceFromByteTree = ByteTree.byteTreeToString(bt);
                logger.info(() -> String.format("deviceFromByteTree: %s", deviceFromByteTree));
            } catch (EIOException ex) {
                logger.severe(() -> String.format("La ubicación del dispositivo no puede ser obtenida desde el ByteTree:%n%s", ex.toString()));
            }
        } else {
            logger.warning(() -> "El objeto no puede ser convertido a un ByteTree.");
        }
        return rngSource;
    }
}
