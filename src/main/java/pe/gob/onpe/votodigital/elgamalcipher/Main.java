/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PPGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.eio.ByteTreeReader;
import com.verificatum.eio.ByteTreeBasic;
import com.verificatum.eio.ByteTreeReaderF;
import com.verificatum.eio.ByteTreeWriterF;
import com.verificatum.eio.ByteTreeF;
import com.verificatum.eio.EIOException;
import com.verificatum.eio.EIOError;
import com.verificatum.eio.ByteTreeContainer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            "0000000000000000000000000027",
            "0000000000000000000000000072",
            "0000000000000000000000000008",
            "0000000000000000000000000023",
            "0000000000000000000000000041",
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
            if(coder.verifyCodedMessage(codificado)){
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
            System.out.println(cipheredTexts[i].toString());
            i++;
        }
        
        // serializar
        String outputPath = mainPath + "new_ciphertexts";
        //exportarCiphertexts(ciphertexts, outputPath);
        //exportarCiphertextsParaVerificatum(ciphertexts, group, outputPath);
        /*
        exportarCiphertextsVMNCompatible(ciphertexts, outputPath);
        //verificarCiphertextsVMNCompatibles(outputPath);
        verificarCiphertexts(new File(outputPath));
        //verificarEstructuraByteTree(outputPath);
         */
    }

    public static void verificarEstructuraByteTree(String pathArchivo) {
        try {
            File archivo = new File(pathArchivo);
            ByteTreeReaderF reader = new ByteTreeReaderF(archivo);

            System.out.println("¿Es hoja?: " + reader.isLeaf());
            System.out.println("Número de hijos en el root: " + reader.getRemaining());

            int i = 0;
            while (reader.getRemaining() > 0) {
                ByteTreeReader hijo = reader.getNextChild();

                System.out.println("  Hijo #" + i + ": ¿es hoja? " + hijo.isLeaf() + " | hijos internos: " + hijo.getRemaining());

                if (!hijo.isLeaf()) {
                    ByteTreeReader c1 = hijo.getNextChild();
                    ByteTreeReader c2 = hijo.getNextChild();

                    System.out.println("    c1: ¿es hoja? " + c1.isLeaf() + ", longitud: " + c1.getRemaining());
                    System.out.println("    c2: ¿es hoja? " + c2.isLeaf() + ", longitud: " + c2.getRemaining());
                }

                i++;
            }

            reader.close();
        } catch (EIOError | EIOException ex) {
            ex.printStackTrace();
        }
    }

    public static void verificarCiphertexts(String path) {
        ByteTreeReaderF reader = new ByteTreeReaderF(new File(path));
        int index = 0;

        while (true) {
            try {
                ByteTreeReader ctReader = reader.getNextChild();  // contenedor (c1, c2)
                if (!ctReader.isLeaf()) {
                    ByteTreeReader c1Reader = ctReader.getNextChild();
                    ByteTreeReader c2Reader = ctReader.getNextChild();
                    System.out.println("✅ Ciphertext #" + index + " leído correctamente (hijos: 2)");
                }
                index++;
            } catch (EIOException e) {
                System.out.println("📦 Total de ciphertexts leídos: " + index);
                break;
            }
        }
    }

    public static void verificarCiphertextsVMNCompatibles(String path) {
        try (ByteTreeReaderF readerF = new ByteTreeReaderF(new File(path))) {
            int index = 0;
            while (true) {
                try {
                    ByteTreeReader container = readerF.getNextChild(); // Lee el siguiente ciphertext
                    if (container == null) {
                        break;
                    }

                    if (container.isLeaf()) {
                        System.out.println("❌ Error: Ciphertext #" + index + " es hoja, esperado contenedor.");
                        break;
                    }

                    ByteTreeReader c1Reader = container.getNextChild();
                    ByteTreeReader c2Reader = container.getNextChild();

                    System.out.println("✅ Ciphertext #" + index + " leído correctamente. Tamaño C1: "
                            + c1Reader.getRemaining() + ", Tamaño C2: " + c2Reader.getRemaining());

                    index++;
                } catch (EIOException e) {
                    // Se alcanzó el final del archivo correctamente
                    System.out.println("📦 Total de ciphertexts leídos: " + index);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void verificarCiphertexts(File ciphertextsFile) {
        try {
            System.out.println("📥 Verificando estructura del archivo: " + ciphertextsFile.getAbsolutePath());

            ByteTreeF byteTreeF = new ByteTreeF(ciphertextsFile);
            ByteTreeReader rootReader = byteTreeF.getByteTreeReader();

            int total = 0;
            while (true) {
                ByteTreeReader ctReader;
                try {
                    ctReader = rootReader.getNextChild();
                } catch (EIOException e) {
                    break; // fin del árbol
                }

                if (ctReader.isLeaf()) {
                    throw new RuntimeException("❌ Se esperaba un contenedor, no una hoja.");
                }

                ByteTreeReader c1Reader = ctReader.getNextChild();
                ByteTreeReader c2Reader = ctReader.getNextChild();

                if (!c1Reader.isLeaf() || !c2Reader.isLeaf()) {
                    throw new RuntimeException("❌ c1 o c2 no son hojas.");
                }

                // Confirmamos que no hay más hijos
                try {
                    ctReader.getNextChild();
                    throw new RuntimeException("❌ Más de dos hijos encontrados en un ciphertext.");
                } catch (EIOException expected) {
                    // correcto
                }

                total++;
            }

            System.out.println("✅ Estructura válida. Total ciphertexts: " + total);

        } catch (Exception e) {
            System.err.println("❌ Error al verificar ciphertexts:");
            e.printStackTrace();
        }
    }

    public static void exportarCiphertexts(List<ElGamalCipheredText> ciphertexts, String outputPath) {
        // Crear contenedor con todos los ciphertexts como subárboles
        ByteTreeBasic[] btArray = new ByteTreeBasic[ciphertexts.size()];
        for (int i = 0; i < ciphertexts.size(); i++) {
            btArray[i] = ciphertexts.get(i).toByteTree();  // Cada uno es ByteTreeContainer
        }

        ByteTreeContainer root = new ByteTreeContainer(btArray);

        File outputFile = new File(outputPath);
        try (ByteTreeWriterF writer = new ByteTreeWriterF(1, outputFile)) {  // 1 = versión para VMN
            writer.write(root);  // CORRECTO: ByteTreeWriterF.write(ByteTreeBasic)
        } catch (IOException | EIOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }

        System.out.println("Ciphertexts exportados exitosamente a: " + outputPath);
    }

    public static void exportarCiphertextsParaVerificatum(List<ElGamalCipheredText> ciphertexts, PGroup group, String outputPath) {
        // Convertir cada par (c1, c2) en un PPGroupElement
        PGroup[] factors = new PGroup[]{group, group};
        PPGroup ppGroup = new PPGroup(factors);

        PGroupElement[] ciphertextElements = new PGroupElement[ciphertexts.size()];
        for (int i = 0; i < ciphertexts.size(); i++) {
            ElGamalCipheredText c = ciphertexts.get(i);
            ciphertextElements[i] = ppGroup.toElement(new PGroupElement[]{c.c1, c.c2});
        }

        // Serializar el array de PPGroupElements
        File outputFile = new File(outputPath);
        try (ByteTreeWriterF writer = new ByteTreeWriterF(1, outputFile)) {
            writer.write(ppGroup.toByteTree(ciphertextElements));
        } catch (IOException | EIOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }

        System.out.println("Ciphertexts exportados en formato VMN-compatible a: " + outputPath);
    }

    public static void exportarCiphertextsVMNCompatible(List<ElGamalCipheredText> ciphertexts, String outputPath) {
        File outputFile = new File(outputPath);

        try (ByteTreeWriterF writer = new ByteTreeWriterF(2, outputFile)) {
            for (ElGamalCipheredText ciphertext : ciphertexts) {
                writer.write(ciphertext.toByteTree()); // ByteTreeContainer(c1, c2)
            }

            System.out.println("✅ Ciphertexts exportados correctamente a: " + outputPath);
        } catch (IOException | EIOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
