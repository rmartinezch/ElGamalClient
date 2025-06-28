package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
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
public class ElGamalPublicKey {

    PGroup group;
    PGroupElement gToX;
    String fullPath;
    boolean loaded;

    public PGroup getGroup() {
        return group;
    }

    public PGroupElement getgToX() {
        return gToX;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public ElGamalPublicKey(String fullPathToRawPublicKey) {
        this.fullPath = fullPathToRawPublicKey;
        loadPublicKey();
    }

    private void loadPublicKey() {
        if (fullPath.isEmpty()) {
            System.out.println("Ruta a la llave pública ElGamal invalida: " + fullPath);
            return;
        }
        try {
            File file = new File(fullPath);
            loaded = file.exists();
            if (!loaded) {
                System.out.println("El archivo no existe en la ruta especificada.");
                return;
            }
            // Leer archivo como árbol de bytes
            ByteTreeReaderF rootReader = new ByteTreeReaderF(file);
            // Leer primer hijo: grupo (PGroup)
            ByteTreeReader groupReader = rootReader.getNextChild();
            group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);
            // Leer segundo hijo: g^x
            // Segundo hijo: ByteTree conteniendo la llave pública
            ByteTreeReader wrapper = rootReader.getNextChild();
            ByteTreeReader leaf = wrapper.getNextChild(); // accede a la hoja interna
            gToX = group.toElement(leaf);
            System.out.println("Lectura de llave pública ElGamal completada.");
        } catch (EIOException | ArithmFormatException ex) {
            Logger.getLogger(ElGamalPublicKey.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public String toString() {
        if (loaded) {
            return "Llave pública:\nGrupo: " + group.toString() + "\nElemento g^x: " + gToX.toString();
        } else {
            return "La llave pública ElGamal no pudo ser cargada.";
        }
    }

}
