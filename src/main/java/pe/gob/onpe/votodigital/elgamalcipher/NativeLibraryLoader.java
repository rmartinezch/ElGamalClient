package pe.gob.onpe.votodigital.elgamalcipher;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Ensures the JVM can resolve local JNI libraries before Verificatum is used.
 */
public final class NativeLibraryLoader {

    private static final String REEXEC_PROPERTY = "elgamal.native.reexec";
    private static final String[] REQUIRED_LIBRARIES = {"vecj-2.2.0"};
    private static final String[] OPTIONAL_LIBRARIES = {"vmgj-1.3.0"};

    private NativeLibraryLoader() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }

    public static void ensureLibraryPath(String[] args, Logger logger) {
        if (Boolean.getBoolean(REEXEC_PROPERTY)) {
            return;
        }

        RuntimePlatform platform = RuntimePlatform.current();
        Path librariesDir = findLibrariesDirectory(platform, logger);
        if (librariesDir == null) {
            String requiredName = System.mapLibraryName(REQUIRED_LIBRARIES[0]);
            logger.warning(() -> String.format("No se encontró una biblioteca nativa compatible con %s. "
                    + "Se buscó %s en los directorios esperados de libs.",
                    platform.classifier(), requiredName));
            return;
        }

        if (hasLibraryPathEntry(librariesDir)) {
            logger.info(() -> String.format("Usando bibliotecas nativas desde java.library.path: %s", librariesDir));
            logOptionalLibraries(librariesDir, logger);
            return;
        }

        Path runtimeArtifact = getCodeSourceLocation();
        if (runtimeArtifact == null || !Files.isRegularFile(runtimeArtifact)) {
            logger.warning(() -> String.format("Las bibliotecas nativas locales están en %s, pero esta ejecución no proviene de un JAR. "
                    + "Inicie la JVM con -Djava.library.path=%s para usar Verificatum.",
                    librariesDir, librariesDir));
            return;
        }

        relaunchWithLibraryPath(args, logger, librariesDir, runtimeArtifact);
    }

    private static void relaunchWithLibraryPath(String[] args,
                                                Logger logger,
                                                Path librariesDir,
                                                Path runtimeArtifact) {
        List<String> command = new ArrayList<>();
        command.add(new File(new File(System.getProperty("java.home"), "bin"), "java").toPath().toString());
        command.add("-D" + REEXEC_PROPERTY + "=true");
        command.add("-Djava.library.path=" + buildLibraryPath(librariesDir));
        command.add("-jar");
        command.add(runtimeArtifact.toAbsolutePath().toString());
        command.addAll(Arrays.asList(args));

        logger.info(() -> String.format("Relanzando el JAR con java.library.path=%s", librariesDir));

        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo relanzar la aplicacion con java.library.path configurado.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La relanzada del proceso fue interrumpida.", e);
        }
    }

    private static boolean hasLibraryPathEntry(Path librariesDir) {
        String currentPath = System.getProperty("java.library.path", "");
        String normalizedDir = librariesDir.toAbsolutePath().normalize().toString();
        for (String pathEntry : currentPath.split(java.io.File.pathSeparator)) {
            if (normalizedDir.equals(new File(pathEntry).toPath().toAbsolutePath().normalize().toString())) {
                return true;
            }
        }
        return false;
    }

    private static String buildLibraryPath(Path librariesDir) {
        String currentPath = System.getProperty("java.library.path", "");
        if (currentPath == null || currentPath.isBlank()) {
            return librariesDir.toAbsolutePath().toString();
        }
        return librariesDir.toAbsolutePath() + java.io.File.pathSeparator + currentPath;
    }

    private static Path findLibrariesDirectory(RuntimePlatform platform, Logger logger) {
        for (Path directory : getCandidateDirectories(platform)) {
            if (containsAllRequiredLibraries(directory)) {
                logOptionalLibraries(directory, logger);
                return directory;
            }
        }
        return null;
    }

    private static boolean containsAllRequiredLibraries(Path directory) {
        for (String library : REQUIRED_LIBRARIES) {
            if (!Files.isRegularFile(directory.resolve(System.mapLibraryName(library)))) {
                return false;
            }
        }
        return true;
    }

    private static void logOptionalLibraries(Path directory, Logger logger) {
        for (String library : OPTIONAL_LIBRARIES) {
            Path candidate = directory.resolve(System.mapLibraryName(library));
            if (!Files.isRegularFile(candidate)) {
                logger.info(() -> String.format("Biblioteca opcional no encontrada en %s: %s",
                        directory, candidate.getFileName()));
            }
        }
    }

    private static List<Path> getCandidateDirectories(RuntimePlatform platform) {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(new File(new File("libs"), platform.classifier()).toPath().toAbsolutePath().normalize());
        directories.add(new File("libs").toPath().toAbsolutePath().normalize());

        Path codeSourceDir = getCodeSourceDirectory();
        if (codeSourceDir != null) {
            Path current = codeSourceDir;
            for (int i = 0; i < 3 && current != null; i++) {
                directories.add(current.resolve("libs").resolve(platform.classifier()).normalize());
                directories.add(current.resolve("libs").normalize());
                current = current.getParent();
            }
        }
        return new ArrayList<>(directories);
    }

    private static Path getCodeSourceDirectory() {
        Path codeSource = getCodeSourceLocation();
        if (codeSource == null) {
            return null;
        }
        return Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
    }

    private static Path getCodeSourceLocation() {
        try {
            CodeSource codeSource = NativeLibraryLoader.class
                    .getProtectionDomain()
                    .getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            return new File(codeSource.getLocation().toURI()).toPath().toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
