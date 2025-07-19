package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PPGroupElement;
import com.verificatum.eio.ByteTreeReader;
import com.verificatum.eio.ByteTreeReaderF;
import com.verificatum.eio.EIOException;
import com.verificatum.eio.Marshalizer;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class ElGamalVectorPublicKey {
    private PPGroup ppGroup;                     // G = ECqPGroup^κ
    private PGroup[] baseGroups;                 // G_i (todos ECqPGroup)
    private PPGroupElement g;                    // Vector de generadores
    private PPGroupElement y;                    // Vector de claves públicas
    private final String fullPath;
    private boolean loaded;

    public ElGamalVectorPublicKey(String fullPathToPublicKey) {
        this.fullPath = fullPathToPublicKey;
        loadPublicKey();
    }

    public PPGroup getPPGroup() {
        return ppGroup;
    }

    public PGroup[] getBaseGroups() {
        return baseGroups;
    }

    public PPGroupElement getG() {
        return g;
    }

    public PPGroupElement getY() {
        return y;
    }

    public PGroupElement[] getGFactors() {
        return g.getFactors(); // g_1, ..., g_κ
    }

    public PGroupElement[] getYFactors() {
        return y.getFactors(); // y_1, ..., y_κ
    }

    public boolean isLoaded() {
        return loaded;
    }
    
    private void loadPublicKey() {
        if (fullPath.isEmpty()) {
            System.out.println("Ruta inválida a la llave pública vectorial ElGamal: " + fullPath);
            return;
        }

        try {
            File file = new File(fullPath);
            loaded = file.exists();
            if (!loaded) {
                System.out.println("El archivo no existe: " + fullPath);
                return;
            }

            ByteTreeReaderF rootReader = new ByteTreeReaderF(file);

            // Leer el grupo PPGroup desde el primer hijo
            ByteTreeReader groupReader = rootReader.getNextChild();
            PGroup group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);

            if (!(group instanceof PPGroup)) {
                throw new RuntimeException("Se esperaba PPGroup, pero se encontró: " + group.getClass().getName());
            }

            ppGroup = (PPGroup) group;
            baseGroups = ppGroup.getFactors(); // cada uno debería ser ECqPGroup

            // Leer g e y como PPGroupElement
            ByteTreeReader elementReader = rootReader.getNextChild();
            PGroupElement gRaw = ppGroup.toElement(elementReader.getNextChild());
            PGroupElement yRaw = ppGroup.toElement(elementReader.getNextChild());

            if (!(gRaw instanceof PPGroupElement) || !(yRaw instanceof PPGroupElement)) {
                throw new RuntimeException("Se esperaban elementos PPGroupElement");
            }

            g = (PPGroupElement) gRaw;
            y = (PPGroupElement) yRaw;

            System.out.println("Lectura de llave pública vectorial ElGamal completada.");

        } catch (EIOException | ArithmFormatException ex) {
            Logger.getLogger(ElGamalVectorPublicKey.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    @Override
    public String toString() {
        if (!loaded) {
            return "La llave pública vectorial ElGamal no pudo ser cargada.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Llave pública vectorial (keywidth = ").append(baseGroups.length).append("):\n");
        for (int i = 0; i < baseGroups.length; i++) {
            sb.append("Componente ").append(i + 1).append(":\n");
            sb.append("  Grupo base: ").append(baseGroups[i].toString()).append("\n");
            sb.append("  Generador g_").append(i + 1).append(": ").append(getGFactors()[i].toString()).append("\n");
            sb.append("  Clave pública y_").append(i + 1).append(": ").append(getYFactors()[i].toString()).append("\n");
        }
        return sb.toString();
    }
}