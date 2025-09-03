package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.EIOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class represents a ciphered vote
 * @author rmartinezch
 */
public class ElGamalCipheredVote {

    private final PGroupElement c1;
    private final PGroupElement c2;
    
    private static final Logger logger = LogConfig.getLogger(
            null,
            Level.INFO,
            true,
            true,
            true,
            true,
            1024 * 1024,
            3,
            true
    );

    public ElGamalCipheredVote(PGroupElement c1, PGroupElement c2) {
        this.c1 = c1;
        this.c2 = c2;
    }

    public PGroupElement getC1() {
        return c1;
    }

    public PGroupElement getC2() {
        return c2;
    }

    public ByteTree toByteTree() {
        ByteTree root = null;
        try {
            root = new ByteTree(
                    //                (ByteTree) c1.toByteTree(),
                    //                (ByteTree) c2.toByteTree()
                    c1.toByteTree().getByteTreeReader().readByteTree(),
                    c2.toByteTree().getByteTreeReader().readByteTree()
            );
        } catch (EIOException ex) {
            logger.severe("No se puede instanciar el ByteTree desde los componentes facilitados (c1, c2):\n" + ex.toString());
        }
        return root;
    }

    public String toHexString() {
        return this.toByteTree().toHexString();
    }

    @Override
    public String toString() {
        return "CipheredText:\nc1 = " + c1 + "\nc2 = " + c2;
    }
}