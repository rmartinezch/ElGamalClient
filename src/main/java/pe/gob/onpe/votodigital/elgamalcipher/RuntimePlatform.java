package pe.gob.onpe.votodigital.elgamalcipher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Detects the current OS/architecture pair used by the application.
 */
public final class RuntimePlatform {

    public enum OperatingSystem {
        WINDOWS,
        UBUNTU,
        LINUX,
        MACOS,
        ANDROID,
        OTHER
    }

    private static final String ANDROID_TOKEN = "android";
    private static final String UBUNTU_TOKEN = "ubuntu";
    private static final Path OS_RELEASE_PATH = new File(
            System.getProperty("elgamal.osReleasePath", "/etc/os-release")
    ).toPath();

    private final String osName;
    private final String architecture;
    private final String classifier;
    private final OperatingSystem operatingSystem;

    private RuntimePlatform(String osName,
                            String architecture,
                            String classifier,
                            OperatingSystem operatingSystem) {
        this.osName = osName;
        this.architecture = architecture;
        this.classifier = classifier;
        this.operatingSystem = operatingSystem;
    }

    public static RuntimePlatform current() {
        String rawOs = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        String rawArch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);

        OperatingSystem os = detectOperatingSystem(rawOs);

        String normalizedOs = normalizeOperatingSystemToken(os, rawOs);
        String normalizedArch = normalizeArchitecture(rawArch);
        return new RuntimePlatform(rawOs, rawArch, normalizedOs + "-" + normalizedArch, os);
    }

    static RuntimePlatform forTesting(OperatingSystem operatingSystem, String architecture) {
        String rawArch = architecture.toLowerCase(Locale.ROOT);
        String normalizedOs = normalizeOperatingSystemToken(operatingSystem,
                operatingSystem.name().toLowerCase(Locale.ROOT));
        String normalizedArch = normalizeArchitecture(rawArch);
        return new RuntimePlatform(
                operatingSystem.name().toLowerCase(Locale.ROOT),
                rawArch,
                normalizedOs + "-" + normalizedArch,
                operatingSystem
        );
    }

    public String osName() {
        return osName;
    }

    public String architecture() {
        return architecture;
    }

    public String classifier() {
        return classifier;
    }

    public OperatingSystem operatingSystem() {
        return operatingSystem;
    }

    public boolean isWindows() {
        return operatingSystem == OperatingSystem.WINDOWS;
    }

    public boolean isUbuntu() {
        return operatingSystem == OperatingSystem.UBUNTU;
    }

    public boolean isLinux() {
        return operatingSystem == OperatingSystem.LINUX
                || operatingSystem == OperatingSystem.UBUNTU;
    }

    public boolean isMac() {
        return operatingSystem == OperatingSystem.MACOS;
    }

    public boolean isAndroid() {
        return operatingSystem == OperatingSystem.ANDROID;
    }

    private static String normalizeOperatingSystemToken(OperatingSystem operatingSystem, String rawOs) {
        return switch (operatingSystem) {
            case WINDOWS -> "windows";
            case UBUNTU, LINUX -> "linux";
            case MACOS -> "macos";
            case ANDROID -> ANDROID_TOKEN;
            case OTHER -> sanitizeToken(rawOs);
        };
    }

    private static OperatingSystem detectOperatingSystem(String rawOs) {
        if (rawOs.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        if (rawOs.contains("mac")) {
            return OperatingSystem.MACOS;
        }
        if (isAndroidRuntime()) {
            return OperatingSystem.ANDROID;
        }
        if (rawOs.contains("nux") || rawOs.contains("linux")) {
            return detectLinuxFamily();
        }
        if (rawOs.contains(ANDROID_TOKEN)) {
            return OperatingSystem.ANDROID;
        }
        return OperatingSystem.OTHER;
    }

    private static OperatingSystem detectLinuxFamily() {
        String linuxId = readLinuxDistributionId();
        if (UBUNTU_TOKEN.equals(linuxId)) {
            return OperatingSystem.UBUNTU;
        }
        if (ANDROID_TOKEN.equals(linuxId)) {
            return OperatingSystem.ANDROID;
        }
        return OperatingSystem.LINUX;
    }

    private static boolean isAndroidRuntime() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String javaVendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String runtimeName = System.getProperty("java.runtime.name", "").toLowerCase(Locale.ROOT);
        return vmName.contains("dalvik")
                || javaVendor.contains(ANDROID_TOKEN)
                || runtimeName.contains(ANDROID_TOKEN);
    }

    private static String readLinuxDistributionId() {
        if (!Files.isRegularFile(OS_RELEASE_PATH) || !Files.isReadable(OS_RELEASE_PATH)) {
            return "";
        }
        try {
            String id = "";
            String idLike = "";
            for (String line : Files.readAllLines(OS_RELEASE_PATH)) {
                String[] entry = parseOsReleaseLine(line);
                if (entry.length == 2) {
                    if ("ID".equals(entry[0])) {
                        id = entry[1];
                    } else if ("ID_LIKE".equals(entry[0])) {
                        idLike = entry[1];
                    }
                }
            }
            return resolveLinuxDistributionId(id, idLike);
        } catch (IOException e) {
            return "";
        }
    }

    private static String[] parseOsReleaseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return new String[0];
        }
        int separator = trimmed.indexOf('=');
        if (separator < 1) {
            return new String[0];
        }
        String key = trimmed.substring(0, separator);
        String value = stripQuotes(trimmed.substring(separator + 1))
                .toLowerCase(Locale.ROOT);
        return new String[]{key, value};
    }

    private static String resolveLinuxDistributionId(String id, String idLike) {
        if (UBUNTU_TOKEN.equals(id)) {
            return UBUNTU_TOKEN;
        }
        if (ANDROID_TOKEN.equals(id)) {
            return ANDROID_TOKEN;
        }
        if (idLike.contains(UBUNTU_TOKEN)) {
            return UBUNTU_TOKEN;
        }
        return id;
    }

    private static String stripQuotes(String rawValue) {
        if (rawValue.length() >= 2
                && rawValue.startsWith("\"")
                && rawValue.endsWith("\"")) {
            return rawValue.substring(1, rawValue.length() - 1);
        }
        return rawValue;
    }

    private static String normalizeArchitecture(String rawArch) {
        return switch (rawArch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> sanitizeToken(rawArch);
        };
    }

    private static String sanitizeToken(String value) {
        return value.replaceAll("[^a-z0-9]+", "-");
    }
}
