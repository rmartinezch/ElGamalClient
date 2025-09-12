package pe.gob.onpe.votodigital.elgamalcipher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.*;

/**
 * Class to configure a logger into this project
 *
 * @author rmartinezch
 */
public class LogConfig {

    private static final String DEFAULT_LOG_DIR = "./logs";
    private static final String DEFAULT_LOG_FILE = "aplicacion.log";
    private static Logger globalLogger;
    
    // Evita la creación de instancias
    private LogConfig() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }

    /**
     * Return a global logger, configuration only once to all project.
     *
     * @param logFilePath Log file path.
     * @param logLevel Minimal level.
     * @param showDateTime Show date and time.
     * @param showClassName Show class name.
     * @param showLogLevel Show log level.
     * @param showLineNumber Show the number of line in the code.
     * @param maxFileSize Maximal size in every log file.
     * @param maxBackupFiles Maximal number of backup log files.
     * @param enableConsoleOutput Show at console.
     * @return Logger
     */
    public static synchronized Logger getLogger(
            String logFilePath,
            Level logLevel,
            boolean showDateTime,
            boolean showClassName,
            boolean showLogLevel,
            boolean showLineNumber,
            int maxFileSize,
            int maxBackupFiles,
            boolean enableConsoleOutput) {

        if (globalLogger != null) {
            return globalLogger;
        }

        globalLogger = Logger.getLogger("GlobalLogger");
        globalLogger.setUseParentHandlers(false);

        Path logFile = resolveLogFilePath(logFilePath);
        FileHandler fileHandler = createFileHandlerWithFallback(logFile, maxFileSize, maxBackupFiles);

        Formatter formatter = buildCustomFormatter(showDateTime, showClassName, showLogLevel, showLineNumber);
        configureFileHandler(fileHandler, formatter, logLevel);
        globalLogger.addHandler(fileHandler);

        if (enableConsoleOutput) {
            ConsoleHandler consoleHandler = createConsoleHandler(formatter, logLevel);
            globalLogger.addHandler(consoleHandler);
        }

        globalLogger.setLevel(logLevel);
        return globalLogger;
    }//*/

    private static Path resolveLogFilePath(String logFilePath) {
        if (logFilePath == null || logFilePath.isBlank()) {
            logFilePath = DEFAULT_LOG_DIR + File.separator + DEFAULT_LOG_FILE;
        }
        return Path.of(logFilePath).toAbsolutePath();
    }

    private static FileHandler createFileHandlerWithFallback(Path target, int maxFileSize, int maxBackupFiles) {
        try {
            Files.createDirectories(target.getParent());
            return new FileHandler(target.toString(), maxFileSize, maxBackupFiles, true);
        } catch (IOException e) {
            return createFallbackHandler(maxFileSize, maxBackupFiles, target);
        }
    }

    private static FileHandler createFallbackHandler(int maxFileSize, int maxBackupFiles, Path failedTarget) {
        Path fallbackDir = Path.of(DEFAULT_LOG_DIR).toAbsolutePath();
        Path fallbackFile = fallbackDir.resolve(DEFAULT_LOG_FILE);

        try {
            Files.createDirectories(fallbackDir);
            System.err.println("[LOG CONFIG] No se pudo usar la ruta '" + failedTarget + "'");
            System.err.println("[LOG CONFIG] Usando ruta alternativa: " + fallbackFile);
            return new FileHandler(fallbackFile.toString(), maxFileSize, maxBackupFiles, true);
        } catch (IOException ex) {
            Path tmpLog = Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_LOG_FILE);
            try {
                System.err.println("[LOG CONFIG] Error creando log en ruta por defecto.");
                System.err.println("[LOG CONFIG] Usando ruta temporal: " + tmpLog);
                return new FileHandler(tmpLog.toString(), maxFileSize, maxBackupFiles, true);
            } catch (IOException fatal) {
                System.err.println("[LOG CONFIG] No se pudo crear ningún archivo de log.");
                return null;
            }
        }
    }

    private static Formatter buildCustomFormatter(
            boolean showDateTime, boolean showClassName, boolean showLogLevel, boolean showLineNumber) {

        return new Formatter() {
            @Override
            public String format(LogRecord record) {
                StringBuilder sb = new StringBuilder();

                if (showDateTime) {
                    sb.append("[").append(new java.util.Date(record.getMillis())).append("] ");
                }
                if (showLogLevel) {
                    sb.append("[").append(record.getLevel()).append("] ");
                }
                if (showClassName || showLineNumber) {
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    String callerClass = record.getSourceClassName();
                    int line = findCallerLine(stack, callerClass);

                    if (showClassName) {
                        sb.append("[").append(callerClass);
                        if (showLineNumber && line >= 0) {
                            sb.append(":").append(line);
                        }
                        sb.append("] ");
                    } else if (showLineNumber && line >= 0) {
                        sb.append("[Línea ").append(line).append("] ");
                    }
                }

                sb.append(record.getMessage()).append("\n");
                return sb.toString();
            }
        };
    }

    private static int findCallerLine(StackTraceElement[] stack, String callerClass) {
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals(callerClass)) {
                return frame.getLineNumber();
            }
        }
        return -1;
    }

    private static void configureFileHandler(FileHandler handler, Formatter formatter, Level level) {
        if (handler != null) {
            handler.setFormatter(formatter);
            handler.setLevel(level);
        }
    }

    private static ConsoleHandler createConsoleHandler(Formatter formatter, Level level) {
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(formatter);
        handler.setLevel(level);
        return handler;
    }

}