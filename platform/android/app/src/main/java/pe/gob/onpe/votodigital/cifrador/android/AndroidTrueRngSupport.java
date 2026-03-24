package pe.gob.onpe.votodigital.cifrador.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class AndroidTrueRngSupport {

    public static final String ACTION_USB_PERMISSION =
            "pe.gob.onpe.votodigital.cifrador.android.USB_PERMISSION";
    public static final int TRUE_RNG_VENDOR_ID = 0x04d8;
    public static final int TRUE_RNG_PRODUCT_ID = 0xf5fe;

    private final Context context;
    private final UsbManager usbManager;
    private final UsbSerialProber prober;

    public AndroidTrueRngSupport(Context context) {
        this.context = context;
        this.usbManager = context.getSystemService(UsbManager.class);
        this.prober = UsbSerialProber.getDefaultProber();
    }

    public DeviceStatus scanStatus() {
        UsbDevice device = findDevice();
        if (device == null) {
            return new DeviceStatus(
                    "TrueRNG USB no detectado. Conectalo por OTG y vuelve a intentar.",
                    false,
                    false
            );
        }
        UsbSerialDriver driver = findDriver(device);
        boolean hasPermission = usbManager.hasPermission(device);
        String message = new StringBuilder()
                .append(String.format(Locale.US,
                        "TrueRNG detectado: vid=0x%04x pid=0x%04x",
                        device.getVendorId(),
                        device.getProductId()))
                .append("\ndriver=")
                .append(driver == null ? "no_serial_driver" : driver.getClass().getSimpleName())
                .append("\npermiso=")
                .append(hasPermission ? "concedido" : "pendiente")
                .toString();
        return new DeviceStatus(message, true, hasPermission);
    }

    public PermissionRequest requestPermission(PendingIntent pendingIntent) {
        UsbDevice device = findDevice();
        if (device == null) {
            return new PermissionRequest(
                    "No se encontro un TrueRNG USB conectado para pedir permiso.",
                    false
            );
        }
        if (usbManager.hasPermission(device)) {
            return new PermissionRequest(
                    "El permiso USB para TrueRNG ya estaba concedido.",
                    false
            );
        }
        usbManager.requestPermission(device, pendingIntent);
        return new PermissionRequest(
                "Solicitud de permiso USB enviada para TrueRNG.",
                true
        );
    }

    public String buildPermissionResult(UsbDevice device, boolean granted) {
        String label = device == null
                ? "TrueRNG"
                : String.format(Locale.US, "vid=0x%04x pid=0x%04x", device.getVendorId(), device.getProductId());
        return granted
                ? "Permiso USB concedido para " + label + "."
                : "Permiso USB denegado para " + label + ".";
    }

    public AndroidUsbTrueRngRandomSource openRandomSource(Logger logger) {
        UsbDevice device = findDevice();
        if (device == null) {
            throw new IllegalStateException("No se detecto un TrueRNG USB conectado en Android.");
        }
        if (!usbManager.hasPermission(device)) {
            throw new IllegalStateException(
                    "Falta permiso USB para el TrueRNG en Android. Usa 'Solicitar permiso TrueRNG'."
            );
        }
        UsbSerialDriver driver = findDriver(device);
        if (driver == null) {
            throw new IllegalStateException(
                    "Android detecto el dispositivo, pero no encontro driver serial compatible para TrueRNG."
            );
        }
        android.hardware.usb.UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Android no pudo abrir la conexion USB del TrueRNG.");
        }
        return AndroidUsbTrueRngRandomSource.open(device, driver, connection, logger);
    }

    public UsbDevice findDevice() {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == TRUE_RNG_VENDOR_ID
                    && device.getProductId() == TRUE_RNG_PRODUCT_ID) {
                return device;
            }
        }
        return null;
    }

    private UsbSerialDriver findDriver(UsbDevice device) {
        List<UsbSerialDriver> drivers = prober.findAllDrivers(usbManager);
        for (UsbSerialDriver driver : drivers) {
            if (driver.getDevice().getDeviceId() == device.getDeviceId()) {
                return driver;
            }
        }
        return null;
    }

    public static PendingIntent buildPermissionIntent(Context context) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(
                context,
                2001,
                new Intent(ACTION_USB_PERMISSION),
                flags
        );
    }

    public static final class DeviceStatus {
        private final String message;
        private final boolean deviceFound;
        private final boolean permissionGranted;

        public DeviceStatus(String message, boolean deviceFound, boolean permissionGranted) {
            this.message = message;
            this.deviceFound = deviceFound;
            this.permissionGranted = permissionGranted;
        }

        public String getMessage() {
            return message;
        }

        public boolean getDeviceFound() {
            return deviceFound;
        }

        public boolean getPermissionGranted() {
            return permissionGranted;
        }
    }

    public static final class PermissionRequest {
        private final String message;
        private final boolean requested;

        public PermissionRequest(String message, boolean requested) {
            this.message = message;
            this.requested = requested;
        }

        public String getMessage() {
            return message;
        }

        public boolean getRequested() {
            return requested;
        }
    }
}

