package pe.gob.onpe.votodigital.elgamalcipher;

import java.nio.file.Path;

/**
 * Immutable input contract for the reusable cipher service.
 */
public record CifradorRequest(
        Path publicKeyPath,
        Path plainVotesPath,
        Path cipheredVotesPath,
        CifradorRngMode rngMode,
        boolean showProgressBar
) {
}
