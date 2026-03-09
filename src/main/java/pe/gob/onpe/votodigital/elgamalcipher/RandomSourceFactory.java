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
    private static final String DEFAULT_UBUNTU_HARDWARE_DEVICE = "/dev/TrueRNG0";

    private RandomSourceFactory() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }

    public static RandomSource create(boolean hardwareRequested, Logger logger) {
        return create(hardwareRequested, logger, RuntimePlatform.current());
    }

    static RandomSource create(boolean hardwareRequested,
                               Logger logger,
                               RuntimePlatform platform) {
        if (platform.isUbuntu()) {
            return createUbuntuRandomSource(hardwareRequested, logger);
        }

        if (hardwareRequested) {
            logger.warning(() -> String.format("Modo -hw solicitado en %s, "
                    + "pero TrueRNG dedicado solo está habilitado para Ubuntu. "
                    + "Se usará SecureRandom portable.",
                    platform.classifier()));
        } else {
            logger.info(() -> String.format("Modo Software RNG detectado en %s: "
                    + "usando SecureRandom portable.", platform.classifier()));
        }
        return new PlatformRandomSource();
    }

    private static RandomSource createUbuntuRandomSource(boolean hardwareRequested, Logger logger) {
        if (!hardwareRequested) {
            logger.info(() -> "Ubuntu -sw: usando /dev/urandom con RandomDevice.");
            return new RandomDevice();
        }

        Optional<Path> hardwareDevice = resolveUbuntuHardwareDevice(logger);
        if (hardwareDevice.isEmpty()) {
            logger.warning(() -> "Ubuntu -hw: no se encontró un dispositivo TrueRNG válido. "
                    + "Se usará /dev/urandom.");
            return new RandomDevice();
        }

        Path device = hardwareDevice.get();
        logger.info(() -> String.format("Ubuntu -hw: usando dispositivo %s", device));
        return new RandomDevice(device.toFile());
    }

    private static Optional<Path> resolveUbuntuHardwareDevice(Logger logger) {
        String configuredPath = System.getProperty(HARDWARE_DEVICE_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = System.getenv(HARDWARE_DEVICE_ENV);
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            configuredPath = DEFAULT_UBUNTU_HARDWARE_DEVICE;
        }

        Path candidate = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.exists(candidate)) {
            logger.warning(() -> String.format("El dispositivo TrueRNG configurado no existe: %s", candidate));
            return Optional.empty();
        }
        if (!Files.isReadable(candidate)) {
            logger.warning(() -> String.format("El dispositivo TrueRNG configurado no es legible: %s", candidate));
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
