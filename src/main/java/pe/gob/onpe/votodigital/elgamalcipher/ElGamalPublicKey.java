package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.ECqPGroup;
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
public class ElGamalPublicKey {

    private boolean loaded;
    private final String fullPath;
    private int length;

    // Si es simple:
    private ECqPGroup ecqGroup;
    private PGroupElement g;
    private PGroupElement y;

    // Si es vectorial:
    private PPGroup ppGroup;
    private PGroup[] baseGroups;
    private PPGroupElement gVec;
    private PPGroupElement yVec;

    // Tipo detectado
    private boolean vectorial = false;

    public ElGamalPublicKey(String fullPathToKey) {
        this.fullPath = fullPathToKey;
        load();
    }

    private void load() {
        try {
            File file = new File(fullPath);
            loaded = file.exists();
            if (!loaded) {
                System.out.println("El archivo no existe: " + fullPath);
                return;
            }

            ByteTreeReaderF rootReader = new ByteTreeReaderF(file);

            // Primer hijo: grupo
            ByteTreeReader groupReader = rootReader.getNextChild();
            PGroup group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);

            if (group instanceof ECqPGroup) {
                // llave simple
                vectorial = false;
                ecqGroup = (ECqPGroup) group;
                length = 1;
                ByteTreeReader elemsReader = rootReader.getNextChild();
                g = ecqGroup.toElement(elemsReader.getNextChild());
                y = ecqGroup.toElement(elemsReader.getNextChild());
                System.out.println("Llave pública simple ElGamal cargada.");

            } else if (group instanceof PPGroup) {
                // llave vectorial
                vectorial = true;
                ppGroup = (PPGroup) group;
                length = ppGroup.getWidth();
                baseGroups = ppGroup.getFactors();

                ByteTreeReader elemsReader = rootReader.getNextChild();
                PGroupElement gRaw = ppGroup.toElement(elemsReader.getNextChild());
                PGroupElement yRaw = ppGroup.toElement(elemsReader.getNextChild());

                gVec = (PPGroupElement) gRaw;
                yVec = (PPGroupElement) yRaw;
                System.out.println("Llave pública vectorial ElGamal cargada, keywidth = " + baseGroups.length);

            } else {
                throw new RuntimeException("Formato de grupo desconocido: " + group.getClass().getName());
            }

        } catch (EIOException | ArithmFormatException ex) {
            Logger.getLogger(ElGamalPublicKey.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isVectorial() {
        return vectorial;
    }

    public int getLength() {
        return length;
    }

    public PGroupElement getG() {
        if (isVectorial()) {
            return gVec;    // automatic cast from PPGroupElement to PGroupElement
        } else {
            return g;
        }
    }

    public PGroupElement getY() {
        if (isVectorial()) {
            return yVec;    // automatic cast from PPGroupElement to PGroupElement
        } else {
            return y;
        }
    }

    // Acceso simple
    public ECqPGroup getECqGroup() {
        return ecqGroup;
    }
    
    // Acceso vectorial
    public PPGroup getPPGroup() {
        return ppGroup;
    }

    public PGroup[] getBaseGroups() {
        return baseGroups;
    }

    public PPGroupElement getGVec() {
        return gVec;
    }

    public PPGroupElement getYVec() {
        return yVec;
    }

    // Acceso a las múltiples llaves contenidas en la llave vectorial total
    public PGroupElement[] getGFactors() {
        return gVec != null ? gVec.getFactors() : null;
    }

    public PGroupElement[] getYFactors() {
        return yVec != null ? yVec.getFactors() : null;
    }

    @Override
    public String toString() {
        if (!loaded) {
            return "No se pudo cargar la llave pública.";
        }

        if (!vectorial) {
            return "Llave pública simple:\nGrupo: " + ecqGroup + "\ng: " + g + "\ny: " + y;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Llave pública vectorial (keywidth=").append(baseGroups.length).append("):\n");
            for (int i = 0; i < baseGroups.length; i++) {
                sb.append("Componente ").append(i + 1).append(":\n");
                sb.append("  Grupo base: ").append(baseGroups[i]).append("\n");
                sb.append("  g_").append(i + 1).append(": ").append(getGFactors()[i]).append("\n");
                sb.append("  y_").append(i + 1).append(": ").append(getYFactors()[i]).append("\n");
            }
            return sb.toString();
        }
    }
}
