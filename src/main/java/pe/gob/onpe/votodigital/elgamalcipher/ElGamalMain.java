package pe.gob.onpe.votodigital.elgamalcipher;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalMain {

    private static final Logger logger = LogConfig.getLogger(
            "./logs/sistema.log",
            Level.INFO,
            true, true, true, true, true
    );
    private static final String APP_VERSION = "1.1.0";

    public static void main(String[] args) {
        logger.info(() -> String.format("Iniciando ElGamalCipher v%s", APP_VERSION));
        logger.info(() -> "Iniciamos la lectura de parámetros en la ejecución");
        NativeLibraryLoader.ensureLibraryPath(args, logger);
        if (args.length < 4 || args.length > 5) {
            printUsage();
            return;
        }

        CifradorRngMode rngMode = CifradorRngMode.fromCliFlag(args[3]).orElse(null);
        if (rngMode == null) {
            logger.warning(() -> String.format("Cuarto parámetro inválido: %s", args[3]));
            printUsage();
            return;
        }

        boolean showProgressBar = (args.length == 5 && "-p".equals(args[4]));
        CifradorRequest request = new CifradorRequest(
                new File(args[0]).toPath(),
                new File(args[1]).toPath(),
                new File(args[2]).toPath(),
                rngMode,
                showProgressBar
        );
        ElGamalCipherService cipherService = new ElGamalCipherService(logger);
        cipherService.encrypt(request);
    }

    private static void printUsage() {
        logger.warning(() -> String.format("""
                           El n\u00famero de argumentos es 4 o 5, as\u00ed:
                           java -jar ElGamalCipher-1.1.0.jar public_Key_file_name plain_votes_file_name ciphered_votes_file_name -(hw/sw) [-p]"""));
    }
}
