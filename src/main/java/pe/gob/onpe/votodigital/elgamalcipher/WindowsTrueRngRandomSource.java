package pe.gob.onpe.votodigital.elgamalcipher;

import com.fazecast.jSerialComm.SerialPort;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * TrueRNG source for Windows backed by the device USB serial interface.
 */
public final class WindowsTrueRngRandomSource extends RandomSource {

    static final int TRUERNG_VENDOR_ID = 0x04D8;
    static final int TRUERNG_PRODUCT_ID = 0xF5FE;
    private static final String TRUE_RNG_TOKEN = "truerng";
    private static final int DEFAULT_BAUD_RATE = 115200;
    private static final int READ_TIMEOUT_MS = 2000;

    private final SerialPort serialPort;
    private final String portName;
    private final String description;
    private final Object monitor = new Object();
    private InputStream inputStream;

    private WindowsTrueRngRandomSource(SerialPort serialPort) {
        this.serialPort = serialPort;
        this.portName = serialPort.getSystemPortName();
        this.description = buildDescription(serialPort);
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeQuietly, "truerng-close-" + portName));
    }

    public static Optional<RandomSource> tryCreate(Logger logger) {
        String configuredPort = resolveConfiguredPortName();
        if (configuredPort != null) {
            return openConfiguredPort(configuredPort, logger).map(source -> (RandomSource) source);
        }

        for (SerialPort candidate : SerialPort.getCommPorts()) {
            if (!looksLikeTrueRng(candidate)) {
                continue;
            }

            Optional<WindowsTrueRngRandomSource> source = openPort(candidate, logger);
            if (source.isPresent()) {
                return source.map(value -> (RandomSource) value);
            }
        }

        logger.warning(() -> "Windows -hw: no se detectó un TrueRNG accesible por puerto serie. "
                + "Configura -Delgamal.rng.device=COMx o ELGAMAL_RNG_DEVICE=COMx.");
        return Optional.empty();
    }

    @Override
    public void getBytes(byte[] array) {
        synchronized (monitor) {
            ensureOpen();
            int offset = 0;
            try {
                while (offset < array.length) {
                    int read = inputStream.read(array, offset, array.length - offset);
                    if (read < 0) {
                        throw new IllegalStateException("El TrueRNG cerro el flujo en " + portName);
                    }
                    offset += read;
                }
            } catch (IOException e) {
                closeQuietly();
                throw new IllegalStateException("No se pudo leer del TrueRNG en " + portName, e);
            }
        }
    }

    @Override
    public ByteTreeBasic toByteTree() {
        return new ByteTree(humanDescription(false).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String humanDescription(boolean verbose) {
        String suffix = verbose ? "," + description : "";
        return "WindowsTrueRngRandomSource(" + portName + suffix + ")";
    }

    static String normalizeConfiguredPortName(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        String normalized = trimmed.replace("/", "\\");
        if (normalized.startsWith("\\\\.\\")) {
            normalized = normalized.substring(4);
        } else if (normalized.startsWith("\\\\?\\")) {
            normalized = normalized.substring(4);
        }
        return normalized.trim();
    }

    static boolean looksLikeTrueRng(SerialPort port) {
        if (port.getVendorID() == TRUERNG_VENDOR_ID && port.getProductID() == TRUERNG_PRODUCT_ID) {
            return true;
        }

        String[] descriptors = {
            port.getDescriptivePortName(),
            port.getPortDescription(),
            port.getPortLocation()
        };
        for (String descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            if (descriptor.toLowerCase(Locale.ROOT).contains(TRUE_RNG_TOKEN)) {
                return true;
            }
        }
        return false;
    }

    private static String resolveConfiguredPortName() {
        String configuredPort = System.getProperty("elgamal.rng.device");
        if (configuredPort == null || configuredPort.isBlank()) {
            configuredPort = System.getenv("ELGAMAL_RNG_DEVICE");
        }
        return normalizeConfiguredPortName(configuredPort);
    }

    private static Optional<WindowsTrueRngRandomSource> openConfiguredPort(String configuredPort, Logger logger) {
        for (SerialPort port : SerialPort.getCommPorts()) {
            if (configuredPort.equalsIgnoreCase(port.getSystemPortName())) {
                return openPort(port, logger);
            }
        }

        SerialPort directPort = SerialPort.getCommPort(configuredPort);
        return openPort(directPort, logger);
    }

    private static Optional<WindowsTrueRngRandomSource> openPort(SerialPort port, Logger logger) {
        String portName = port.getSystemPortName();
        port.setComPortParameters(DEFAULT_BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, READ_TIMEOUT_MS, 0);
        port.setDTR();
        port.clearRTS();

        if (!port.openPort()) {
            logger.warning(() -> String.format("Windows -hw: no se pudo abrir el puerto %s", portName));
            return Optional.empty();
        }

        try {
            WindowsTrueRngRandomSource source = new WindowsTrueRngRandomSource(port);
            source.inputStream = port.getInputStream();
            logger.info(() -> String.format("Windows -hw: usando TrueRNG en %s (%s)",
                    portName,
                    buildDescription(port)));
            return Optional.of(source);
        } catch (Exception e) {
            try {
                port.closePort();
            } catch (Exception ignored) {
                // Ignorado para preservar el error original.
            }
            logger.warning(() -> String.format("Windows -hw: no se pudo inicializar el puerto %s: %s",
                    portName,
                    e.getMessage()));
            return Optional.empty();
        }
    }

    private static String buildDescription(SerialPort port) {
        String portDescription = port.getPortDescription();
        if (portDescription != null && !portDescription.isBlank()) {
            return portDescription.trim();
        }

        String descriptiveName = port.getDescriptivePortName();
        if (descriptiveName != null && !descriptiveName.isBlank()) {
            return descriptiveName.trim();
        }
        return "USB serial";
    }

    private void ensureOpen() {
        if (serialPort.isOpen() && inputStream != null) {
            return;
        }
        throw new IllegalStateException("El puerto TrueRNG ya no esta disponible: " + portName);
    }

    private void closeQuietly() {
        synchronized (monitor) {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                    // Ignorado para cierre de proceso.
                }
                inputStream = null;
            }
            if (serialPort.isOpen()) {
                try {
                    serialPort.closePort();
                } catch (Exception ignored) {
                    // Ignorado para cierre de proceso.
                }
            }
        }
    }
}
