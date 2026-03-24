package pe.gob.onpe.votodigital.cifrador.android;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.verificatum.crypto.RandomSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest;
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode;
import pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService;
import pe.gob.onpe.votodigital.elgamalcipher.LogConfig;

public final class AndroidCipherRunner {

    public static final String INPUT_DIR_NAME = "inputs";
    public static final String EXPORT_DIR_NAME = "exports";
    public static final String LOG_DIR_NAME = "logs";
    public static final String PUBLIC_KEY_FILE_NAME = "publicKey";
    public static final String VOTES_FILE_NAME = "shuffled_votes.txt";
    public static final String CIPHERTEXTS_FILE_NAME = "ciphertexts_ext";
    public static final String LOG_FILE_NAME = "android-cifrador.log";

    private final Context context;
    private final AndroidTrueRngSupport trueRngSupport;

    public AndroidCipherRunner(Context context) {
        this.context = context.getApplicationContext();
        this.trueRngSupport = new AndroidTrueRngSupport(this.context);
    }

    public CipherExecutionResult encryptSandboxInputs() {
        return encryptSandboxInputs(CifradorRngMode.SOFTWARE);
    }

    public CipherExecutionResult encryptSandboxInputs(CifradorRngMode rngMode) {
        File inputDir = new File(context.getFilesDir(), INPUT_DIR_NAME);
        File exportDir = new File(context.getFilesDir(), EXPORT_DIR_NAME);
        File logDir = new File(context.getFilesDir(), LOG_DIR_NAME);
        File publicKeyFile = new File(inputDir, PUBLIC_KEY_FILE_NAME);
        File votesFile = new File(inputDir, VOTES_FILE_NAME);
        File ciphertextsFile = new File(exportDir, CIPHERTEXTS_FILE_NAME);

        requireReadable(publicKeyFile, "publicKey");
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
                true,
                true,
                true,
                true,
                true
        );

        CifradorRequest request = new CifradorRequest(
                publicKeyFile.toPath(),
                votesFile.toPath(),
                ciphertextsFile.toPath(),
                rngMode,
                false
        );

        boolean success = rngMode == CifradorRngMode.HARDWARE
                ? encryptWithAndroidHardware(request, logger)
                : new ElGamalCipherService(logger).encrypt(request);
        long byteCount = ciphertextsFile.isFile() ? ciphertextsFile.length() : 0L;
        int lineCount = countLines(ciphertextsFile);

        StringBuilder message = new StringBuilder();
        message.append("publicKey=").append(publicKeyFile.getAbsolutePath());
        message.append("\nvotes=").append(votesFile.getAbsolutePath());
        message.append("\noutput=").append(ciphertextsFile.getAbsolutePath());
        message.append("\nlog=").append(logFile.getAbsolutePath());
        message.append("\nresultado=").append(success ? "OK" : "ERROR");
        message.append(" bytes=").append(byteCount);
        message.append(" lineas=").append(lineCount);

