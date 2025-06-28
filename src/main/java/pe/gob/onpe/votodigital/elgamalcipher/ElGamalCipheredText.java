/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTreeContainer;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCipheredText {

    public final PGroupElement c1;
    public final PGroupElement c2;

    public ElGamalCipheredText(PGroupElement c1, PGroupElement c2) {
        this.c1 = c1;
        this.c2 = c2;
    }

    public ByteTreeContainer toByteTree() {
        return new ByteTreeContainer(c1.toByteTree(), c2.toByteTree());
    }

    @Override
    public String toString() {
        return "Ciphertext:\nC1 = " + c1 + "\nC2 = " + c2;
    }
}
