package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.eio.ByteTree;
import java.io.BufferedReader;
import java.io.File;
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
    
    // Evita la creación de instancias
    private Tools() {
        throw new UnsupportedOperationException("Esta clase no debe ser instanciada.");
    }
    
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

    public static void serialize(ElGamalCipheredVote[] cipheredTexts, String outputPath) {
        if (cipheredTexts.length == 0) {
            logger.warning(() -> String.format("Sin elementos a escribir."));
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
            logger.info(() -> String.format("Se escribieron %d %s %s", cipheredTexts.length, " votos cifrados en ", outputPath));
        } catch (IOException ex) {
            logger.severe(() -> ex.toString());
        }
    }

    public static String[] readSingleVotesFromFile(String ruta) {
        File file = new File(ruta);
        if (!file.exists()) {
            logger.severe(() -> String.format("El archivo no existe: %s", ruta));
            return new String[0];
        }
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(ruta))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                if (!linea.trim().isEmpty()) {
                    lineas.add(linea.trim());
                }
            }
        } catch (IOException ex) {
            logger.severe(() -> ex.toString());
            return new String[0];
        }
        return lineas.toArray(String[]::new);
    }

    public static String[][] readVectorVotesFromFile(String path, int numberOfKeys) {
        File file = new File(path);
        if (!file.exists()) {
            logger.severe(() -> String.format("El archivo no existe: %s", path));
            return new String[0][0];
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        } catch (IOException ex) {
            logger.severe(() -> ex.toString());
            return new String[0][0];
        }
        
        if (lines.isEmpty()) {
            logger.warning(() -> String.format("El archivo está vacío: %s", path));
            return new String[0][0];
        }

        int lengthOfMessage = lines.get(0).length();                            // longitud de cada mensaje
        boolean isMultiple = (lengthOfMessage % numberOfKeys == 0);
        if (!isMultiple) {
            logger.severe(() -> String.format("El mensaje no es divisible por %d %s", numberOfKeys, " llave(s)"));
            return new String[0][0];
        } else {
            logger.info(() -> String.format("El mensaje es divisible por %d %s", numberOfKeys, " llave(s)"));
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