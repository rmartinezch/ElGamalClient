package pe.gob.onpe.votodigital.elgamalcipher;

import java.util.Optional;

/**
 * RNG modes exposed by the reusable cipher service.
 */
public enum CifradorRngMode {
    SOFTWARE("-sw"),
    HARDWARE("-hw");

    private final String cliFlag;

    CifradorRngMode(String cliFlag) {
        this.cliFlag = cliFlag;
    }

    public String cliFlag() {
        return cliFlag;
    }

    public boolean hardwareRequested() {
        return this == HARDWARE;
    }

    public static Optional<CifradorRngMode> fromCliFlag(String cliFlag) {
        for (CifradorRngMode mode : values()) {
            if (mode.cliFlag.equals(cliFlag)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