        return new CipherExecutionResult(
                success && byteCount > 0 && lineCount > 0,
                ciphertextsFile,
                logFile,
                byteCount,
                lineCount,
                message.toString()
        );
    }

    public String describeTrueRngStatus() {
        return trueRngSupport.scanStatus().getMessage();
    }

    public ImportedInput importPublicKey(Uri sourceUri) {
        return importIntoSandbox(sourceUri, PUBLIC_KEY_FILE_NAME, "publicKey");
    }

    public ImportedInput importVotes(Uri sourceUri) {
        return importIntoSandbox(sourceUri, VOTES_FILE_NAME, VOTES_FILE_NAME);
    }

    public ExportResult exportCiphertexts(Uri targetUri) throws IOException {
        File exportDir = new File(context.getFilesDir(), EXPORT_DIR_NAME);
        File ciphertextsFile = new File(exportDir, CIPHERTEXTS_FILE_NAME);
        requireReadable(ciphertextsFile, CIPHERTEXTS_FILE_NAME);

        try (OutputStream output = openExportOutputStream(targetUri)) {
            if (output == null) {
                throw new IOException("No se pudo abrir el destino de exportacion: " + targetUri);
            }
            try (InputStream input = Files.newInputStream(ciphertextsFile.toPath())) {
                copyStreams(input, output);
            }
        }

        return new ExportResult(
                ciphertextsFile,
                ciphertextsFile.length(),
                "ciphertexts_ext exportado desde " + ciphertextsFile.getAbsolutePath()
        );
    }

    public File currentPublicKeyFile() {
        return new File(new File(context.getFilesDir(), INPUT_DIR_NAME), PUBLIC_KEY_FILE_NAME);
    }

    public File currentVotesFile() {
        return new File(new File(context.getFilesDir(), INPUT_DIR_NAME), VOTES_FILE_NAME);
    }

    private boolean encryptWithAndroidHardware(CifradorRequest request, Logger logger) {
        RandomSource hardwareSource = null;
        try {
            hardwareSource = trueRngSupport.openRandomSource(logger);
            return new ElGamalCipherService(logger).encrypt(request, hardwareSource);
        } finally {
            if (hardwareSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Throwable error) {
                    logger.log(Level.FINE, "No se pudo cerrar el TrueRNG Android tras el cifrado.", error);
                }
            }
        }
    }

    private void requireReadable(File file, String label) {
        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Falta archivo de entrada " + label + " en " + file.getAbsolutePath()
            );
        }
        if (!file.canRead()) {
            throw new IllegalStateException(
                    "No se puede leer " + label + " en " + file.getAbsolutePath()
            );
        }
    }

    private ImportedInput importIntoSandbox(Uri sourceUri, String targetName, String label) {
        File inputDir = new File(context.getFilesDir(), INPUT_DIR_NAME);
        File targetFile = new File(inputDir, targetName);
        inputDir.mkdirs();
        String sourceDescription = describeSourceUri(sourceUri);

        try (InputStream input = context.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) {
                throw new IOException("No se pudo abrir " + label + " desde " + sourceUri);
            }
            try (OutputStream output = Files.newOutputStream(targetFile.toPath())) {
                copyStreams(input, output);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }

        return new ImportedInput(
                targetFile,
                targetFile.length(),
                label + " importado desde " + sourceDescription + "\ncopiado a " + targetFile.getAbsolutePath()
        );
    }

    private OutputStream openExportOutputStream(Uri targetUri) throws IOException {
        IOException firstError = null;

        try {
            OutputStream output = context.getContentResolver().openOutputStream(targetUri, "w");
            if (output != null) {
                return output;
            }
        } catch (IOException error) {
            firstError = error;
        }

        try {
            OutputStream output = context.getContentResolver().openOutputStream(targetUri);
            if (output != null) {
                return output;
            }
        } catch (IOException error) {
            if (firstError == null) {
                firstError = error;
            } else {
                firstError.addSuppressed(error);
            }
        }

        IOException finalError = new IOException(
                "El proveedor Android no permitio abrir el destino de exportacion: " + targetUri
        );
        if (firstError != null) {
            finalError.addSuppressed(firstError);
        }
        throw finalError;
    }

    private void copyStreams(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private String describeSourceUri(Uri sourceUri) {
        try (Cursor cursor = context.getContentResolver().query(
                sourceUri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    String displayName = cursor.getString(nameIndex);
                    if (displayName != null && !displayName.isBlank()) {
                        return displayName + " (" + sourceUri + ")";
                    }
                }
            }
        }
        return sourceUri.toString();
    }

    private int countLines(File file) {
        if (!file.isFile()) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException error) {
            return 0;
        }
    }

    public static final class CipherExecutionResult {
        private final boolean success;
        private final File outputFile;
        private final File logFile;
        private final long byteCount;
        private final int lineCount;
        private final String message;

        public CipherExecutionResult(
                boolean success,
                File outputFile,
                File logFile,
                long byteCount,
                int lineCount,
                String message
        ) {
            this.success = success;
            this.outputFile = Objects.requireNonNull(outputFile, "outputFile");
            this.logFile = Objects.requireNonNull(logFile, "logFile");
            this.byteCount = byteCount;
            this.lineCount = lineCount;
            this.message = Objects.requireNonNull(message, "message");
        }

        public boolean getSuccess() {
            return success;
        }

        public File getOutputFile() {
            return outputFile;
        }

        public File getLogFile() {
            return logFile;
        }

        public long getByteCount() {
            return byteCount;
        }

        public int getLineCount() {
            return lineCount;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class ImportedInput {
        private final File targetFile;
        private final long byteCount;
        private final String message;

        public ImportedInput(File targetFile, long byteCount, String message) {
            this.targetFile = Objects.requireNonNull(targetFile, "targetFile");
            this.byteCount = byteCount;
            this.message = Objects.requireNonNull(message, "message");
        }

        public File getTargetFile() {
            return targetFile;
        }

        public long getByteCount() {
            return byteCount;
        }

        public String getMessage() {
            return message;
        }
    }

    public static final class ExportResult {
        private final File exportedFile;
        private final long byteCount;
        private final String message;

        public ExportResult(File exportedFile, long byteCount, String message) {
            this.exportedFile = Objects.requireNonNull(exportedFile, "exportedFile");
            this.byteCount = byteCount;
            this.message = Objects.requireNonNull(message, "message");
        }

        public File getExportedFile() {
            return exportedFile;
        }

        public long getByteCount() {
            return byteCount;
        }

        public String getMessage() {
            return message;
        }
    }
}
