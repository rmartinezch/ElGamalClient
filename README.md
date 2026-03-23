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

## Estructura del repositorio

- `native/`: fuentes compartidas de `vec`, `gmpmee`, `vecj` y `vmgj` usadas para compilar insumos nativos en Android y Windows.
- `platform/`: proyectos fuente de plataforma que producen artefactos canónicos del cifrador.
- `scripts/`: scripts de compilación y empaquetado separados por sistema operativo.
- `tools/`: interfaces técnicas y herramientas auxiliares separadas por sistema operativo.
- `workflow/`: estaciones de votación y flujos consumidores del cifrador.
- `dist/`: artefactos finales listos para distribución.

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
Las dependencias `com.verificatum` usadas por este proyecto no están en
Maven Central. En esta rama se puede poblar un repositorio Maven local
del proyecto ejecutando:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\bootstrap-verificatum.ps1
```

En Ubuntu/Linux, la validación de prerequisitos locales se hace con:

```bash
./scripts/ubuntu/entorno/bootstrap-verificatum.sh
```

En Windows, el bootstrap genera e instala en `.mvn/local-repo`:

*   `com.verificatum:verificatum-vmgj:1.3.0`
*   `com.verificatum:verificatum-vecj:2.2.0`
*   `com.verificatum:verificatum-vcr-vmgj-vecj:3.1.0`

### Soporte de Plataforma
*   Ubuntu: validado funcionalmente con RNG de `RandomDevice`:
    `-sw` usa `/dev/urandom` y `-hw` usa TrueRNG (`/dev/TrueRNG0` por
    defecto, configurable).
*   Windows x64: la aplicación ya detecta plataforma, busca bibliotecas
    nativas por layout local (`prebuilt/windows-x64`) y por layout
    portable (`libs/windows-x64`).
    `-sw` usa `SecureRandom` y `-hw` usa TrueRNG por puerto serie
    (`COMx`) si el dispositivo USB ya expone un puerto serial en
    Windows; si no está disponible, vuelve a `SecureRandom`.
*   Otros sistemas operativos: usan `SecureRandom` en `-sw` y `-hw`.
*   La compilacion nativa Windows queda cerrada con
    `vecj-2.2.0.dll` y `vmgj-1.3.0.dll` en `prebuilt/windows-x64`.
*   `verificatum-vmgj` requiere un ajuste de 64 bits en su JNI:
    los punteros se transportan como `jlong` usando `intptr_t`.

### Scripts por Plataforma
*   `scripts/windows`: scripts PowerShell y `.bat` para build, empaquetado
    y pruebas remotas en Windows.
*   `scripts/ubuntu`: scripts `.sh` para validación de prerequisitos,
    compilación, ejecución local y empaquetado portable en Ubuntu.
*   `scripts/android`: scripts `.sh` para chequeo de entorno,
    compilación del `AAR` Android del cifrador y empaquetado de sus
    prebuilts JNI.

### Compilación
```bash
mvn -q -version
./scripts/ubuntu/entorno/bootstrap-verificatum.sh
mvn clean package -DskipTests
```

### Compilacion Nativa Windows
Con los fuentes locales de Verificatum disponibles, el repositorio puede
construir las DLL JNI de Windows x64 con:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\build-native-windows.ps1
```

El script:

*   instala `MSYS2 UCRT64` si no existe
*   compila `verificatum-vec` y `verificatum-gmpmee` como librerias
    estaticas locales
*   compila `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
*   deja ambas DLL en `prebuilt/windows-x64`
*   ejecuta una prueba minima JNI en Windows

### Ejecución
```bash
java -jar prebuilt/java/ElGamalCipher-1.1.0.jar \
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
4.  `-sw` / `-hw`: Seleccionar modalidad RNG (ver reglas por plataforma).
5.  `-p` (Opcional): Mostrar barra de progreso.

