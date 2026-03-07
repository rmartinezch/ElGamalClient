# ElGamalClient - Cifrado de Votos Electrónicos

Este proyecto implementa el cifrado de votos electrónicos utilizando el esquema de cifrado ElGamal sobre curvas elípticas. Es una herramienta crítica diseñada para procesar grandes volúmenes de votos de manera segura y eficiente.

---

## 🚀 Mejoras y Optimizaciones

El proyecto ha sido sometido a un proceso de refactorización intensivo para mejorar drásticamente su rendimiento y usabilidad. A continuación se detalla el estado original y las mejoras implementadas.

### 📉 Estado Original (Antes)
*   **Procesamiento Secuencial:** La codificación y el cifrado de votos se realizaban uno por uno en un bucle simple.
*   **Cuello de Botella en RNG:** El acceso al generador de números aleatorios (`RandomSource`) estaba protegido por un bloque `synchronized`, obligando a los hilos (si hubiera habido) a esperar su turno, impidiendo el paralelismo real.
*   **Rendimiento:** Cifrar 10,000 votos tomaba aproximadamente **1 minuto y 22 segundos**.

### 📈 Mejoras Realizadas (Después)

#### 1. Paralelismo Masivo con Java Streams
Se reemplazaron los bucles secuenciales por **Streams Paralelos** (`Arrays.stream().parallel()`). Esto permite utilizar **todos los núcleos disponibles** del procesador simultáneamente para realizar las costosas operaciones matemáticas de exponenciación modular.

#### 2. Eliminación de Bloqueos (Lock-Free)
Para evitar que los hilos compitieran por el recurso compartido de números aleatorios, se implementó **`ThreadLocal<RandomSource>`**.
*   **Resultado:** Cada hilo tiene su propia instancia del generador de números aleatorios. Ningún hilo espera a otro, logrando una escalabilidad casi lineal.

#### 3. Barra de Progreso (`-p`)
Se añadió una funcionalidad opcional para visualizar el avance del proceso en tiempo real sin impactar el rendimiento.
*   Uso: Agregar el flag `-p` al final del comando.
*   Implementación: Hilo independiente de bajo costo y contadores atómicos `Non-blocking`.

#### 4. Gestión Inteligente de Hardware RNG (`-hw`)
Se implementó una lógica condicional para manejar dispositivos de entropía por hardware (USB TrueRNG):
*   **Modo Software (`-sw`):** Utiliza paralelismo máximo.
*   **Modo Hardware (`-hw`):** Desactiva automáticamente el paralelismo para evitar la saturación y colisiones en el dispositivo físico, garantizando la estabilidad del sistema.

---

## ⚡ Comparativa de Rendimiento (Benchmark)

Prueba realizada con **10,000 votos**:

| Métrica | Versión Original | Versión Optimizada | Mejora |
| :--- | :---: | :---: | :---: |
| **Tiempo de Ejecución** | ~1m 22s | **~10s** | **~8x más rápido** |
| **Uso de CPU** | Un solo núcleo | Multi-núcleo (100%) | Eficiencia total |

*Nota: Escalado probado exitosamente hasta 1,000,000 de votos (aprox. 17 mins).*

---

## 🛠️ Instrucciones de Uso

### Requisitos
*   Java 21 o superior
*   Maven 3.x

### Bootstrap de dependencias Verificatum
Las dependencias `com.verificatum` usadas por este proyecto no estan en
Maven Central. En esta rama se puede poblar un repositorio Maven local
del proyecto ejecutando:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-verificatum.ps1
```

Eso genera e instala en `.mvn/local-repo`:

*   `com.verificatum:verificatum-vmgj:1.3.0`
*   `com.verificatum:verificatum-vecj:2.2.0`
*   `com.verificatum:verificatum-vcr-vmgj-vecj:3.1.0`

### Soporte de Plataforma
*   Linux: validado funcionalmente en esta rama.
*   Windows x64: la aplicacion ya detecta plataforma, busca bibliotecas
    nativas por layout (`libs/windows-x64`) y usa un RNG portable basado
    en `SecureRandom`.
*   La compilacion nativa Windows queda cerrada con
    `vecj-2.2.0.dll` y `vmgj-1.3.0.dll` en `libs/windows-x64`.
*   `verificatum-vmgj` requiere un ajuste de 64 bits en su JNI:
    los punteros se transportan como `jlong` usando `intptr_t`.

### Compilación
```bash
mvn -q -version
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-verificatum.ps1
mvn clean package -DskipTests
```

### Compilacion Nativa Windows
Con los fuentes locales de Verificatum disponibles, el repositorio puede
construir las DLL JNI de Windows x64 con:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-native-windows.ps1
```

