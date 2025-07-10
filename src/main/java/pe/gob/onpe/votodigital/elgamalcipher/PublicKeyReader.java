package pe.gob.onpe.votodigital.elgamalcipher;

import com.verificatum.arithm.ArithmFormatException;
import com.verificatum.arithm.ECqPGroup;
import com.verificatum.arithm.PGroup;
import com.verificatum.arithm.PGroupElement;
import com.verificatum.arithm.PRing;
import com.verificatum.arithm.PRingElement;
import com.verificatum.crypto.RandomDevice;
import com.verificatum.eio.ByteTreeReader;
import com.verificatum.eio.ByteTreeReaderF;
import com.verificatum.eio.EIOException;
import com.verificatum.eio.Marshalizer;
import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 *
 * @author rmartinezch
 */
public class PublicKeyReader {

    public static void main(String[] args) {
        try {
            // === Ruta al archivo publicKey_ext_raw ===
            String path = "/home/rmartinezch/verificatum/eleccion05/01/publicKey";
            File file = new File(path);

            // === Fuente de aleatoriedad dummy ===
//            RandomSource dummyRandom = new RandomDevice();

            // === Abrimos el ByteTree binario ===
            ByteTreeReaderF rootReader = new ByteTreeReaderF(file);

            // Paso 1: Leer el grupo
            ByteTreeReader groupReader = rootReader.getNextChild();
            PGroup group = Marshalizer.unmarshalAux_PGroup(groupReader, null, 1);

            // Verificación de tipo de grupo
            if (!(group instanceof ECqPGroup)) {
                throw new RuntimeException("Se esperaba ECqPGroup, pero se encontró: " + group.getClass().getName());
            }
            System.out.println("group: " + group.toString());
            ECqPGroup ecGroup = (ECqPGroup) group;

            // Paso 2: Leer {g, y}
            ByteTreeReader gensReader = rootReader.getNextChild();

            PGroupElement g = ecGroup.toElement(gensReader.getNextChild());  // correcto
            PGroupElement y = ecGroup.toElement(gensReader.getNextChild());  // correcto

            System.out.println(">> Generador g: " + g.toString());
            System.out.println(">> Clave pública y: " + y.toString());

            // === Paso 3: Codificar mensaje a punto M ===
            String mensaje = "0000000000000000000000000001";
            byte[] mensajeBytes = mensaje.getBytes(StandardCharsets.UTF_8);

            // Paso 3: Codificar usando el método encode disponible
            PGroupElement codedMessage = ecGroup.encode(mensajeBytes, 0, mensajeBytes.length);
            System.out.println("codedMessage.toString(): " + codedMessage.toString());
            System.out.println("codedMessage.toString().length(): " + codedMessage.toString().length());
            System.out.println("codedMessage.toByteTree().toHexString(): " + codedMessage.toByteTree().toHexString());
            System.out.println("codedMessage.toByteTree().toHexString().length(): " + codedMessage.toByteTree().toHexString().length());

            // === Paso 4: Generar k ∈R [1, n-1] y cifrar ===
            PRing ring = ecGroup.getPRing();
            PRingElement k = ring.randomElement(new RandomDevice(), 256);

            // Cálculo de C1 = k * g
            PGroupElement C1 = g.exp(k);

            // Cálculo de C2 = M + k * y
            PGroupElement kY = y.exp(k);
            PGroupElement C2 = codedMessage.mul(kY);

            System.out.println(">> C1: " + C1.toString());
            System.out.println(">> C2: " + C2.toString());

            // serializar
            String outputPath = "/home/rmartinezch/verificatum/eleccion05/01/ciphertexts_ext3";
            Tools.serialize(new ElGamalCipheredText(C1, C2).toByteTree(), outputPath);

        } catch (ArithmFormatException | EIOException | RuntimeException e) {
        }

    }
}
