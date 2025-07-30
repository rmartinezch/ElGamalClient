package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PPGroupElement;
import java.nio.charset.StandardCharsets;

/**
 *
 * @author rmartinezch
 */
public class ElGamalEncoder {

    private final boolean vectorial;
    private final ECqPGroup ecqGroup;
    private final PPGroup ppGroup;
    private final PGroup[] baseGroups;

    /**
     *
     * @param group
     */
    public ElGamalEncoder(PGroup group) {
        if (group instanceof ECqPGroup) {
            this.vectorial = false;
            this.ecqGroup = (ECqPGroup) group;
            this.ppGroup = null;
            this.baseGroups = null;
        } else if (group instanceof PPGroup) {
            this.vectorial = true;
            this.ppGroup = (PPGroup) group;
            this.baseGroups = ppGroup.getFactors();
            this.ecqGroup = null;
        } else {
            throw new IllegalArgumentException("El grupo no es ECqPGroup ni PPGroup: " + group.getClass().getName());
        }
    }

    public boolean isVectorial() {
        return vectorial;
    }

    /**
     *
     * @param message
     * @return
     */
    public PGroupElement encodeSingle(String message) {
        if (vectorial) {
            throw new IllegalStateException("Este encoder está en modo vectorial. Use encodeVector().");
        }
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        if (msgBytes.length > ecqGroup.getEncodeLength()) {
            throw new IllegalArgumentException("Mensaje demasiado largo para el grupo ECqPGroup.");
        }
        PGroupElement encoded = ecqGroup.encode(msgBytes, 0, msgBytes.length);
        System.out.println("Mensaje simple codificado: " + encoded.toString());
        return encoded;
    }

    /**
     *
     * @param messages
     * @return
     */
    public PPGroupElement encodeVector(String[] messages) {
        if (!vectorial) {
            throw new IllegalStateException("Este encoder está en modo simple. Use encodeSingle().");
        }
        if (messages.length != baseGroups.length) {
            throw new IllegalArgumentException("Número de mensajes (" + messages.length
                    + ") no coincide con keywidth (" + baseGroups.length + ").");
        }

        PGroupElement[] encodedElements = new PGroupElement[messages.length];
        for (int i = 0; i < messages.length; i++) {
            ECqPGroup g = (ECqPGroup) baseGroups[i];
            byte[] msgBytes = messages[i].getBytes(StandardCharsets.UTF_8);
            if (msgBytes.length > g.getEncodeLength()) {
                throw new IllegalArgumentException("Componente " + i + " demasiado largo para codificar.");
            }
            encodedElements[i] = g.encode(msgBytes, 0, msgBytes.length);
        }

        PPGroupElement vectorElement = (PPGroupElement) ppGroup.product(encodedElements);
        System.out.println(showEncodedVector(messages, encodedElements));
        return vectorElement;
    }

    private String showEncodedVector(String[] messages, PGroupElement[] encoded) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < encoded.length; i++) {
            sb.append("   {").append(messages[i]).append("; ")
                    .append(encoded[i].toString()).append("}\n");
        }
        sb.append("]\n");
        return sb.toString();
    }
}
