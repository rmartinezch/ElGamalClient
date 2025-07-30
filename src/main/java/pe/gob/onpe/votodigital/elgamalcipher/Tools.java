package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    public static String[] readSingleVotesFromFile(String ruta) {
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(ruta))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                if (!linea.trim().isEmpty()) {
                    lineas.add(linea.trim());
                }
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        }
        return lineas.toArray(String[]::new);
    }

    public static String[][] readVectorVotesFromFile(String path, int numberOfKeys) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        } catch (FileNotFoundException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(Tools.class.getName()).log(Level.SEVERE, null, ex);
        }

        int lengthOfMessage = lines.get(0).length();                            // longitud de cada mensaje
        boolean isMultiple = (lengthOfMessage % numberOfKeys == 0);
        if (!isMultiple) {
            System.out.println("El mensaje no es divisible por " + numberOfKeys + " llaves");
            return null;
        } else {
            System.out.println("El mensaje es divisible por " + numberOfKeys + " llaves");
        }
        
        int numberOfFields = numberOfKeys;                                      // número de campos dentro del mensaje
        int lengthOfField = lengthOfMessage / numberOfFields;                   // longitud del campo del mensaje
        String[][] array = new String[lines.size()][numberOfFields];
        for (int i = 0; i < lines.size(); i++) {
            for (int j = 0; j < numberOfFields; j++) {
                int inicio = j * lengthOfField;
                int fin = inicio + lengthOfField;
                array[i][j] = lines.get(i).substring(inicio, fin);
            }
        }
        return array;
    }
}