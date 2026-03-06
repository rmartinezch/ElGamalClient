package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Centralizes portable random source selection for all supported platforms.
 */
public final class RandomSourceFactory {

    private static final String HARDWARE_DEVICE_PROPERTY = "elgamal.rng.device";
    private static final String HARDWARE_DEVICE_ENV = "ELGAMAL_RNG_DEVICE";
    private static final String DEFAULT_LINUX_HARDWARE_DEVICE = "/dev/TrueRNG0";

    private RandomSourceFactory() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }

    public static RandomSource create(boolean hardwareRequested, Logger logger) {
        if (!hardwareRequested) {
            logger.info(() -> "Modo Software RNG detectado: Usando SecureRandom portable.");
            return new PlatformRandomSource();
        }

        Optional<Path> hardwareDevice = resolveHardwareDevice(logger);
        if (hardwareDevice.isEmpty()) {
            logger.warning(() -> "No se encontró un dispositivo RNG por hardware válido. "
                    + "Se usará SecureRandom portable.");
            return new PlatformRandomSource();
        }

        Path device = hardwareDevice.get();
        logger.info(() -> String.format("Modo Hardware RNG detectado: usando dispositivo %s", device));
        return new RandomDevice(device.toFile());
    }

    private static Optional<Path> resolveHardwareDevice(Logger logger) {
        String configuredPath = System.getProperty(HARDWARE_DEVICE_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv(HARDWARE_DEVICE_ENV);
        }
        if ((configuredPath == null || configuredPath.isBlank())
                && RuntimePlatform.current().isLinux()) {
            configuredPath = DEFAULT_LINUX_HARDWARE_DEVICE;
        }

        if (configuredPath == null || configuredPath.isBlank()) {
            return Optional.empty();
        }

        Path candidate = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.exists(candidate)) {
            logger.warning(() -> String.format("El dispositivo RNG configurado no existe: %s", candidate));
            return Optional.empty();
        }
        if (!Files.isReadable(candidate)) {
            logger.warning(() -> String.format("El dispositivo RNG configurado no es legible: %s", candidate));
            return Optional.empty();
        }

        return Optional.of(candidate);
    }
}
