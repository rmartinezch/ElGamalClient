package pe.gob.onpe.votodigital.elgamalcipher;

/**
 * Detects the current OS/architecture pair used by the application.
 */
public final class RuntimePlatform {

    private final String osName;
    private final String architecture;
    private final String classifier;
    private final boolean windows;
    private final boolean linux;
    private final boolean mac;

    private RuntimePlatform(String osName,
                            String architecture,
                            String classifier,
                            boolean windows,
                            boolean linux,
                            boolean mac) {
        this.osName = osName;
        this.architecture = architecture;
        this.classifier = classifier;
        this.windows = windows;
        this.linux = linux;
        this.mac = mac;
    }

    public static RuntimePlatform current() {
        String rawOs = System.getProperty("os.name", "unknown").toLowerCase();
        String rawArch = System.getProperty("os.arch", "unknown").toLowerCase();

        boolean isWindows = rawOs.contains("win");
        boolean isMac = rawOs.contains("mac");
        boolean isLinux = rawOs.contains("nux") || rawOs.contains("linux");

        String normalizedOs;
        if (isWindows) {
            normalizedOs = "windows";
        } else if (isMac) {
            normalizedOs = "macos";
        } else if (isLinux) {
            normalizedOs = "linux";
        } else {
            normalizedOs = sanitizeToken(rawOs);
        }

        String normalizedArch = normalizeArchitecture(rawArch);
        return new RuntimePlatform(rawOs, rawArch, normalizedOs + "-" + normalizedArch,
                isWindows, isLinux, isMac);
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

    public boolean isWindows() {
        return windows;
    }

    public boolean isLinux() {
        return linux;
    }

    public boolean isMac() {
        return mac;
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