### RNG y Dispositivo por Hardware
*   En `Ubuntu`:
    *   `-sw` usa `RandomDevice()` (`/dev/urandom`).
    *   `-hw` usa TrueRNG por:
        *   propiedad JVM `-Delgamal.rng.device=<ruta>`
        *   variable de entorno `ELGAMAL_RNG_DEVICE`
        *   valor por defecto `/dev/TrueRNG0`
    *   si el dispositivo TrueRNG no existe o no es legible, vuelve a
        `RandomDevice()` (`/dev/urandom`).
*   En `Windows`:
    *   `-sw` usa `PlatformRandomSource` basado en `SecureRandom`.
    *   `-hw` usa TrueRNG por:
        *   autodetección de puerto serie USB por `VID:PID 04D8:F5FE`
        *   o propiedad JVM `-Delgamal.rng.device=COMx`
        *   o variable de entorno `ELGAMAL_RNG_DEVICE=COMx`
    *   no requiere crear `/dev/TrueRNG0`; solo que el driver del
        dispositivo ya lo exponga como puerto `COM`.
    *   si el puerto no está disponible o no puede abrirse, vuelve a
        `SecureRandom`.
*   En `macOS`, `Android` y otros sistemas:
    *   `-sw` y `-hw` usan `PlatformRandomSource` basado en
        `SecureRandom`.

### Layout Nativo por Plataforma
La aplicación busca bibliotecas nativas en este orden:

1.  `prebuilt/<os-arch>`
2.  `prebuilt`
3.  `libs/<os-arch>`
4.  `libs`

Ejemplos:

*   `prebuilt/linux-x64/libvecj-2.2.0.so`
*   `prebuilt/windows-x64/vecj-2.2.0.dll`

Si el JAR se ejecuta sin `java.library.path` y encuentra un layout
válido local, se relanza automáticamente con la ruta correcta.

**Ejemplo:**
```bash
java -jar ElGamalCipher.jar publicKey votos.txt cifrados.txt -sw -p
```

### Empaquetado Windows
Con el JAR ya compilado y con `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
presentes en `prebuilt/windows-x64`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\package-windows.ps1
```

### Empaquetado Portable Ubuntu
Para generar una imagen portable en Linux:

```bash
./scripts/ubuntu/empaquetado/build-cifrador-portable.sh
```

Salida esperada:

*   `dist/linux/image/Cifrador/Cifrador`
*   `dist/linux/image/Cifrador/runtime`
*   `dist/linux/image/Cifrador/app`
*   `dist/linux/image/Cifrador/libs/linux-x64` (o `linux-arm64`)

El script:

*   compila el JAR con Maven
*   copia runtime Java embebido
*   copia JNI de Verificatum (`libvecj`, `libvmgj`)
*   copia dependencias nativas transitivas detectadas en `/usr/local/lib`
  (por ejemplo `libvec.so.0`, `libgmpmee.so.0`)
*   genera launcher `Cifrador` con `LD_LIBRARY_PATH` y
  `java.library.path` relativos

### Uso del launcher portable Ubuntu

```bash
./dist/linux/image/Cifrador/Cifrador \
  recursos/publicKey \
  recursos/shuffled_votes.txt \
  salida/ciphertexts_ext \
  -sw
```

### Empaquetado TAR.GZ portable Ubuntu

```bash
./scripts/ubuntu/empaquetado/package-cifrador-portable.sh
```

Salida esperada:

*   `dist/linux/Cifrador-1.1.0-linux-x64-portable.tar.gz`

### Inicio Fase 2 Android
Se reorganizó el proyecto Android del cifrador en `platform/android/`
para separarlo de las estaciones y de las herramientas de diagnóstico.

Comandos base:

```bash
./scripts/android/entorno/doctor.sh
./scripts/android/compilacion/build-cifrador.sh
```

### Empaquetado Portable Android
Para generar una imagen portable Android en `dist/`:

