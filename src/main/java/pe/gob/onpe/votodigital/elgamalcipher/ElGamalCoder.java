/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
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

    public PGroupElement encoder(String mensaje) {
        System.out.println("mensaje original: " + mensaje);
        // Paso 1: Convertir mensaje a bytes
        byte[] mensajeBytes = mensaje.getBytes(StandardCharsets.UTF_8);

        // Paso 2: Validar que no exceda la capacidad de codificación
        int maxLongitud = group.getEncodeLength();
        System.out.println("maxLongitud: " + maxLongitud);
        System.out.println("mensajeBytes.length: " + mensajeBytes.length);
        if (mensajeBytes.length > maxLongitud) {
            throw new IllegalArgumentException("Mensaje demasiado largo para ser codificado en el grupo.");
        }

        // Paso 3: Codificar usando el método encode disponible
        return group.encode(mensajeBytes, 0, mensajeBytes.length);
    }

    public boolean verifyCodedMessage(PGroupElement codedMessage) {
        boolean b = false;
        try {
            PGroupElement decoded = group.toElement(codedMessage.toByteTree().getByteTreeReader());
            b = decoded.equals(codedMessage);
        } catch (ArithmFormatException ex) {
            Logger.getLogger(ElGamalCoder.class.getName()).log(Level.SEVERE, null, ex);
        }
        return b;
    }

}
