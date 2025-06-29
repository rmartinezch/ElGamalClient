/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeReader;

public class TestCipher {

    public static void main(String[] args) throws Exception {

        // Ruta a la llave publica
        String pathToPublicKey = "/home/rmartinezch/verificatum/eleccion03/01/publicKey";
        ElGamalPublicKey publicKey = new ElGamalPublicKey(pathToPublicKey);

        if (!publicKey.isLoaded()) {
            System.err.println("No se pudo cargar la llave publica.");
            return;
        }

        // Paso 1: Codificar el mensaje
        PGroup group = publicKey.getGroup();
        ElGamalCoder coder = new ElGamalCoder(group);
        PGroupElement codedMessage = coder.encoder("0000000000000000000000000000");
        
        if(coder.verifyCodedMessage(codedMessage)) {
            System.out.println("Mensaje codificado verificado.");
        } else {
            System.out.println("Mensaje codificado no verificado.");
        }

        // Paso 2: Cifrar el mensaje codificado
        RandomSource randomSource = new RandomDevice();
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        ElGamalCipheredText ciphertext = cipher.encrypt(codedMessage, randomSource);

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

        // Serializar
        byte[] serialized = new byte[(int) root.totalByteSize()];
        root.toByteArray(serialized, 0);

        // Escribir en formato nativo
        String outputPath = "/home/rmartinezch/verificatum/eleccion03/01/ciphertexts_ext2";
        Tools.nativeFormatWriter(serialized, outputPath);

        System.out.println("== Formato nativo completo en hex ==");
        Tools.hexFormatReader(serialized);

    }

}
