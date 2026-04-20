package pe.gob.onpe.votodigital.elgamalcipher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlatformRandomSourceTest {

    private static final String RNG_DEVICE_PROPERTY = "elgamal.rng.device";
    private static final String X86_64_ARCH = "x86_64";

    @AfterEach
    void cleanup() {
        System.clearProperty(RNG_DEVICE_PROPERTY);
    }

    @Test
    void producesRequestedNumberOfBytes() {
        PlatformRandomSource source = new PlatformRandomSource();
        byte[] bytes = source.getBytes(32);

        assertEquals(32, bytes.length);
        assertFalse(isAllZero(bytes), "SecureRandom no debe devolver un bloque nulo completo");
    }

    @Test
    void serializesHumanReadableDescription() throws Exception {
        PlatformRandomSource source = new PlatformRandomSource();
        String serialized = ByteTree.byteTreeToString((ByteTree) source.toByteTree());

        assertTrue(serialized.contains("PlatformRandomSource"));
        assertTrue(serialized.contains(RuntimePlatform.current().classifier()));
    }

    @Test
    void softwareFactoryUsesPortableRandomSourceOutsideUbuntu() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.WINDOWS, X86_64_ARCH);
        RandomSource source = RandomSourceFactory.create(false, Logger.getAnonymousLogger(), platform);

        assertInstanceOf(PlatformRandomSource.class, source);
    }

    @Test
    void hardwareFactoryUsesPortableRandomSourceOutsideUbuntu() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.WINDOWS, X86_64_ARCH);
        RandomSource source = RandomSourceFactory.create(
                true,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty()
        );

        assertInstanceOf(PlatformRandomSource.class, source);
    }

    @Test
    void windowsHardwareFactoryUsesTrueRngProviderWhenAvailable() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.WINDOWS, X86_64_ARCH);
        RandomSource hardwareSource = new PlatformRandomSource();

        RandomSource source = RandomSourceFactory.create(
                true,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.of(hardwareSource)
        );

        assertSame(hardwareSource, source);
    }

    @Test
    void ubuntuSoftwareFactoryUsesRandomDeviceUrandom() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.UBUNTU, X86_64_ARCH);
        RandomSource ubuntuSoftwareSource = new PlatformRandomSource();
        RandomSource source = RandomSourceFactory.create(
                false,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty(),
                (hardwareRequested, logger) -> {
                    assertFalse(hardwareRequested);
                    return ubuntuSoftwareSource;
                }
        );

        assertSame(ubuntuSoftwareSource, source);
    }

    @Test
    void ubuntuHardwareFactoryFallsBackToUrandomWhenTrueRngIsMissing() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.UBUNTU, X86_64_ARCH);
        Path missingDevice = Path.of("target", "missing-true-rng-device");
        if (Files.exists(missingDevice)) {
            throw new IllegalStateException("La ruta usada para la prueba debe no existir: " + missingDevice);
        }

        System.setProperty(RNG_DEVICE_PROPERTY, missingDevice.toString());
        RandomSource ubuntuHardwareFallback = new PlatformRandomSource();
        RandomSource source = RandomSourceFactory.create(
                true,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty(),
                (hardwareRequested, logger) -> {
                    assertTrue(hardwareRequested);
                    return ubuntuHardwareFallback;
                }
        );

        assertSame(ubuntuHardwareFallback, source);
    }

    @Test
    void macSoftwareFactoryUsesPortableRandomSource() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.MACOS, "aarch64");
        RandomSource source = RandomSourceFactory.create(
                false,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty(),
                logger -> Optional.empty(),
                (hardwareRequested, logger) -> new PlatformRandomSource()
        );

        assertInstanceOf(PlatformRandomSource.class, source);
    }

    @Test
    void macHardwareFactoryUsesTrueRngProviderWhenAvailable() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.MACOS, "aarch64");
        RandomSource hardwareSource = new PlatformRandomSource();

        RandomSource source = RandomSourceFactory.create(
                true,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty(),
                logger -> Optional.of(hardwareSource),
                (hardwareRequested, logger) -> new PlatformRandomSource()
        );

        assertSame(hardwareSource, source);
    }

    @Test
    void macHardwareFactoryFallsBackToPortableWhenTrueRngMissing() {
        RuntimePlatform platform = RuntimePlatform.forTesting(
                RuntimePlatform.OperatingSystem.MACOS, "aarch64");
        RandomSource source = RandomSourceFactory.create(
                true,
                Logger.getAnonymousLogger(),
                platform,
                logger -> Optional.empty(),
                logger -> Optional.empty(),
                (hardwareRequested, logger) -> new PlatformRandomSource()
        );

        assertInstanceOf(PlatformRandomSource.class, source);
    }

    private boolean isAllZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
