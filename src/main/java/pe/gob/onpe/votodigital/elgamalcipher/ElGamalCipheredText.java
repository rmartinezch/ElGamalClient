/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCipheredText {

    private final PGroupElement c1;
    private final PGroupElement c2;

    public ElGamalCipheredText(PGroupElement c1, PGroupElement c2) {
        this.c1 = c1;
        this.c2 = c2;
    }

    public PGroupElement getC1() {
        return c1;
    }

    public PGroupElement getC2() {
        return c2;
    }

    @Override
    public String toString() {
        return "Ciphertext:\nC1 = " + c1 + "\nC2 = " + c2;
    }
}
