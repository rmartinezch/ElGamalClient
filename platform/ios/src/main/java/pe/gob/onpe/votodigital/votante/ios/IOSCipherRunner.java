package pe.gob.onpe.votodigital.votante.ios;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest;
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode;
import pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService;
import pe.gob.onpe.votodigital.elgamalcipher.LogConfig;

public final class IOSCipherRunner {

    private static final String INPUT_DIR_NAME = "inputs";
    private static final String EXPORT_DIR_NAME = "exports";
    private static final String LOG_DIR_NAME = "logs";
    private static final String PUBLIC_KEY_FILE_NAME = "publicKey";
    private static final String VOTES_FILE_NAME = "plain_votes.txt";
    private static final String CIPHERTEXTS_FILE_NAME = "ciphertexts_ext";
    private static final String LOG_FILE_NAME = "ios-cifrador.log";

    private final File dataDir;

    public IOSCipherRunner(File dataDir) {
        this.dataDir = dataDir;
    }

    public File getDataDir() {
        return dataDir;
    }

    public CipherResult encryptSandboxInputs(CifradorRngMode rngMode) {
        File inputDir = new File(dataDir, INPUT_DIR_NAME);
        File exportDir = new File(dataDir, EXPORT_DIR_NAME);
        File logDir = new File(dataDir, LOG_DIR_NAME);
        File publicKeyFile = new File(inputDir, PUBLIC_KEY_FILE_NAME);
        File votesFile = new File(inputDir, VOTES_FILE_NAME);
        File ciphertextsFile = new File(exportDir, CIPHERTEXTS_FILE_NAME);

        requireReadable(publicKeyFile, PUBLIC_KEY_FILE_NAME);
        requireReadable(votesFile, VOTES_FILE_NAME);

        exportDir.mkdirs();
        logDir.mkdirs();
        if (ciphertextsFile.exists()) {
            ciphertextsFile.delete();
        }

        File logFile = new File(logDir, LOG_FILE_NAME);
        if (logFile.exists()) {
            logFile.delete();
        }

        Logger logger = LogConfig.getLogger(
                logFile.getAbsolutePath(),
                Level.INFO,
                true, true, true, true, true
        );

        CifradorRequest request = new CifradorRequest(
                publicKeyFile.toPath(),
                votesFile.toPath(),
                ciphertextsFile.toPath(),
                rngMode,
                false
        );

        boolean success = new ElGamalCipherService(logger).encrypt(request);
        long byteCount = ciphertextsFile.isFile() ? ciphertextsFile.length() : 0L;
        int lineCount = countLines(ciphertextsFile);

        return new CipherResult(
                success && byteCount > 0 && lineCount > 0,
                ciphertextsFile,
                logFile,
                byteCount,
                lineCount
        );
    }

    public File currentPublicKeyFile() {
        return new File(new File(dataDir, INPUT_DIR_NAME), PUBLIC_KEY_FILE_NAME);
    }

    public File currentVotesFile() {
        return new File(new File(dataDir, INPUT_DIR_NAME), VOTES_FILE_NAME);
    }

    private void requireReadable(File file, String label) {
        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Falta archivo de entrada " + label + " en " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IllegalStateException(
                    "No se puede leer " + label + " en " + file.getAbsolutePath());
        }
    }

    private int countLines(File file) {
        if (!file.isFile()) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException e) {
            return 0;
        }
    }

    public record CipherResult(boolean success, File outputFile, File logFile,
                                long byteCount, int lineCount) {
    }
}
