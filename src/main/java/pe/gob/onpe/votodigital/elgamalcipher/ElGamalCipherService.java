package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.EIOException;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Reusable ElGamal cipher orchestrator independent from CLI parsing.
 */
public final class ElGamalCipherService {

    private final Logger logger;

    public ElGamalCipherService(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean encrypt(CifradorRequest request) {
        Objects.requireNonNull(request, "request");

        Path workingDirectory = new File("").toPath().toAbsolutePath().normalize();
        logger.info(() -> String.format("Directorio actual: %s", workingDirectory));

        File[] files = validateFiles(request);
        if (files.length == 0) {
            return false;
        }

        processCiphering(files,
                request.rngMode().hardwareRequested(),
                request.showProgressBar());
        return true;
    }

    private File[] validateFiles(CifradorRequest request) {
        File[] files = new File[3];
        Path[] paths = {
            request.publicKeyPath(),
            request.plainVotesPath(),
            request.cipheredVotesPath()
        };

        for (int i = 0; i < paths.length; i++) {
            final int index = i;
            if (paths[i] == null) {
                logger.severe(() -> String.format("Parámetro %d no puede ser nulo", index + 1));
                return new File[0];
            }
            files[i] = paths[i].toAbsolutePath().normalize().toFile();
            logger.info(() -> String.format("Parámetro %d : %s", (index + 1), files[index].getAbsoluteFile()));
            // Check existence for input files (0: publicKey, 1: plainVotes), but not for output (2)
            if (!files[i].exists() && (i != 2)) {
                logger.severe(() -> String.format("No existe el archivo: %s", files[index].getAbsolutePath()));
                return new File[0];
            }
        }
        return files;
    }

    private void processCiphering(File[] files, boolean trueRNG, boolean showProgressBar) {
        ElGamalPublicKey publicKey = new ElGamalPublicKey(files[0].getAbsolutePath());
        if (!publicKey.isLoaded()) {
            return;
        }
        logger.info(publicKey::toString);

        ElGamalEncoder encoder = new ElGamalEncoder(publicKey);
        logger.info(() -> "Iniciando codificación de votos en paralelo...");

        String[][] vectorVotes = Tools.readVectorVotesFromFile(files[1].getAbsolutePath(), publicKey.getNumberOfKeys());
        if (vectorVotes.length == 0) {
            logger.severe(() -> "No se pudieron leer votos (archivo inexistente, vacío o con formato inválido).");
            return;
        }

        PGroupElement[] encodedVectorVotes = Arrays.stream(vectorVotes)
                .parallel()
                .map(encoder::encodeVote)
                .toArray(PGroupElement[]::new);

        logger.info(() -> "Codificación finalizada. Iniciando cifrado...");

        encryptAndSave(files[2], encodedVectorVotes, publicKey, trueRNG, showProgressBar);
    }

    private void encryptAndSave(File outputFile,
                                PGroupElement[] encodedVectorVotes,
                                ElGamalPublicKey publicKey,
                                boolean trueRNG,
                                boolean showProgressBar) {
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

    private Thread startProgressBar(AtomicInteger progressCounter, int totalVotes) {
        Thread thread = new Thread(() -> {
            try {
                // NOSONAR: interrupted check pattern used intentionally
                while (!Thread.currentThread().isInterrupted()) {
                    updateProgress(progressCounter.get(), totalVotes);
                    if (progressCounter.get() >= totalVotes) {
                        break;
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "cifrador-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void updateProgress(int current, int totalVotes) {
        int percent = (int) ((current * 100.0) / totalVotes);
        StringBuilder bar = new StringBuilder("Cifrando: [");
        int bars = percent / 2;
        for (int i = 0; i < 50; i++) {
            bar.append(i < bars ? "=" : " ");
        }
        bar.append("] ").append(percent).append("%\r");
        System.out.print(bar.toString()); // NOSONAR: required for CLI progress bar
    }

    private void stopProgressBar(Thread progressThread) {
        if (progressThread != null) {
            try {
                progressThread.interrupt();
                progressThread.join();
                System.out.println(); // NOSONAR: CLI formatting
            } catch (InterruptedException ex) {
                logger.warning(ex::toString);
                Thread.currentThread().interrupt();
            }
        }
    }

    private ElGamalCipheredVote[] encryptWithHardware(ElGamalCipher cipher,
                                                       PGroupElement[] votes,
                                                       AtomicInteger counter,
                                                       boolean track) {
        RandomSource hwRng = selectRandomSource(true);
        return Arrays.stream(votes)
                .map(vote -> {
                    ElGamalCipheredVote v = cipher.encryptVote(vote, hwRng);
                    if (track) {
                        counter.incrementAndGet();
                    }
                    return v;
                })
                .toArray(ElGamalCipheredVote[]::new);
    }

    private ElGamalCipheredVote[] encryptWithSoftware(ElGamalCipher cipher,
                                                       PGroupElement[] votes,
                                                       AtomicInteger counter,
                                                       boolean track) {
        ThreadLocal<RandomSource> threadLocalRng = ThreadLocal.withInitial(() -> selectRandomSource(false));
        return Arrays.stream(votes)
                .parallel()
                .map(vote -> {
                    ElGamalCipheredVote v = cipher.encryptVote(vote, threadLocalRng.get());
                    if (track) {
                        counter.incrementAndGet();
                    }
                    return v;
                })
                .toArray(ElGamalCipheredVote[]::new);
    }

    private RandomSource selectRandomSource(boolean hardwareRequested) {
        RandomSource rngSource = RandomSourceFactory.create(hardwareRequested, logger);

        logger.info(() -> String.format("randomSource.toByteTree().toHexString(): %s", rngSource.toByteTree().toHexString()));

        ByteTreeBasic btb = rngSource.toByteTree();
        if (btb instanceof ByteTree bt) {
            try {
                String deviceFromByteTree = ByteTree.byteTreeToString(bt);
                logger.info(() -> String.format("deviceFromByteTree: %s", deviceFromByteTree));
            } catch (EIOException ex) {
                logger.severe(() -> String.format("La ubicación del dispositivo no puede ser obtenida desde el ByteTree:%n%s", ex));
            }
        } else {
            logger.warning(() -> "El objeto no puede ser convertido a un ByteTree.");
        }
        return rngSource;
    }
}