```bash
./scripts/android/empaquetado/build-cifrador-portable.sh
```

Salida esperada:

*   `dist/android/image/Cifrador/aar/Cifrador-android-debug.aar`
*   `dist/android/image/Cifrador/jniLibs/arm64-v8a/libvecj-2.2.0.so`
*   `dist/android/image/Cifrador/jniLibs/arm64-v8a/libvmgj-1.3.0.so`
*   `dist/android/image/Cifrador/jniLibs/x86_64/libvecj-2.2.0.so`
*   `dist/android/image/Cifrador/jniLibs/x86_64/libvmgj-1.3.0.so`
*   `dist/android/image/Cifrador/metadata/checksums.sha256`

El script:

*   compila el `AAR` Android del cifrador desde `platform/android`
*   exporta los prebuilts JNI de Android por ABI
*   copia el `AAR` y las `jniLibs` a `dist/android/image/Cifrador`
*   genera checksums SHA-256

### Sobre `target/` y `prebuilt/java`

`target/` sigue siendo necesario, pero solo como salida transitoria de Maven.
Ahí aparecen clases compiladas, reportes de pruebas y el `jar` recién generado.

El artefacto Java canónico para consumo posterior queda en:

*   `prebuilt/java/ElGamalCipher-1.1.0.jar`

Ese `jar` es compartido por Ubuntu y Windows porque es bytecode Java común.
Lo que sí cambia por sistema operativo son las bibliotecas JNI:

*   `prebuilt/linux-x64/*.so`
*   `prebuilt/windows-x64/*.dll`

Android es distinto: no consume ese `jar` directamente como artefacto final de integración,
porque necesita un `AAR` con `jniLibs`, manifiesto y metadatos Gradle. Por eso existe
`platform/android` como proyecto fuente específico de Android.

### Empaquetado ZIP portable Android

```bash
./scripts/android/empaquetado/package-cifrador-portable.sh
```

Salida esperada:

*   `dist/android/Cifrador-1.1.0-android-portable.zip`

Detalles de la fase:

*   [platform/android/README.md](/home/willy/cifradorM/platform/android/README.md)
*   [tools/android/README.md](/home/willy/cifradorM/tools/android/README.md)
*   [docs/plan-fase2-android.md](/home/willy/cifradorM/docs/plan-fase2-android.md)

### Build reproducible de `Cifrador.exe`
Para compilar el ejecutable Windows portable desde este repo:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\empaquetado\build-cifrador-portable.ps1
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
powershell -ExecutionPolicy Bypass -File .\scripts\windows\package-cifrador-portable.ps1
```

Salida esperada:

*   `dist/windows/Cifrador-1.1.0-windows-x64-portable.zip`

Si quieres forzar la reconstrucción de `Cifrador.exe` antes de crear el
ZIP:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\package-cifrador-portable.ps1 -RebuildExe
```

### Prueba remota automatizada con Verificatum Linux
La prueba completa de mezcla de `1` party usando `Cifrador.exe` se puede
ejecutar con:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\test-remote-verificatum-mix.ps1 `
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
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\test-remote-verificatum-mix.ps1 `
    -Password "123456." `
    -SshHost CHUWIN11
```

Para ejecutar la misma prueba remota usando TrueRNG en Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\test-remote-verificatum-mix.ps1 `
    -Password "123456." `
    -SshHost CHUWIN11 `
    -RngMode hw `
    -RngDevice COM6
```

Tambien existe un wrapper `.bat` para consola o doble clic:

```bat
.\scripts\windows\test-remote-verificatum-mix.bat -Password "123456." -SshHost CHUWIN11
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
Estado Android al 2026-03-09:

*   JNI Android validado para `x86_64` y `arm64-v8a`
*   cifrado real ejecutado en Android
*   validación híbrida completada: Android cifra, Linux mezcla y
    descifra, y el conjunto de votos coincide en distinto orden

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
