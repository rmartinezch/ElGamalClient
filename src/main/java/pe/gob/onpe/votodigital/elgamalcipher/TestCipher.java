package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;

public class TestCipher {

    public static void main(String[] args) throws Exception {

        // Ruta a la llave publica
        String mainPath = "/home/rmartinezch/verificatum/eleccion03/01/";
        String pathToPublicKey = mainPath + "publicKey";
        ElGamalSinglePublicKey publicKey = new ElGamalSinglePublicKey(pathToPublicKey);

        if (!publicKey.isLoaded()) {
            System.err.println("No se pudo cargar la llave publica.");
            return;
        }

        // Paso 1: Codificar el mensaje
        ECqPGroup group = publicKey.getECqGroup();
        ElGamalSingleEncoder coder = new ElGamalSingleEncoder(group);
        PGroupElement codedMessage = coder.encoder("0000000000000000000000000000");

        if (coder.verifyCodedMessage(codedMessage)) {
            System.out.println("Mensaje codificado verificado.");
        } else {
            System.out.println("Mensaje codificado no verificado.");
        }

        // Paso 2: Cifrar el mensaje codificado
        RandomSource randomSource = new RandomDevice();
        ElGamalSingleCipher cipher = new ElGamalSingleCipher(publicKey);
        ElGamalCipheredVote ciphertext = cipher.encrypt(codedMessage, randomSource);
        /*
        // Obtener coordenadas de C1 y C2
        ByteTreeReader readerC1 = ciphertext.getC1().toByteTree().getByteTreeReader();
        ByteTreeReader readerC2 = ciphertext.getC2().toByteTree().getByteTreeReader();

        ByteTree c1x = new ByteTree(readerC1.getNextChild().read());
        ByteTree c1y = new ByteTree(readerC1.getNextChild().read());

        ByteTree c2x = new ByteTree(readerC2.getNextChild().read());
        ByteTree c2y = new ByteTree(readerC2.getNextChild().read());

        // Ensamblar estructura compatible
        ByteTree treeC1 = new ByteTree(c1x, c1y);
        ByteTree treeC2 = new ByteTree(c2x, c2y);
        ByteTree root = new ByteTree(treeC1, treeC2);
         */
        ByteTree root = new ByteTree(
                (ByteTree) ciphertext.getC1().toByteTree(),
                (ByteTree) ciphertext.getC2().toByteTree()
        );
        System.out.println("votoCifrado: " + root.toHexString());

        // Serializar
        byte[] serialized = new byte[(int) root.totalByteSize()];
        root.toByteArray(serialized, 0);

        // Escribir en formato nativo
        String outputPath = mainPath + "ciphertexts_ext2";
        Tools.nativeFormatWriter(serialized, outputPath);

        System.out.println("== Formato nativo completo en hex ==");
        Tools.hexFormatReader(serialized);
    }

}
