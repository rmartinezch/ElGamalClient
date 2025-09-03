package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.eio.ByteTree;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class Tools {
    
    private static final Logger logger = LogConfig.getLogger(
            null,                   // Ruta del log
            Level.INFO,             // Nivel mínimo
            true,                   // Mostrar fecha/hora
            true,                   // Mostrar nombre de la clase
            true,                   // Mostrar nivel de log
            true,                   // Mostrar número de línea
            1024 * 1024,            // Tamaño máximo: 1MB
            3,                      // Archivos de respaldo
            true                    // También mostrar en consola
    );
    /*
    public static String base64Encoder(PGroupElement codificado) {
        ByteTreeBasic btb = codificado.toByteTree();
        String base64 = Base64.getEncoder().encodeToString(btb.toByteArray());
        return base64;
    }*/
/*
    public static String HexEncoder(PGroupElement codificado) {
        return codificado.toByteTree().toHexString();
    }*/
/*
    public static void hexFormatReader(byte[] serialized) {
        for (int j = 0; j < serialized.length; j++) {
            System.out.print(String.format("%02x", serialized[j]));
            if ((j + 1) % 16 == 0) {
                System.out.println();
            }
        }
        System.out.println();
    }*/
/*
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
    }*/

    public static void serialize(ElGamalCipheredVote[] cipheredTexts, String outputPath) {
        if (cipheredTexts.length == 0) {
            logger.warning("Sin elementos a escribir.");
            return;
        }

        try (PrintWriter out = new PrintWriter(new FileWriter(outputPath))) {
            for (ElGamalCipheredVote cipheredText : cipheredTexts) {
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
            logger.info("Se escribieron " + cipheredTexts.length + " votos cifrados en " + outputPath);
        } catch (IOException ex) {
            logger.severe(ex.toString());
        }
    }
/*
    public static void serialize(ByteTree cipheredText, String outputPath) {
        // Serializar
        byte[] serialized = new byte[(int) cipheredText.totalByteSize()];
        cipheredText.toByteArray(serialized, 0);

        // Escribir en formato nativo
        nativeFormatWriter(serialized, outputPath);
    }*/

    public static String[] readSingleVotesFromFile(String ruta) {
        File file = new File(ruta);
        if (!file.exists()) {
            logger.severe("El archivo no existe: " + ruta);
            return null;
        }
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(ruta))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                if (!linea.trim().isEmpty()) {
                    lineas.add(linea.trim());
                }
            }
        } catch (FileNotFoundException ex) {
            logger.severe(ex.toString());
        } catch (IOException ex) {
            logger.severe(ex.toString());
        }
        return lineas.toArray(String[]::new);
    }

    public static String[][] readVectorVotesFromFile(String path, int numberOfKeys) {
        File file = new File(path);
        if (!file.exists()) {
            logger.severe("El archivo no existe: " + path);
            return null;
        }
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
            logger.severe("El mensaje no es divisible por " + numberOfKeys + " llave(s)");
            return null;
        } else {
            logger.info("El mensaje es divisible por " + numberOfKeys + " llave(s)");
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