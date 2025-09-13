package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.EIOException;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalSingleMain {

    private static final Logger logger = LogConfig.getLogger(
            "./logs/sistema.log",
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );

    public static void main(String[] args) {
        logger.info(() -> String.format("LD_LIBRARY_PATH:\n%s", System.getenv("LD_LIBRARY_PATH")));
        logger.info(() -> String.format("java.library.path:\n%s", System.getProperty("java.library.path")));

        logger.info(() -> "Leyendo la llave pública ElGamal.");
        String mainPath = System.getProperty("user.home") + "/Verificatum/eleccion08/01/";
        String publicKeyName = "publicKey";
        ElGamalSinglePublicKey publicKey = new ElGamalSinglePublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        logger.info(publicKey::toString);

        logger.info(() -> "Codificando los votos planos.");
        // codificación
        ElGamalSingleEncoder coder = new ElGamalSingleEncoder(publicKey.getECqGroup());

        String[] messages = Tools.readSingleVotesFromFile(mainPath + "shuffled_votes.txt");

        if (messages.length == 0) {
            return;
        }

        PGroupElement[] codificados = new PGroupElement[messages.length];
        // codificando mensajes
        int i = 0;
        for (String message : messages) {
            codificados[i] = coder.encoder(message);
            i++;
        }
        // verificando que los mensajes codificados han sido correctamente creados
        for (PGroupElement codificado : codificados) {
            logger.info(() -> codificado.toByteTree().toHexString());
            if (coder.verifyCodedMessage(codificado)) {
                logger.info(() -> "Esta codificación es decodificable.");
            }
        }

        // Integración con un Generador de números aleatorios verdadero de hardware con interfaz USB, solo para JAVA
        boolean trueRNG = true;
        String device;
        File rngDevice;
        RandomSource randomSource;
        if (trueRNG) {
            device = "/dev/TrueRNG0";                   // Dispositivo de linux donde está indexado el generador de hardware USB
            rngDevice = new File(device);
            randomSource = new RandomDevice(rngDevice); // Representación del dispositivo en el formato de Verificatum
        } else {
            randomSource = new RandomDevice();          // por defecto /dev/urandom
        }
        logger.info(() -> String.format("randomSource.toByteTree().toHexString(): %s", randomSource.toByteTree().toHexString()));

        // Desde aquí randomSource.toByteTree().toHexString() sabemos que randomSource tiene una hoja ByteTree
        // Obtener representación ByteTreeBasic
        ByteTreeBasic btb = randomSource.toByteTree();
        // Realizar cast explícito a ByteTree
        if (!(btb instanceof ByteTree)) {
            throw new IllegalStateException("El objeto no es un ByteTree.");
        }
        ByteTree bt = (ByteTree) btb;
        try {
            String deviceFromByteTree = ByteTree.byteTreeToString(bt);
            logger.info(() -> String.format("deviceFromByteTree: %s", deviceFromByteTree));
        } catch (EIOException ex) {
            logger.info(ex::toString);
        }

        logger.info(() -> "Cifrando los mensajes codificados con ElGamal.");
        // cifrando los mensajes codificados
        ElGamalSingleCipher cipher = new ElGamalSingleCipher(publicKey);
        ElGamalCipheredVote[] cipheredTexts = new ElGamalCipheredVote[codificados.length];

        i = 0;
        for (PGroupElement codificado : codificados) {
            cipheredTexts[i] = cipher.encrypt(codificado, randomSource);
            logger.info(cipheredTexts[i]::toString);
            logger.info(cipheredTexts[i]::toHexString);
            i++;
        }

        // serializar
        String outputPath = mainPath + "ciphertexts_ext2";
        Tools.serialize(cipheredTexts, outputPath);
    }
}