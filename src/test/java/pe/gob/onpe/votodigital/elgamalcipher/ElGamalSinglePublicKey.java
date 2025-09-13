package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
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
public class ElGamalSinglePublicKey {

    private ECqPGroup eCqPGroup;
    private PGroupElement g;
    private PGroupElement y;
    private final String fullPath;
    private boolean loaded;

    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            true
    );
    
    public ECqPGroup getECqGroup() {
        return eCqPGroup;
    }

    public PGroupElement getG() {
        return g;
    }

    public PGroupElement getY() {
        return y;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public ElGamalSinglePublicKey(String fullPathToRawPublicKey) {
        this.fullPath = fullPathToRawPublicKey;
        loadPublicKey();
    }

    private void loadPublicKey() {
        if (fullPath.isEmpty()) {
            logger.info(() -> String.format("Ruta a la llave pública ElGamal invalida: %s", fullPath));
            return;
        }
        try {
            File file = new File(fullPath);
            loaded = file.exists();
            if (!loaded) {
                logger.info(() -> String.format("El archivo en la ruta especificada no ha podido ser cargado: %s", fullPath));
                return;
            }
            // Leer archivo como árbol de bytes
            ByteTreeReaderF rootReader = new ByteTreeReaderF(file);
            // Leer primer hijo: grupo (PGroup)
            ByteTreeReader groupReader = rootReader.getNextChild();
            PGroup group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);
            // Verificación de tipo de grupo
            if (!(group instanceof ECqPGroup)) {
                logger.severe(() -> String.format("Se esperaba ECqPGroup, pero se encontró: %s", group.getClass().getName()));
                return;
            }
            eCqPGroup = (ECqPGroup) group;

            ByteTreeReader gensReader = rootReader.getNextChild();

            g = eCqPGroup.toElement(gensReader.getNextChild());
            y = eCqPGroup.toElement(gensReader.getNextChild());

            logger.info(() -> "Lectura de llave pública ElGamal completada.");
        } catch (EIOException | ArithmFormatException ex) {
            Logger.getLogger(ElGamalSinglePublicKey.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public String toString() {
        if (loaded) {
            return "Llave pública:\nGrupo: " + eCqPGroup.toString() + "\nElemento g: " + g.toString() + "\nElemento y: " + y.toString();
        } else {
            return "La llave pública ElGamal no pudo ser cargada.";
        }
    }

}