El script:

*   instala `MSYS2 UCRT64` si no existe
*   compila `verificatum-vec` y `verificatum-gmpmee` como librerias
    estaticas locales
*   compila `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
*   deja ambas DLL en `libs/windows-x64`
*   ejecuta una prueba minima JNI en Windows

### Ejecución
```bash
java -jar target/ElGamalCipher-1.1.0.jar \
     <ruta_publicKey> \
     <ruta_votos_planos> \
     <ruta_salida_cifrados> \
     -sw \
     [-p]
```

**Parámetros:**
1.  `public_Key_file_name`: Ruta al archivo de clave pública.
2.  `plain_votes_file_name`: Archivo de entrada con votos.
3.  `ciphered_votes_file_name`: Archivo de salida.
4.  `-sw` / `-hw`: Usar generador por software portable o hardware.
5.  `-p` (Opcional): Mostrar barra de progreso.

### RNG y Dispositivo por Hardware
*   `-sw` usa una implementación portable basada en `SecureRandom`.
*   `-hw` intenta usar un dispositivo configurado por:
    *   propiedad JVM `-Delgamal.rng.device=<ruta>`
    *   variable de entorno `ELGAMAL_RNG_DEVICE`
*   En Linux, si no se configura nada, intenta `/dev/TrueRNG0`.
*   Si el dispositivo no existe o no es legible, la aplicación vuelve a
    `SecureRandom`.

### Layout Nativo por Plataforma
La aplicación busca bibliotecas nativas en este orden:

1.  `libs/<os-arch>`
2.  `libs`

Ejemplos:

*   `libs/linux-x64/libvecj-2.2.0.so`
*   `libs/windows-x64/vecj-2.2.0.dll`

Si el JAR se ejecuta sin `java.library.path` y encuentra un layout
válido local, se relanza automáticamente con la ruta correcta.

**Ejemplo:**
```bash
java -jar ElGamalCipher.jar publicKey votos.txt cifrados.txt -sw -p
```

### Empaquetado Windows
Con el JAR ya compilado y con `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
presentes en `libs/windows-x64`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1
```

### Build reproducible de `Cifrador.exe`
Para compilar el ejecutable Windows portable desde este repo:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-cifrador-exe.ps1
```

Salida esperada:

*   `dist/windows/image/Cifrador/Cifrador.exe`
*   `dist/windows/image/Cifrador/runtime`
*   `dist/windows/image/Cifrador/app`
*   `dist/windows/image/Cifrador/libs/windows-x64`

El script:

*   compila el JAR con Maven
*   reconstruye las DLL JNI de Windows si faltan
*   copia un runtime Java 21 embebido
*   genera un launcher nativo `Cifrador.exe`
*   deja una carpeta autocontenida en `dist/windows/image/Cifrador`

### Uso del ejecutable generado
El ejecutable se invoca asi:

```powershell
.\dist\windows\image\Cifrador\Cifrador.exe `
    .\recursos\publicKey `
    .\recursos\shuffled_votes.txt `
    .\salida\ciphertexts_ext `
    -sw
```

### Portabilidad de la carpeta Windows
El artefacto portable actual es esta carpeta completa:

*   `dist/windows/image/Cifrador`

Si copias **toda** esa carpeta a otra ruta de un Windows x64 compatible,
el ejecutable se puede usar sin reinstalar Java ni volver a compilar,
porque el launcher busca todo de forma relativa:

*   `runtime\bin\java.exe`
*   `app\ElGamalCipher-1.1.0.jar`
*   `libs\windows-x64\vecj-2.2.0.dll`
*   `libs\windows-x64\vmgj-1.3.0.dll`

