package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.crypto.RandomSource;
import com.verificatum.eio.ByteTree;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.EIOException;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author rmartinezch
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("LD_LIBRARY_PATH:\n" + System.getenv("LD_LIBRARY_PATH"));
        System.out.println("java.library.path:\n" + System.getProperty("java.library.path"));

        System.out.println("Paso 1: == Leyendo la llave pública ElGamal ==");
        String mainPath = "/home/rmartinezch/verificatum/eleccion05/01/";
        //String mainPath = "/home/rmartinezch/verificatum-vmn-3.1.0-full/verificatum-vmn-3.1.0/demo/mixnet/mydemodir/Party01/";
        String publicKeyName = "publicKey";
        ElGamalPublicKey publicKey = new ElGamalPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        System.out.println("Paso 2: == Codificando el mensaje plano ==");
        // codificación
        ElGamalCoder coder = new ElGamalCoder(publicKey.getECqGroup());

        /*
        String mensaje;
        PGroupElement codificado;
        mensaje = "0000000000000000000000000027";
        codificado = coder.encoder(mensaje);
        System.out.println("Mensaje codificado Hex:\n" + Tools.HexEncoder(codificado));
        if (coder.verifyCodedMessage(codificado)) {
            System.out.println("iguales");
        } else {
            System.out.println("diferentes");
        }

        mensaje = "0000000000000000000000000072";
        codificado = coder.encoder(mensaje);
        System.out.println("Mensaje codificado Hex:\n" + Tools.HexEncoder(codificado));
        //*/
        String[] messages = {
            //            "0000000000000000000000000027",
            //            "0000000000000000000000000072",
            //            "0000000000000000000000000008",
            //            "0000000000000000000000000023",
            //            "0000000000000000000000000041",
            "0000000000000000000000000001"};

        PGroupElement[] codificados = new PGroupElement[messages.length];
        // codificando mensajes
        int i = 0;
        for (String message : messages) {
            codificados[i] = coder.encoder(message);
            i++;
        }
        // verificando que los mensajes codificados han sido correctamente creados
        for (PGroupElement codificado : codificados) {
            System.out.println(Tools.HexEncoder(codificado));
            if (coder.verifyCodedMessage(codificado)) {
                System.out.println("Esta codificación es decodificable.");
            }
        }

        // Integración con un Generador de números aleatorios verdadero de hardware con interfaz USB, solo para JAVA
        boolean trueRNG = true;
        String device;
        File rngDevice;
        RandomSource randomSource;
        if (trueRNG) {
            device = "/dev/TrueRNG0";                   // Dispositivo de linux donde está indexado el generador de hardware USB
            rngDevice = new File(device);
            randomSource = new RandomDevice(rngDevice); // Representación del dispositivo en el formato de Verificatum
        } else {
            randomSource = new RandomDevice();          // por defecto /dev/urandom
        }
        System.out.println("randomSource.toByteTree().toHexString(): " + randomSource.toByteTree().toHexString());

        // Desde aquí randomSource.toByteTree().toHexString() sabemos que randomSource tiene una hoja ByteTree
        // Obtener representación ByteTreeBasic
        ByteTreeBasic btb = randomSource.toByteTree();
        // Realizar cast explícito a ByteTree
        if (!(btb instanceof ByteTree)) {
            throw new IllegalStateException("El objeto no es un ByteTree.");
        }
        ByteTree bt = (ByteTree) btb;
        try {
            String deviceFromByteTree = ByteTree.byteTreeToString(bt);
            System.out.println("deviceFromByteTree: " + deviceFromByteTree);
        } catch (EIOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }

        System.out.println("Paso 3: == Cifrando el mensaje codificado con ElGamal ==");
        // cifrando los mensajes codificados
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        ElGamalCipheredText[] cipheredTexts = new ElGamalCipheredText[codificados.length];

        i = 0;
        for (PGroupElement codificado : codificados) {
            cipheredTexts[i] = cipher.encrypt(codificado, randomSource);
            System.out.println(cipheredTexts[i].toString());
            System.out.println(cipheredTexts[i].toHexString());
            i++;
        }

        // serializar
        String outputPath = mainPath + "ciphertexts_ext2";
        Tools.serialize(cipheredTexts[0].toByteTree(), outputPath);
    }

}
