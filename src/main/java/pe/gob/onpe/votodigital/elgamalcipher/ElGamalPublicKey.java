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
 * This class load the ElGamal public key
 * @author rmartinezch
 */
public class ElGamalPublicKey {

    private final boolean loaded;
    private final String fullPath;
    private int numberOfKeys;

    // If it is simple
    private ECqPGroup ecqGroup;
    private PGroupElement g;
    private PGroupElement y;

    // If it is vectorial
    private PPGroup ppGroup;
    private PGroup[] baseGroups;

    // The key is vectorial or not (simple)
    private boolean vectorial = false;
    
    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );
    
    public ElGamalPublicKey(String fullPathToKey) {
        this.fullPath = fullPathToKey;
        this.loaded = load();
    }

    private boolean load() {
        try {
            File file = new File(fullPath);
            if (!file.exists()) {
                logger.severe(() -> String.format("El archivo no existe: %s", fullPath));
                return false;
            }

            try (ByteTreeReaderF rootReader = new ByteTreeReaderF(file)) {
                logger.info(() -> "Lectura ByteTree de la llave pública realizada.");

                // First child: Group
                ByteTreeReader groupReader = rootReader.getNextChild();
                logger.info(() -> "Lectura del primer Child de la llave pública realizada.");
                PGroup group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);
                logger.info(() -> "Desempaquetamiento de la llave pública realizada.");

                switch (group) {
                    case ECqPGroup eCqPGroup -> {
                        // Simple key
                        vectorial = false;
                        ecqGroup = eCqPGroup;
                        numberOfKeys = 1;

                        ByteTreeReader elemsReader = rootReader.getNextChild();
                        g = ecqGroup.toElement(elemsReader.getNextChild());
                        y = ecqGroup.toElement(elemsReader.getNextChild());
                        logger.info(() -> "Llave pública simple ElGamal cargada.");
                        return true;
                    }
                    case PPGroup pPGroup -> {
                        // Vectorial key
                        vectorial = true;
                        ppGroup = pPGroup;
                        baseGroups = ppGroup.getFactors();
                        numberOfKeys = baseGroups.length;

                        ByteTreeReader elemsReader = rootReader.getNextChild();
                        g = ppGroup.toElement(elemsReader.getNextChild());
                        y = ppGroup.toElement(elemsReader.getNextChild());
                        logger.info(() -> String.format("Llave pública vectorial ElGamal cargada, keywidth: %d", numberOfKeys));
                        return true;
                    }
                    default -> {
                        logger.severe(() -> String.format("Formato de grupo desconocido: %s", group.getClass().getName()));
                        return false;
                    }
                }
            }
        } catch (EIOException | ArithmFormatException ex) {
            logger.severe(() -> String.format("La llave pública no puede ser cargada:%n%s", ex.toString()));
            return false;
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isVectorial() {
        return vectorial;
    }

    public int getNumberOfKeys() {
        return numberOfKeys;
    }

    public PGroupElement getG() {
        return g;
    }

    public PGroupElement getY() {
        return y;
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

    // It gets the multiple keys containered into the total vectorial key
    public PGroupElement[] getGFactors() {
        return g != null ? ((PPGroupElement)g).getFactors() : null;
    }

    public PGroupElement[] getYFactors() {
        return y != null ? ((PPGroupElement)y).getFactors() : null;
    }

    @Override
    public String toString() {
        if (!loaded) {
            return "La llave pública no ha sido cargada.";
        }

        if (!vectorial) {
            return "Llave pública simple:\nGrupo: " + ecqGroup + "\ng: " + g + "\ny: " + y;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Llave pública vectorial (keywidth: ").append(baseGroups.length).append("):\n");
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