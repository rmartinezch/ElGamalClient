/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.PRGHeuristic;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTreeBasic;
import java.security.SecureRandom;
import java.util.Base64;

/**
 *
 * @author rmartinezch
 */
public class Tools {

    public static String base64Encoder(PGroupElement codificado) {
        ByteTreeBasic btb = codificado.toByteTree();
        String base64 = Base64.getEncoder().encodeToString(btb.toByteArray());
        return base64;
    }

    public static String HexEncoder(PGroupElement codificado) {
        return codificado.toByteTree().toHexString();
    }

    /**
     * Para producción, la semilla debe generarse de manera aleatoria y segura
     *
     * @return
     */
    public static RandomSource getRandomSource() {
        // Crea un PRGHeuristic basado en SHA-256 con una semilla fija o aleatoria
        /*
        byte[] semilla = new byte[]{
            (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD,
            (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
            (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            (byte) 0x10, (byte) 0x32, (byte) 0x54, (byte) 0x76,
            (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD,
            (byte) 0x01, (byte) 0x23, (byte) 0x45, (byte) 0x67,
            (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
            (byte) 0x10, (byte) 0x32, (byte) 0x54, (byte) 0x76};
         */
        SecureRandom sr = new SecureRandom();
        byte[] semilla = new byte[32];
        sr.nextBytes(semilla);

        PRGHeuristic prg = new PRGHeuristic();
        prg.setSeed(semilla);

        return prg;
    }
}