Importante:

*   lo portable es `Cifrador`, no `ElGamalCipher`
*   no copies solo `Cifrador.exe`; copia la carpeta completa
*   si existe una carpeta `dist/windows/image/ElGamalCipher`, corresponde
    al empaquetado viejo con `jpackage` y no es el flujo principal actual

### Empaquetado ZIP portable
Para generar un `.zip` portable del ejecutable autocontenido:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-cifrador-portable.ps1
```

Salida esperada:

*   `dist/windows/Cifrador-1.1.0-windows-x64-portable.zip`

Si quieres forzar la reconstrucción de `Cifrador.exe` antes de crear el
ZIP:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\package-cifrador-portable.ps1 -RebuildExe
```

### Prueba remota automatizada con Verificatum Linux
La prueba completa de mezcla de `1` party usando `Cifrador.exe` se puede
ejecutar con:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-remote-verificatum-mix.ps1 `
    -SshHost CHUWIN11 `
    -Password "123456." `
    -RebuildExe
```

El script:

*   usa `OpenSSH` nativo de Windows
*   recompila `Cifrador.exe` si hace falta o si se pasa `-RebuildExe`
*   genera una eleccion temporal en Linux
*   descarga `publicKey`
*   cifra `recursos/shuffled_votes.txt` usando `Cifrador.exe`
*   sube `ciphertexts_ext` al Linux
*   ejecuta `shuffle`, `decrypt` y `vmnv`
*   descarga `plaintexts`
*   compara localmente los votos originales contra los descifrados
*   escribe un resumen final y devuelve el control al prompt

Ejemplo validado:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-remote-verificatum-mix.ps1 `
    -Password "123456." `
    -SshHost CHUWIN11
```

Tambien existe un wrapper `.bat` para consola o doble clic:

```bat
.\scripts\test-remote-verificatum-mix.bat -Password "123456." -SshHost CHUWIN11
```

Salida final esperada:

*   `Resumen comparacion: originales=2125, descifrados=2125, mismo_conjunto=True, mismo_orden=False`
*   `Prueba remota completada correctamente.`

Artefactos de la prueba:

*   `.build/remote-mix-test-CHUWIN11/publicKey`
*   `.build/remote-mix-test-CHUWIN11/ciphertexts_ext`
*   `.build/remote-mix-test-CHUWIN11/plaintexts`
*   `.build/remote-mix-test-CHUWIN11/remote-mix.log`
*   `.build/remote-mix-test-CHUWIN11/summary.json`

### TODO
Pendiente para cerrar completamente el RNG por hardware en Windows:

*   agregar soporte operativo y documentado para un dispositivo TrueRNG
    real en Windows usando `-hw`
*   validar en un equipo Windows real qué ruta o interfaz expone el
    dispositivo para que `RandomDevice` pueda abrirlo correctamente

---

## 📜 Historial de Revisiones

### Versión 1.1.0 (Actual)
*   **Fecha:** 2026-01-08
*   **Código Limpio (Clean Code):** Refactorización completa para cumplir con **SonarQube Quality Gate A**.
    *   Reducción de complejidad cognitiva en `ElGamalMain`.
    *   Uso de **Virtual Threads** (Java 21) para tareas secundarias.
    *   Corrección de Code Smells (Manejo de recursos, Logs, Constantes).
*   **Identificación:** El binario ahora muestra su versión al inicio de la ejecución.

### Versión 1.0.0 (Optimizada)
*   **Fecha:** 2026-01-07
*   **Rendimiento:** Implementación de Streams Paralelos y ThreadLocal. Optimización 8x.
*   **Seguridad:** Corrección de concurrencia para Hardware RNG.
*   **Usabilidad:** Barra de progreso visual.

### Rama `cifradorM`
*   **Fecha:** 2026-03-06
*   **Portabilidad:** Se introduce detección de plataforma, selección de
    RNG portable, layout nativo por plataforma y base de empaquetado
    para Windows.
*   **Windows x64:** Se compilan localmente `vecj` y `vmgj`, se valida
    una corrida real con `publicKey` y `shuffled_votes.txt`, y se genera
    una `app-image` con `jpackage`.
