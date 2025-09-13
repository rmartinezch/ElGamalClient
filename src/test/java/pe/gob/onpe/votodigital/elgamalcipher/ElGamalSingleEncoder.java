package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroupElement;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalSingleEncoder {

    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );

    private final ECqPGroup eCqPGroup;

    public ElGamalSingleEncoder(ECqPGroup eCqPGroup) {
        this.eCqPGroup = eCqPGroup;
    }

    public PGroupElement encoder(String uncodedMessage) {
        logger.info(() -> String.format("uncodedMessage: %s", uncodedMessage));
        // Paso 1: Convertir mensaje a bytes
        byte[] uncodedMessageInBytes = uncodedMessage.getBytes(StandardCharsets.UTF_8);
        logger.info(() -> String.format("uncodedMessageInBytes.length: %s", uncodedMessageInBytes.length));

        // Paso 2: Validar que no exceda la capacidad de codificación
        int maxAllowedLength = eCqPGroup.getEncodeLength();
        logger.info(() -> String.format("Máxima longitud que permite %s %s %d", eCqPGroup.toString(), ":", maxAllowedLength));
        if (uncodedMessageInBytes.length > maxAllowedLength) {
            throw new IllegalArgumentException("Mensaje demasiado largo para ser codificado en el grupo.");
        }

        // Paso 3: Codificar usando el método encode disponible
        PGroupElement codedMessage = eCqPGroup.encode(uncodedMessageInBytes, 0, uncodedMessageInBytes.length);
        logger.info(() -> String.format("codedMessage.toString(): %s", codedMessage.toString()));
        logger.info(() -> String.format("codedMessage.toString().length(): %s", codedMessage.toString().length()));
        logger.info(() -> String.format("codedMessage.toByteTree().toHexString(): %s", codedMessage.toByteTree().toHexString()));
        logger.info(() -> String.format("codedMessage.toByteTree().toHexString().length(): %s", codedMessage.toByteTree().toHexString().length()));

        return codedMessage;
    }

    public boolean verifyCodedMessage(PGroupElement codedMessage) {
        boolean b = false;
        try {
            PGroupElement decoded = eCqPGroup.toElement(codedMessage.toByteTree().getByteTreeReader());
            logger.info(() -> String.format("decoded.decode(): %s", new String(decoded.decode(), StandardCharsets.UTF_8)));
            logger.info(() -> String.format("decoded.toString(): %s", decoded.toString()));
            b = decoded.equals(codedMessage);
        } catch (ArithmFormatException ex) {
            Logger.getLogger(ElGamalSingleEncoder.class.getName()).log(Level.SEVERE, null, ex);
        }
        return b;
    }

}