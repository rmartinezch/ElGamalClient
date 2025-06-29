package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCoder {

    PGroup group;

    public ElGamalCoder(PGroup group) {
        this.group = group;
    }

    public PGroupElement encoder(String uncodedMessage) {
        System.out.println("uncodedMessage: " + uncodedMessage);
        // Paso 1: Convertir mensaje a bytes
        byte[] uncodedMessageInBytes = uncodedMessage.getBytes(StandardCharsets.UTF_8);
        System.out.println("uncodedMessageInBytes.length: " + uncodedMessageInBytes.length);

        // Paso 2: Validar que no exceda la capacidad de codificación
        int maxAllowedLength = group.getEncodeLength();
        System.out.println("Máxima longitud que permite " + group.toString() + ": " + maxAllowedLength);
        if (uncodedMessageInBytes.length > maxAllowedLength) {
            throw new IllegalArgumentException("Mensaje demasiado largo para ser codificado en el grupo.");
        }

        // Paso 3: Codificar usando el método encode disponible
        PGroupElement codedMessage = group.encode(uncodedMessageInBytes, 0, uncodedMessageInBytes.length);
        System.out.println("codedMessage.toString(): " + codedMessage.toString());
        System.out.println("codedMessage.toString().length(): " + codedMessage.toString().length());
        System.out.println("codedMessage.toByteTree().toHexString(): " + codedMessage.toByteTree().toHexString());
        System.out.println("codedMessage.toByteTree().toHexString().length(): " + codedMessage.toByteTree().toHexString().length());

        return codedMessage;
    }

    public boolean verifyCodedMessage(PGroupElement codedMessage) {
        boolean b = false;
        try {
            PGroupElement decoded = group.toElement(codedMessage.toByteTree().getByteTreeReader());
            System.out.println("decoded.decode(): " + new String(decoded.decode(), StandardCharsets.UTF_8));
            System.out.println("decoded.toString(): " + decoded.toString());
            b = decoded.equals(codedMessage);
        } catch (ArithmFormatException ex) {
            Logger.getLogger(ElGamalCoder.class.getName()).log(Level.SEVERE, null, ex);
        }
        return b;
    }

}
