package pe.gob.onpe.votodigital.cifrador.android;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public final class AndroidUsbTrueRngRandomSource extends RandomSource implements AutoCloseable {

    private static final int READ_TIMEOUT_MS = 1000;

    private final UsbDevice device;
    private final UsbSerialDriver driver;
    private final UsbDeviceConnection connection;
    private final UsbSerialPort port;
    private final Logger logger;
    private final String deviceLabel;

    private AndroidUsbTrueRngRandomSource(
            UsbDevice device,
            UsbSerialDriver driver,
            UsbDeviceConnection connection,
            UsbSerialPort port,
            Logger logger
    ) {
        this.device = device;
        this.driver = driver;
        this.connection = connection;
        this.port = port;
        this.logger = logger;
        this.deviceLabel = String.format(
                Locale.US,
                "vid=0x%04x pid=0x%04x",
                device.getVendorId(),
                device.getProductId()
        );
    }

    @Override
    public synchronized void getBytes(byte[] array) {
        int offset = 0;
        byte[] scratch = new byte[Math.min(array.length, 256)];
        while (offset < array.length) {
            int readCount;
            try {
                readCount = port.read(scratch, READ_TIMEOUT_MS);
            } catch (SerialTimeoutException timeout) {
                readCount = 0;
            } catch (Throwable error) {
                throw new IllegalStateException("No se pudo leer del TrueRNG USB en Android.", error);
            }
            if (readCount <= 0) {
                throw new IllegalStateException(
                        "El TrueRNG USB no produjo datos en Android dentro del timeout de lectura."
                );
            }
            int copyCount = Math.min(readCount, array.length - offset);
            System.arraycopy(scratch, 0, array, offset, copyCount);
            offset += copyCount;
        }
    }

    @Override
    public ByteTreeBasic toByteTree() {
        return new ByteTree(humanDescription(false).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String humanDescription(boolean verbose) {
        return "AndroidUsbTrueRngRandomSource(" + deviceLabel + ")";
    }

    @Override
    public synchronized void close() {
        try {
            port.close();
        } catch (Throwable error) {
            logCleanupFailure("cerrar puerto TrueRNG USB", error);
        }
        try {
            connection.close();
        } catch (Throwable error) {
            logCleanupFailure("cerrar conexion TrueRNG USB", error);
        }
        logger.info("Android -hw: puerto TrueRNG USB cerrado.");
    }

    private void logCleanupFailure(String action, Throwable error) {
        logger.fine(action + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    public static AndroidUsbTrueRngRandomSource open(
            UsbDevice device,
            UsbSerialDriver driver,
            UsbDeviceConnection connection,
            Logger logger
    ) {
        List<UsbSerialPort> ports = driver.getPorts();
        UsbSerialPort port = ports.isEmpty() ? null : ports.get(0);
        if (port == null) {
            throw new IllegalStateException("El driver serial TrueRNG no expone puertos en Android.");
        }
        try {
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(true);
            port.setRTS(true);
            logger.info("Android -hw: usando TrueRNG USB serial en " + device.getDeviceName() + ".");
            return new AndroidUsbTrueRngRandomSource(device, driver, connection, port, logger);
        } catch (Throwable error) {
            try {
                port.close();
            } catch (Throwable closeError) {
                logger.fine(
                        "No se pudo cerrar el puerto tras un fallo de inicializacion: "
                                + closeError.getClass().getSimpleName() + ": " + closeError.getMessage()
                );
            }
            try {
                connection.close();
            } catch (Throwable closeError) {
                logger.fine(
                        "No se pudo cerrar la conexion tras un fallo de inicializacion: "
                                + closeError.getClass().getSimpleName() + ": " + closeError.getMessage()
                );
            }
            throw new IllegalStateException("No se pudo inicializar el TrueRNG USB en Android.", error);
        }
    }
}

