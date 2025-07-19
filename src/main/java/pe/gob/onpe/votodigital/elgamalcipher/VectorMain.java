package pe.gob.onpe.votodigital.elgamalcipher;

/**
 *
 * @author rmartinezch
 */
public class VectorMain {

    public static void main(String[] args) {
        System.out.println("Leyendo la llave pública ElGamal.");
        String mainPath = "/home/rmartinezch/verificatum/eleccion06/01/";
        String publicKeyName = "publicKey";
        ElGamalVectorPublicKey publicKey = new ElGamalVectorPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

    }
}