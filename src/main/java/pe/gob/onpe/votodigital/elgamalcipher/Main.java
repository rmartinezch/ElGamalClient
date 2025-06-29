/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroupElement;

/**
 *
 * @author rmartinezch
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("LD_LIBRARY_PATH = " + System.getenv("LD_LIBRARY_PATH"));
        System.out.println("java.library.path = " + System.getProperty("java.library.path"));

        System.out.println("Paso 1: == Leyendo la llave pública ElGamal ==");
        String mainPath = "/home/rmartinezch/verificatum/eleccion03/01/";
        //String mainPath = "/home/rmartinezch/verificatum-vmn-3.1.0-full/verificatum-vmn-3.1.0/demo/mixnet/mydemodir/Party01/";
        String publicKeyName = "publicKey";
        ElGamalPublicKey publicKey = new ElGamalPublicKey(mainPath + publicKeyName);
        if (!publicKey.isLoaded()) {
            return;
        }
        System.out.println(publicKey.toString());

        System.out.println("Paso 2: == Codificando el mensaje plano ==");
        // codificación
        ElGamalCoder coder = new ElGamalCoder(publicKey.getGroup());

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
            "0000000000000000000000000017"};

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

        System.out.println("Paso 3: == Cifrando el mensaje codificado con ElGamal ==");
        // cifrando los mensajes codificados
        ElGamalCipher cipher = new ElGamalCipher(publicKey);
        ElGamalCipheredText[] cipheredTexts = new ElGamalCipheredText[codificados.length];
        i = 0;
        for (PGroupElement codificado : codificados) {
            cipheredTexts[i] = cipher.encrypt(codificado, Tools.getRandomSource());
            System.out.println("ByteArray c1: " + cipheredTexts[i].getC1().toByteArray().length);
            System.out.println("ByteArray c2: " + cipheredTexts[i].getC2().toByteArray().length);
            System.out.println(cipheredTexts[i].toString());
            i++;
        }

        // serializar
    }

}
