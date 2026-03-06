package pe.gob.onpe.votodigital.elgamalcipher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ElGamalMainIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsWithAbsolutePaths() throws IOException {
        Path inputVotes = tempDir.resolve("votes.txt");
        Path outputVotes = tempDir.resolve("ciphered.txt");
        Files.writeString(inputVotes, "ABCDEF\n");

        ElGamalMain.main(new String[]{
            Path.of("recursos/publicKey").toAbsolutePath().toString(),
            inputVotes.toAbsolutePath().toString(),
            outputVotes.toAbsolutePath().toString(),
            "-sw"
        });

        assertCipherFile(outputVotes);
    }

    @Test
    void encryptsWithRelativePaths() throws IOException {
        Path relativeDir = Path.of("target", "test-data");
        Files.createDirectories(relativeDir);

        Path inputVotes = relativeDir.resolve("votes-relative.txt");
        Path outputVotes = relativeDir.resolve("ciphered-relative.txt");
        Files.writeString(inputVotes, "ABCDEF\n");
        Files.deleteIfExists(outputVotes);

        ElGamalMain.main(new String[]{
            "recursos/publicKey",
            relativeDir.resolve("votes-relative.txt").toString(),
            relativeDir.resolve("ciphered-relative.txt").toString(),
            "-sw"
        });

        assertCipherFile(outputVotes);
    }

    private void assertCipherFile(Path outputVotes) throws IOException {
        assertFalse(Files.notExists(outputVotes), "No se generó el archivo cifrado");

        String content = Files.readString(outputVotes).trim();
        assertFalse(content.isBlank(), "El archivo cifrado quedó vacío");
        assertTrue(content.matches("[0-9a-f]+"),
                "La salida debe estar serializada en hexadecimal");
    }
}
