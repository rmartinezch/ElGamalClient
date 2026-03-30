# ElGamalClient - Cifrado de Votos Electronicos

Implementacion del cifrado de votos electronicos usando ElGamal sobre curvas elipticas, con soporte multiplataforma para:

- `Windows x64`
- `Ubuntu/Linux`
- `Android`

El repositorio contiene tanto el cifrador como libreria reusable como estaciones de votacion de ejemplo que lo consumen.

## Indice

1. [Resumen](#resumen)
2. [Arquitectura](#arquitectura)
3. [Estado Actual](#estado-actual)
4. [Estructura Del Repositorio](#estructura-del-repositorio)
5. [Requisitos](#requisitos)
6. [Bootstrap De Dependencias Verificatum](#bootstrap-de-dependencias-verificatum)
7. [Artefactos Canonicos](#artefactos-canonicos)
8. [Compilacion](#compilacion)
9. [Integracion Externa Como Libreria](#integracion-externa-como-libreria)
10. [Uso Del Cifrador Desde CLI](#uso-del-cifrador-desde-cli)
11. [RNG Y Soporte De Hardware](#rng-y-soporte-de-hardware)
12. [Layout Nativo Y Carga De JNI](#layout-nativo-y-carga-de-jni)
13. [Empaquetado Y Distribucion](#empaquetado-y-distribucion)
14. [Pruebas Y Validaciones](#pruebas-y-validaciones)
15. [Consumidores De Ejemplo En Este Repo](#consumidores-de-ejemplo-en-este-repo)
16. [Resumen De Rendimiento](#resumen-de-rendimiento)
17. [Historial Breve](#historial-breve)

## Resumen

El cifrador se distribuye con filosofias distintas segun plataforma:

- `Windows` y `Ubuntu`:
  - artefacto canonico = `jar + librerias nativas JNI`
- `Android`:
  - artefacto canonico = `AAR`

La regla general del proyecto es:

- el cifrador debe poder ser consumido por cualquier interfaz externa
- las interfaces ubicadas en `workflow/` son solo consumidores de ejemplo
- ninguna aplicacion externa debe depender del codigo fuente interno del cifrador

## Arquitectura

```mermaid
flowchart TB
    Repo["Repositorio cifradorM"]

    Repo --> Core["Nucleo comun del cifrador<br/>src/ + native/"]
    Repo --> Prebuilt["Artefactos canonicos<br/>prebuilt/"]
    Repo --> Workflow["Interfaces de ejemplo<br/>workflow/"]

    Core --> WinLib["Windows<br/>jar + DLL JNI"]
    Core --> UbuntuLib["Ubuntu<br/>jar + .so JNI"]
    Core --> AndroidLib["Android<br/>AAR + jniLibs"]

    WinLib --> WinExternal["Aplicacion externa en Windows<br/>API Java o proceso java"]
    WinLib --> WinStation["Estacion Windows de ejemplo<br/>workflow/votante/windows"]

    UbuntuLib --> UbuntuExternal["Aplicacion externa en Ubuntu<br/>API Java o proceso java"]
    UbuntuLib --> UbuntuOps["Servicios o flujos externos<br/>mezcla / automatizacion"]

    AndroidLib --> AndroidExternal["Aplicacion Android externa<br/>consume el AAR"]
    AndroidLib --> AndroidStation["Estacion Android de ejemplo<br/>workflow/votante/android"]

    WinStation --> Mixer["Mezcladora Verificatum<br/>descubrimiento + handshake + public-key + ciphertexts"]
    AndroidStation --> Mixer
    Mixer -. GUI local .-> MixGUI["GUI Mezcladora<br/>workflow/votante/mezcladora"]
```

Lectura rapida del diagrama:

- `Windows`: una app externa o la estacion de ejemplo consumen el mismo cifrador como `jar + DLL`
- `Ubuntu`: una app o servicio externo consume el mismo cifrador como `jar + .so`
- `Android`: una app externa o la estacion de ejemplo consumen el mismo cifrador como `AAR`
- la mezcladora no forma parte del cifrador; es un servicio aparte al que las estaciones de voto se conectan

## Estado Actual

Puntos principales del estado actual:

- el cifrador Java se compila con `Java 21`
- `Windows` usa `ElGamalCipher-1.1.0.jar` mas `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
- `Ubuntu` usa el mismo `jar` mas `libvecj` y `libvmgj`
- `Android` expone el cifrador como libreria reusable
- la estacion de votacion Windows consume el cifrador Windows como libreria
- la estacion de votacion Android consume el cifrador Android como libreria

## Estructura Del Repositorio

- `android/`
  - modulo Android del cifrador reusable
- `dist/`
  - artefactos finales listos para distribucion
- `docs/`
  - documentacion tecnica adicional
- `native/`
  - `verificatum-jars/` repositorio Maven file-based con JARs precompilados de Verificatum (resuelto automaticamente por Maven y Gradle)
  - `verificatum-src/` fuentes de `vcr`, `vec`, `gmpmee`, `vecj` y `vmgj` (solo necesarios si se quiere recompilar desde cero)
- `prebuilt/`
  - artefactos canonicos compilados para consumo posterior
- `recursos/`
  - llaves, votos y recursos de prueba
- `scripts/`
  - scripts de compilacion, empaquetado, entorno y pruebas por plataforma
- `src/`
  - codigo Java principal del cifrador
- `tools/`
  - herramientas auxiliares separadas del flujo principal
- `workflow/`
  - estaciones de votacion y consumidores de ejemplo del cifrador
  - incluye la GUI de la mezcladora Verificatum en `workflow/votante/mezcladora`

## Requisitos

- `Java 21` o superior
- `Maven 3.x` (opcional: el proyecto incluye Maven Wrapper `mvnw` / `mvnw.cmd`)

Requisitos adicionales por plataforma:

- `Windows`
  - PowerShell
  - `MSYS2 UCRT64` si se van a recompilar DLL JNI
- `Ubuntu`
  - toolchain nativo para JNI Linux
- `Android`
  - Android SDK / Gradle para compilar el `AAR` o el consumidor Android

### Convencion De Placeholders

Para evitar ambiguedades, en este documento se usan estos placeholders:

- `<PROJECT_ROOT>`: carpeta raiz del repo `ElGamalClient` en su maquina
- `<MIXNET_ROOT>`: carpeta raiz que contiene fuentes Verificatum externas
- `<APP_JAR>`: artefacto JAR de la aplicacion consumidora
- `<PUBLIC_KEY_PATH>`: ruta del archivo `publicKey`
- `<PLAINTEXT_VOTES_PATH>`: ruta del archivo de votos planos
- `<CIPHERTEXT_OUTPUT_PATH>`: ruta de salida para `ciphertexts_ext`

### Shell Y Classpath Por Plataforma

| Plataforma | Shell recomendado | Separador de classpath |
| --- | --- | --- |
| Windows | PowerShell | `;` |
| Ubuntu/Linux | Bash | `:` |
| Android | Gradle/AGP | No aplica |

## Bootstrap De Dependencias Verificatum

Las dependencias `com.verificatum` no viven en Maven Central.

### Proyecto autocontenido (desde marzo 2026)

El proyecto ahora incluye los JARs precompilados de Verificatum en `native/verificatum-jars/`,
organizados como un repositorio Maven file-based. Tanto `pom.xml` (Maven) como
`platform/android/settings.gradle` (Gradle) apuntan a este directorio.

**Ya no es necesario ejecutar el bootstrap para compilar.** Despues de clonar:

```bash
# Ubuntu/Linux
./mvnw clean package

# Windows (sin necesidad de instalar Maven)
mvnw.cmd clean package
```

JARs incluidos en `native/verificatum-jars/`:

| Artefacto | Version | Tamaño |
|-----------|---------|--------|
| `verificatum-vcr-vmgj-vecj` | 3.1.0 | 1.7 MB |
| `verificatum-vecj` | 2.2.0 | 6.8 KB |
| `verificatum-vmgj` | 1.3.0 | 9.8 KB |

Fuentes incluidos en `native/verificatum-src/`:

| Paquete | Version | Uso |
|---------|---------|-----|
| `verificatum-vcr` | 3.1.0 | Solo si se necesita recompilar VCR desde fuentes |
| `verificatum-vecj` | 2.2.0 | Solo si se necesita recompilar VECJ desde fuentes |
| `verificatum-vmgj` | 1.3.0 | Solo si se necesita recompilar VMGJ desde fuentes |
| `verificatum-gmpmee` | 2.1.0 | Dependencia nativa de VCR |
| `verificatum-vec` | 2.5.0 | Dependencia nativa de VECJ |

### Bootstrap (solo si se necesita recompilar desde fuentes)

El bootstrap solo es necesario si se quiere recompilar los JARs de Verificatum desde codigo
fuente (por ejemplo, para aplicar parches o actualizar versiones). En uso normal,
los JARs de `native/verificatum-jars/` son suficientes.

### Rutas buscadas automaticamente (Windows)

El script busca los paquetes en este orden de prioridad. **Basta con que una sola ruta exista**;
no es necesario configurar nada mas si usas alguna de las rutas predefinidas:

```
# Prioridad 1 – dentro del propio repositorio (opcion recomendada)
<raiz-repo>\native\verificatum-src\verificatum-vcr-3.1.0
<raiz-repo>\native\verificatum-src\verificatum-vecj-2.2.0
<raiz-repo>\native\verificatum-src\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar

# Prioridad 2 – directorio mixnet hermano del proyecto
<carpeta-padre-del-repo>\mixnet\verificatum-vcr-3.1.0
<carpeta-padre-del-repo>\mixnet\verificatum-vecj-2.2.0
<carpeta-padre-del-repo>\mixnet\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar

# Prioridad 3 – convenciones de estructura de proyectos ONPE (auto-detectado por unidad)
D:\Projects\ONPE\mixnet\verificatum-vcr-3.1.0
D:\Projects\ONPE\mixnet\verificatum-vecj-2.2.0
D:\Projects\ONPE\mixnet\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar

# Prioridad 4 – convencion alternativa (guion bajo)
D:\_Proyectos\mixnet\verificatum-vcr-3.1.0
D:\_Proyectos\mixnet\verificatum-vecj-2.2.0
D:\_Proyectos\mixnet\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar
```

> **Nota:** las rutas de Prioridad 3 y 4 se generan dinamicamente segun la unidad donde
> este clonado el repositorio (puede ser `C:`, `D:`, etc.).

Si el error dice `No se pudo ubicar verificatum-vcr-3.1.0. Rutas probadas: ...` significa
que los fuentes no estan en ninguna de esas ubicaciones. Con la version actual del proyecto,
los fuentes ya estan incluidos en `native\verificatum-src\` y los JARs precompilados en
`native\verificatum-jars\`, por lo que este error no deberia ocurrir despues de un `git pull`.

### Dependencias esperadas para Windows

Para que `scripts/windows/compilacion/bootstrap-verificatum.ps1` funcione, debe existir al menos una de estas opciones:

- Opcion A (recomendada): fuentes dentro del repo
  - `native/verificatum-src/verificatum-vcr-3.1.0`
  - `native/verificatum-src/verificatum-vecj-2.2.0`
  - `native/verificatum-src/verificatum-vmgj-1.3.0/verificatum-vmgj-1.3.0.jar`
- Opcion B: directorio externo `mixnet` en ruta convencional
  - `D:\Projects\ONPE\mixnet\verificatum-vcr-3.1.0`
  - `D:\Projects\ONPE\mixnet\verificatum-vecj-2.2.0`
  - `D:\Projects\ONPE\mixnet\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar`
- Opcion C: directorio externo con ruta personalizada via variable de entorno o parametro

Importante:

- use una sola raiz `mixnet` para evitar confusiones
- el script ya no depende de rutas fijas de una maquina en particular
- si no usa `native/verificatum-src`, configure la raiz con `MIXNET_ROOT` o pase parametros explicitos

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\bootstrap-verificatum.ps1
```

Windows (forzando una raiz externa unica):

```powershell
$env:MIXNET_ROOT = 'D:\ruta\a\tu\mixnet'
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\bootstrap-verificatum.ps1
```

Windows (rutas explicitas por parametro):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\bootstrap-verificatum.ps1 `
    -VcrSourceRoot  'D:\ruta\a\tu\mixnet\verificatum-vcr-3.1.0' `
    -VecjSourceRoot 'D:\ruta\a\tu\mixnet\verificatum-vecj-2.2.0' `
    -VmgjJarPath    'D:\ruta\a\tu\mixnet\verificatum-vmgj-1.3.0\verificatum-vmgj-1.3.0.jar'
```

Ubuntu:

```bash
./scripts/ubuntu/entorno/bootstrap-verificatum.sh
```

Android:

- reutiliza el mismo repositorio `native/verificatum-jars/` del proyecto
- no requiere un bootstrap Verificatum separado para consumir el `AAR`
- la compilacion Android resuelve dependencias directamente de `native/verificatum-jars/`

Los tres artefactos Verificatum resueltos automaticamente son:

- `com.verificatum:verificatum-vmgj:1.3.0`
- `com.verificatum:verificatum-vecj:2.2.0`
- `com.verificatum:verificatum-vcr-vmgj-vecj:3.1.0`

## Artefactos Canonicos

### Windows

- `prebuilt/java/ElGamalCipher-1.1.0.jar`
- `prebuilt/windows-x64/vecj-2.2.0.dll`
- `prebuilt/windows-x64/vmgj-1.3.0.dll`

Bundle de libreria distribuible:

- `dist/windows/library/Cifrador/app/ElGamalCipher-1.1.0.jar`
- `dist/windows/library/Cifrador/libs/windows-x64/vecj-2.2.0.dll`
- `dist/windows/library/Cifrador/libs/windows-x64/vmgj-1.3.0.dll`

### Ubuntu

- `prebuilt/java/ElGamalCipher-1.1.0.jar`
- `prebuilt/linux-x64/`

### Android

- `prebuilt/android/aar/ElGamalCipher-android-debug.aar`
- `prebuilt/android/jniLibs/arm64-v8a/`
- `prebuilt/android/jniLibs/x86_64/`

## Compilacion

### Compilacion Rapida (Maven Wrapper)

La forma mas directa de compilar el cifrador, sin necesidad de scripts adicionales:

Ubuntu/Linux:

```bash
./mvnw clean package
```

Windows:

```cmd
mvnw.cmd clean package
```

Esto genera `target/ElGamalCipher-1.1.0.jar` (fat-JAR con todas las dependencias).

### Compilacion Base Del Cifrador (via scripts)

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\build-cifrador.ps1
```

Ubuntu:

```bash
./scripts/ubuntu/compilacion/build-cifrador.sh
```

Android:

```bash
./scripts/android/compilacion/build-cifrador.sh
```

### Compilacion Nativa Por Plataforma

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\build-native-windows.ps1
```

Salida esperada:

- `prebuilt/windows-x64/vecj-2.2.0.dll`
- `prebuilt/windows-x64/vmgj-1.3.0.dll`

Ubuntu:

```bash
./scripts/ubuntu/compilacion/build-native-linux.sh
```

Salida esperada:

- `prebuilt/linux-x64/`

Android:

```bash
./scripts/android/compilacion/build-jni.sh
```

Salida esperada:

- `prebuilt/android/jniLibs/arm64-v8a/`
- `prebuilt/android/jniLibs/x86_64/`

## Integracion Externa Como Libreria

### Filosofia General

- `Windows` y `Ubuntu` consumen `jar + JNI`
- `Android` consume `AAR`
- el cifrador es la libreria
- las interfaces de votacion son consumidores separados

### Modos De Integracion Segun Plataforma

En `Windows` y `Ubuntu` existen dos formas validas de integracion:

- como API Java embebida dentro de una aplicacion propia
- como proceso externo lanzando `java` contra el `jar`

Ambas opciones usan el mismo cifrador y ambas siguen requiriendo las bibliotecas JNI nativas de la plataforma.

En `Android` el modelo canonico es distinto:

- la integracion se hace consumiendo el `AAR` como libreria dentro de la app Android
- no se considera un uso externo por CLI ni lanzando un proceso `java` separado
- la API publica Android vive dentro del propio `AAR`

### API Java Comun Para Windows Y Ubuntu

Esta API aplica a consumidores Java externos en `Windows` y `Ubuntu`.

No aplica directamente a `Android`, porque en Android el punto de integracion canonico es el `AAR`.

Clases principales:

- `pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService`
- `pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest`
- `pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode`
- `pe.gob.onpe.votodigital.elgamalcipher.LogConfig`

Ejemplo minimo para una aplicacion Java que luego se ejecuta en `Windows` o `Ubuntu` con las librerias JNI correctas:

```java
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest;
import pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode;
import pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService;
import pe.gob.onpe.votodigital.elgamalcipher.LogConfig;

public final class DemoCifrado {
    public static void main(String[] args) {
        Logger logger = LogConfig.getLogger(
            "./logs/demo-cifrador.log",
            Level.INFO,
            true,
            true,
            true,
            true,
            true
        );

        CifradorRequest request = new CifradorRequest(
            Path.of("recursos/publicKey"),
            Path.of("recursos/shuffled_votes.txt"),
            Path.of("salida/ciphertexts_ext"),
            CifradorRngMode.SOFTWARE,
            false
        );

        boolean ok = new ElGamalCipherService(logger).encrypt(request);
        if (!ok) {
            throw new IllegalStateException("El cifrado no produjo salida.");
        }
    }
}
```

### Integracion Externa En Windows

Una aplicacion externa en Windows debe consumir:

- `ElGamalCipher-1.1.0.jar`
- `vecj-2.2.0.dll`
- `vmgj-1.3.0.dll`

Ejemplo:

```powershell
$PROJECT_ROOT = '<PROJECT_ROOT>'
$APP_JAR = '<APP_JAR>'

java `
  "-Djava.library.path=$PROJECT_ROOT\prebuilt\windows-x64" `
  -cp "$PROJECT_ROOT\prebuilt\java\ElGamalCipher-1.1.0.jar;$APP_JAR" `
  com.ejemplo.Main
```

Importante:

- el artefacto canonico Windows es libreria, no `.exe`
- no copies solo el `jar`; copia tambien las DLL JNI

### Integracion Externa En Ubuntu

Una aplicacion externa en Ubuntu debe enlazar el `jar` y exponer `libvecj` y `libvmgj`.

Ejemplo:

```bash
java \
  -Djava.library.path=/ruta/al/proyecto/prebuilt/linux-x64 \
  -cp /ruta/al/proyecto/prebuilt/java/ElGamalCipher-1.1.0.jar:mi-app.jar \
  com.ejemplo.Main
```

### Integracion Externa En Android

En `Android` no se usa el cifrador como proceso externo ni como CLI independiente.

La forma correcta de integracion es consumir el `AAR` compilado dentro de la app Android, es decir, usar la API publica del cifrador como libreria embebida:

```kotlin
dependencies {
    implementation(files("/ruta/al/proyecto/prebuilt/android/aar/ElGamalCipher-android-debug.aar"))
}
```

API Android principal:

- `pe.gob.onpe.votodigital.cifrador.android.AndroidCipherRunner`
- `pe.gob.onpe.votodigital.cifrador.android.AndroidCipherLibraryInfo`
- `pe.gob.onpe.votodigital.cifrador.android.AndroidTrueRngSupport`

El `AAR` ya empaqueta:

- bridge Android
- `jniLibs` para `arm64-v8a`
- `jniLibs` para `x86_64`
- soporte TrueRNG USB para Android

Resumen por plataforma:

- `Windows`: usa la API Java comun mas `jar + DLL`
- `Ubuntu`: usa la API Java comun mas `jar + .so`
- `Android`: usa el `AAR` y su API Android publica

## Uso Del Cifrador Desde CLI

Ejecucion base en Windows (PowerShell):

```powershell
$PROJECT_ROOT = '<PROJECT_ROOT>'

java -jar "$PROJECT_ROOT\prebuilt\java\ElGamalCipher-1.1.0.jar" `
  '<PUBLIC_KEY_PATH>' `
  '<PLAINTEXT_VOTES_PATH>' `
  '<CIPHERTEXT_OUTPUT_PATH>' `
  -sw `
  -p
```

Ejecucion base en Ubuntu/Linux:

```bash
java -jar prebuilt/java/ElGamalCipher-1.1.0.jar \
  <PUBLIC_KEY_PATH> \
  <PLAINTEXT_VOTES_PATH> \
  <CIPHERTEXT_OUTPUT_PATH> \
     -sw \
     [-p]
```

Parametros:

1. `ARG1_PUBLIC_KEY_PATH`: ruta a la llave publica
2. `ARG2_PLAINTEXT_VOTES_PATH`: archivo con votos planos
3. `ARG3_CIPHERTEXT_OUTPUT_PATH`: salida `ciphertexts_ext`
4. `-sw` o `-hw`: modalidad RNG
5. `-p`: barra de progreso opcional

## RNG Y Soporte De Hardware

### Ubuntu

- `-sw` usa `RandomDevice()` con `/dev/urandom`
- `-hw` usa TrueRNG por:
  - propiedad JVM `-Delgamal.rng.device=<ruta>`
  - variable `ELGAMAL_RNG_DEVICE`
  - valor por defecto `/dev/TrueRNG0`

### Windows

- `-sw` usa `SecureRandom`
- `-hw` usa TrueRNG por:
  - autodeteccion `VID:PID 04D8:F5FE`
  - propiedad JVM `-Delgamal.rng.device=COMx`
  - variable `ELGAMAL_RNG_DEVICE=COMx`

### Android

- `-sw` usa `SecureRandom`
- `-hw` usa TrueRNG USB cuando el dispositivo esta conectado por OTG, Android detecta un driver serial compatible y la app ya tiene permiso USB
- el `AAR` ya incluye `AndroidTrueRngSupport` y `AndroidUsbTrueRngRandomSource`; no hace falta que la app consumidora implemente por su cuenta la lectura del TrueRNG
- si no hay dispositivo, driver o permiso USB, la ejecucion en modo hardware falla de forma explicita; no se degrada silenciosamente a `SecureRandom`
- la app consumidora si debe manejar permisos USB, diagnostico del dispositivo y ciclo de vida Android alrededor del uso del TrueRNG

### Otros Sistemas

- `-sw` y `-hw` usan `SecureRandom`

## Layout Nativo Y Carga De JNI

La aplicacion busca bibliotecas nativas en este orden:

1. `prebuilt/<os-arch>`
2. `prebuilt`
3. `libs/<os-arch>`
4. `libs`

Ejemplos:

- `prebuilt/linux-x64/libvecj-2.2.0.so`
- `prebuilt/windows-x64/vecj-2.2.0.dll`

Si el `jar` se ejecuta sin `java.library.path` y encuentra un layout local valido, puede relanzarse con la ruta correcta.

## Empaquetado Y Distribucion

### Windows

Build del bundle de libreria:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\empaquetado\build-cifrador-portable.ps1
```

Salida:

- `dist/windows/library/Cifrador/app/ElGamalCipher-1.1.0.jar`
- `dist/windows/library/Cifrador/libs/windows-x64/vecj-2.2.0.dll`
- `dist/windows/library/Cifrador/libs/windows-x64/vmgj-1.3.0.dll`

ZIP del bundle:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\empaquetado\package-cifrador-portable.ps1
```

Salida:

- `dist/windows/Cifrador-1.1.0-windows-x64-library.zip`

### Ubuntu

Imagen portable:

```bash
./scripts/ubuntu/empaquetado/build-cifrador-portable.sh
```

Salida:

- `dist/linux/image/Cifrador/Cifrador`
- `dist/linux/image/Cifrador/runtime`
- `dist/linux/image/Cifrador/app`
- `dist/linux/image/Cifrador/libs/linux-x64`

Paquete comprimido:

```bash
./scripts/ubuntu/empaquetado/package-cifrador-portable.sh
```

Salida:

- `dist/linux/Cifrador-1.1.0-linux-x64-portable.tar.gz`

### Android

Imagen portable:

```bash
./scripts/android/empaquetado/build-cifrador-portable.sh
```

Salida:

- `dist/android/image/Cifrador/aar/Cifrador-android-debug.aar`
- `dist/android/image/Cifrador/jniLibs/arm64-v8a/`
- `dist/android/image/Cifrador/jniLibs/x86_64/`
- `dist/android/image/Cifrador/metadata/checksums.sha256`

Paquete comprimido:

```bash
./scripts/android/empaquetado/package-cifrador-portable.sh
```

Salida:

- `dist/android/Cifrador-1.1.0-android-portable.zip`

## Pruebas Y Validaciones

### Windows Contra Verificatum Linux

Prueba remota automatizada:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\pruebas\test-remote-verificatum-mix.ps1 `
    -SshHost <host-verificatum> `
    -Password "<password-ssh>" `
    -RebuildBundle
```

Wrapper `.bat`:

```bat
.\scripts\windows\pruebas\test-remote-verificatum-mix.bat -Password "<password-ssh>" -SshHost <host-verificatum>
```

Resultado esperado:

- `ciphertexts_ext` generado localmente
- `shuffle`, `decrypt` y `vmnv` completados en Linux
- comparación final consistente entre originales y descifrados

### Android

Scripts relevantes:

- `scripts/android/pruebas/test-connected.sh`
- `scripts/android/pruebas/test-hybrid-mix.sh`

### Ubuntu

Validaciones relevantes:

- `scripts/ubuntu/entorno/bootstrap-verificatum.sh`
- `scripts/ubuntu/compilacion/build-cifrador.sh`
- `scripts/ubuntu/ejecucion/run-cifrador.sh`
- `scripts/ubuntu/empaquetado/build-cifrador-portable.sh`

## Consumidores De Ejemplo En Este Repo

### Votante Windows

- codigo: `workflow/votante/windows`
- consume el cifrador Windows como libreria
- empaqueta su propio runtime Java
- no depende de `Cifrador.exe`

### Votante Android

- codigo: `workflow/votante/android`
- consume el cifrador Android como libreria
- la interfaz en este repo es solo un ejemplo de uso

### GUI Mezcladora Verificatum

- codigo: `workflow/votante/mezcladora`
- GUI web local para operar varias `parties` de Verificatum en una sola PC
- expone panel principal en el puerto `7040` y una ventana por `party` en `70xx`
- las estaciones de voto (`windows`, `android`) se conectan a esta mezcladora via su API REST
- arranque: `cd workflow/votante/mezcladora && ./start_gui.sh`

### Ubuntu

- no hay una estacion de votacion Ubuntu en `workflow/`
- Ubuntu se usa en este repositorio como entorno de ejecucion CLI, empaquetado y validacion tecnica del cifrador

## Resumen De Rendimiento

Prueba de referencia con `10,000` votos:

| Metrica | Version Original | Version Optimizada | Mejora |
| :--- | :---: | :---: | :---: |
| Tiempo de ejecucion | ~1m 22s | ~10s | ~8x |
| Uso de CPU | un nucleo | multi-nucleo | mejora sustancial |

Mejoras clave:

- paralelismo con streams
- `ThreadLocal<RandomSource>`
- barra de progreso opcional
- control de modo hardware para TrueRNG

## Historial Breve

### Version 1.1.0

- refactor para calidad de codigo
- uso de capacidades modernas de Java 21
- mejoras de logs, estructura y portabilidad

### Version 1.0.0

- optimizacion de rendimiento
- paralelismo y mejora del acceso al RNG

### Rama `cifradorM`

- soporte multiplataforma
- empaquetado nativo por plataforma
- consolidacion del cifrador como libreria reusable
- proyecto autocontenido: JARs Verificatum en `native/verificatum-jars/` (ya no requiere bootstrap)
- Maven Wrapper incluido (`mvnw` / `mvnw.cmd`)
- fuentes VCR 3.1.0 incluidos en `native/verificatum-src/`
- verificado en Ubuntu, Windows 11 y Android (emulador API 34)
