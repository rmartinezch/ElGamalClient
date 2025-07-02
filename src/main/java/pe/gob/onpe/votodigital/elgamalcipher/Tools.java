package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.PRGHeuristic;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    public static void nativeFormatWriter(byte[] serialized, String fullOutputPath) {
        StringBuilder hexLine = new StringBuilder();
        for (byte b : serialized) {
            hexLine.append(String.format("%02x", b));
        }

        // Escribir en un archivo en una única línea
        try (PrintWriter out = new PrintWriter(new FileWriter(fullOutputPath))) {
            out.println(hexLine.toString());
            System.out.println("Voto cifrado escrito como línea única en: " + fullOutputPath);
        } catch (IOException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public static void hexFormatReader(byte[] serialized) {
        for (int j = 0; j < serialized.length; j++) {
            System.out.print(String.format("%02x", serialized[j]));
            if ((j + 1) % 16 == 0) {
                System.out.println();
            }
        }
        System.out.println();
    }
    
    public static void serialize(ByteTree cipheredText, String outputPath) {
                // Serializar
        byte[] serialized = new byte[(int) cipheredText.totalByteSize()];
        cipheredText.toByteArray(serialized, 0);

        // Escribir en formato nativo
        nativeFormatWriter(serialized, outputPath);
    }
}
