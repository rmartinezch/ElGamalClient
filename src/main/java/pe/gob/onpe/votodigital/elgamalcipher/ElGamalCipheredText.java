package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTree;

/**
 *
 * @author rmartinezch
 */
public class ElGamalCipheredText {

    private final PGroupElement c1;
    private final PGroupElement c2;

    public ElGamalCipheredText(PGroupElement c1, PGroupElement c2) {
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
        ByteTree root = new ByteTree(
                (ByteTree) c1.toByteTree(),
                (ByteTree) c2.toByteTree()
        );
        return root;
    }
    
    public String toHexString(){
        return this.toByteTree().toHexString();
    }

    @Override
    public String toString() {
        return "CipheredText:\nc1 = " + c1 + "\nc2 = " + c2;
    }
}
