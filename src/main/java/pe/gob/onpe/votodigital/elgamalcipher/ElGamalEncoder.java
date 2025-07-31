package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
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
    private final int numberOfKeys;

    public ElGamalEncoder(ElGamalPublicKey publicKey) {
        this.vectorial = publicKey.isVectorial();
        if (this.vectorial) {
            this.ecqGroup = null;
            this.ppGroup = publicKey.getPPGroup();
            this.baseGroups = publicKey.getBaseGroups();
            System.out.println("La llave ElGamal es vectorial.");
        } else {
            this.ecqGroup = publicKey.getECqGroup();
            this.ppGroup = null;
            this.baseGroups = null;
            System.out.println("La llave ElGamal es simple.");
        }
        this.numberOfKeys = publicKey.getNumberOfKeys();
    }

    /**
     *
     * @param vectorVote
     * @return
     */
    public PGroupElement encodeVote(String[] vectorVote) {
        if (vectorVote.length != numberOfKeys) {
            throw new IllegalArgumentException("Número de campos (" + vectorVote.length + ") no coincide con keywidth (" + numberOfKeys + ").");
        }

        PGroupElement[] encodedElements = new PGroupElement[vectorVote.length];
        for (int i = 0; i < vectorVote.length; i++) {
            ECqPGroup g;
            if (vectorial) {
                g = (ECqPGroup) baseGroups[i];
            } else {
                g = ecqGroup;
            }
            byte[] msgBytes = vectorVote[i].getBytes(StandardCharsets.UTF_8);
            if (msgBytes.length > g.getEncodeLength()) {
                throw new IllegalArgumentException("Componente " + i + " demasiado largo para codificar.");
            }
            encodedElements[i] = g.encode(msgBytes, 0, msgBytes.length);
        }

        PGroupElement vectorElement;
        if (vectorial) {
            vectorElement = ppGroup.product(encodedElements);
        } else {
            vectorElement = encodedElements[0];
        }
//        System.out.println(showEncodedVector(vectorVote, encodedElements));
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