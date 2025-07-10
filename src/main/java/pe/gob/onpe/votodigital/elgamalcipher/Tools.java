package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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

    public static void hexFormatReader(byte[] serialized) {
        for (int j = 0; j < serialized.length; j++) {
            System.out.print(String.format("%02x", serialized[j]));
            if ((j + 1) % 16 == 0) {
                System.out.println();
            }
        }
        System.out.println();
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

    public static void serialize(ElGamalCipheredText[] cipheredTexts, String outputPath) {
        if (cipheredTexts.length == 0) {
            System.out.println("Sin elementos a escribir.");
            return;
        }

        try (PrintWriter out = new PrintWriter(new FileWriter(outputPath))) {
            for (ElGamalCipheredText cipheredText : cipheredTexts) {
                // extract every ByteTree object
                ByteTree byteTree = cipheredText.toByteTree();
                byte[] serialized = new byte[(int) byteTree.totalByteSize()];
                byteTree.toByteArray(serialized, 0);
                // from bytes to hex
                StringBuilder hexLine = new StringBuilder();
                for (byte b : serialized) {
                    hexLine.append(String.format("%02x", b));
                }
                // write the line
                out.println(hexLine.toString());
            }
            System.out.println("Se escribieron " + cipheredTexts.length + " votos cifrados.");
        } catch (IOException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static void serialize(ByteTree cipheredText, String outputPath) {
        // Serializar
        byte[] serialized = new byte[(int) cipheredText.totalByteSize()];
        cipheredText.toByteArray(serialized, 0);

        // Escribir en formato nativo
        nativeFormatWriter(serialized, outputPath);
    }

}
