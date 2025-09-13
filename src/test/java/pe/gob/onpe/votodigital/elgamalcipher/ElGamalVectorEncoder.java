package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PPGroupElement;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalVectorEncoder {

    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );
    
    private final PPGroup ppGroup;
    private final PGroup[] baseGroups;

    public ElGamalVectorEncoder(PPGroup ppGroup) {
        this.ppGroup = ppGroup;
        this.baseGroups = ppGroup.getFactors();
    }

    /**
     * Codifica un vector de strings como PPGroupElement. Cada string se
     * codifica usando el grupo correspondiente G_i.
     *
     * @param uncodedMessageElements Arreglo de strings, uno por componente del
     * vector.
     * @return PPGroupElement codificado (mensaje vectorial).
     */
    public PPGroupElement encodeVector(String[] uncodedMessageElements) {
        if (uncodedMessageElements.length != baseGroups.length) {
            throw new IllegalArgumentException("Número de componentes (" + uncodedMessageElements.length + ") no coincide con keywidth (" + baseGroups.length + ").");
        }

        PGroupElement[] encodedElements = new PGroupElement[uncodedMessageElements.length];

        for (int i = 0; i < uncodedMessageElements.length; i++) {
            ECqPGroup group = (ECqPGroup) baseGroups[i];  // Asumimos ECqPGroup
            byte[] msgBytes = uncodedMessageElements[i].getBytes(StandardCharsets.UTF_8);

            if (msgBytes.length > group.getEncodeLength()) {
                throw new IllegalArgumentException("Mensaje en componente " + i + " demasiado largo para codificar.");
            }

            encodedElements[i] = group.encode(msgBytes, 0, msgBytes.length);
        }

        logger.info(() -> showEncodedVector(uncodedMessageElements, encodedElements));

        return (PPGroupElement) ppGroup.product(encodedElements);
    }

    private String showEncodedVector(String[] uncodedMessageElements, PGroupElement[] encodedElements) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < encodedElements.length; i++) {
            sb.append("   ");
            sb.append("{").append(uncodedMessageElements[i]).append("; ");
            sb.append(encodedElements[i].toString()).append("}\n");
        }
        sb.append("]\n");
        return sb.toString();
    }
}