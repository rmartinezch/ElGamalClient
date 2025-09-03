package pe.gob.onpe.votodigital.elgamalcipher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.*;

/**
 * Class to configure a logger into this project
 * @author rmartinezch
 */
public class LogConfig {

    private static final String DEFAULT_LOG_DIR = "./logs";
    private static final String DEFAULT_LOG_FILE = "aplicacion.log";
    private static Logger globalLogger;
    private static FileHandler fileHandler;
    private static ConsoleHandler consoleHandler;

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

        // If it exists, reuse the global logger
        if (globalLogger != null) {
            return globalLogger;
        }

        globalLogger = Logger.getLogger("GlobalLogger");
        globalLogger.setUseParentHandlers(false);

        // If a path is not specified, use default path
        if (logFilePath == null || logFilePath.isBlank()) {
            logFilePath = DEFAULT_LOG_DIR + "/" + DEFAULT_LOG_FILE;
        }

        Path targetLogFile = Path.of(logFilePath).toAbsolutePath();

        try {
            Files.createDirectories(targetLogFile.getParent());
            fileHandler = new FileHandler(
                    targetLogFile.toString(),
                    maxFileSize,
                    maxBackupFiles,
                    true
            );
        } catch (IOException e) {
            // If it fails, use the default path
            Path fallbackDir = Path.of(DEFAULT_LOG_DIR).toAbsolutePath();
            Path fallbackLogFile = fallbackDir.resolve(DEFAULT_LOG_FILE);
            try {
                Files.createDirectories(fallbackDir);
                fileHandler = new FileHandler(
                        fallbackLogFile.toString(),
                        maxFileSize,
                        maxBackupFiles,
                        true
                );
                System.err.println("[LOG CONFIG] No se pudo usar la ruta '" + targetLogFile + "'");
                System.err.println("[LOG CONFIG] Usando ruta alternativa: " + fallbackLogFile);
            } catch (IOException ex) {
                // If also it fails, use /tmp
                Path tmpLog = Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_LOG_FILE);
                try {
                    fileHandler = new FileHandler(
                            tmpLog.toString(),
                            maxFileSize,
                            maxBackupFiles,
                            true
                    );
                    System.err.println("[LOG CONFIG] Error creando log en ruta por defecto.");
                    System.err.println("[LOG CONFIG] Usando ruta temporal: " + tmpLog);
                } catch (IOException fatal) {
                    System.err.println("[LOG CONFIG] No se pudo crear ningún archivo de log.");
                    return globalLogger;
                }
            }
        }

        // Customizing the format of the log
        Formatter customFormatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                StringBuilder sb = new StringBuilder();

                // Date and time
                if (showDateTime) {
                    sb.append("[").append(new java.util.Date(record.getMillis())).append("] ");
                }

                // Level of log
                if (showLogLevel) {
                    sb.append("[").append(record.getLevel()).append("] ");
                }

                // Class name and line of code
                if (showClassName || showLineNumber) {
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    // Get the frame which contains the class name
                    String callerClass = record.getSourceClassName();
                    int line = -1;

                    for (StackTraceElement frame : stack) {
                        if (frame.getClassName().equals(callerClass)) {
                            line = frame.getLineNumber();
                            break;
                        }
                    }

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

                // Main message
                sb.append(record.getMessage()).append("\n");
                return sb.toString();
            }
        };

        // Configurate the FileHandler
        fileHandler.setFormatter(customFormatter);
        fileHandler.setLevel(logLevel);
        globalLogger.addHandler(fileHandler);

        // Configure the output at console, it it is enabled.
        if (enableConsoleOutput) {
            consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(customFormatter);
            consoleHandler.setLevel(logLevel);
            globalLogger.addHandler(consoleHandler);
        }

        globalLogger.setLevel(logLevel);
        return globalLogger;
    }
}