# ElGamalClient - Cifrado de Votos Electronicos

Implementacion del cifrado de votos electronicos usando ElGamal sobre curvas elipticas, con soporte multiplataforma para:

- `Windows x64`
- `Ubuntu/Linux`
- `Android`

El repositorio contiene tanto el cifrador como libreria reusable como estaciones de votacion de ejemplo que lo consumen.

## Indice

1. [Resumen](#resumen)
2. [Estado Actual](#estado-actual)
3. [Estructura Del Repositorio](#estructura-del-repositorio)
4. [Requisitos](#requisitos)
5. [Bootstrap De Dependencias Verificatum](#bootstrap-de-dependencias-verificatum)
6. [Artefactos Canonicos](#artefactos-canonicos)
7. [Compilacion](#compilacion)
8. [Integracion Externa Como Libreria](#integracion-externa-como-libreria)
9. [Uso Del Cifrador Desde CLI](#uso-del-cifrador-desde-cli)
10. [RNG Y Soporte De Hardware](#rng-y-soporte-de-hardware)
11. [Layout Nativo Y Carga De JNI](#layout-nativo-y-carga-de-jni)
12. [Empaquetado Y Distribucion](#empaquetado-y-distribucion)
13. [Pruebas Y Validaciones](#pruebas-y-validaciones)
14. [Consumidores De Ejemplo En Este Repo](#consumidores-de-ejemplo-en-este-repo)
15. [Resumen De Rendimiento](#resumen-de-rendimiento)
16. [Historial Breve](#historial-breve)

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
  - fuentes compartidas de `vec`, `gmpmee`, `vecj` y `vmgj`
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

## Requisitos

- `Java 21` o superior
- `Maven 3.x`

Requisitos adicionales por plataforma:

- `Windows`
  - PowerShell
  - `MSYS2 UCRT64` si se van a recompilar DLL JNI
- `Ubuntu`
  - toolchain nativo para JNI Linux
- `Android`
  - Android SDK / Gradle para compilar el `AAR` o el consumidor Android

## Bootstrap De Dependencias Verificatum

Las dependencias `com.verificatum` no viven en Maven Central. Este repo usa bootstrap local.

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\bootstrap-verificatum.ps1
```

Ubuntu:

```bash
./scripts/ubuntu/entorno/bootstrap-verificatum.sh
```

En Windows el bootstrap instala en `.mvn/local-repo`:

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

### Compilacion Base Del Cifrador

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

### Compilacion Nativa Windows

Para recompilar las DLL JNI de Windows x64:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\compilacion\build-native-windows.ps1
```

Salida esperada:

- `prebuilt/windows-x64/vecj-2.2.0.dll`
- `prebuilt/windows-x64/vmgj-1.3.0.dll`

## Integracion Externa Como Libreria

### Filosofia General

- `Windows` y `Ubuntu` consumen `jar + JNI`
- `Android` consume `AAR`
- el cifrador es la libreria
- las interfaces de votacion son consumidores separados

### API Java Principal

API reusable para consumidores Java o escritorio:

- `pe.gob.onpe.votodigital.elgamalcipher.ElGamalCipherService`
- `pe.gob.onpe.votodigital.elgamalcipher.CifradorRequest`
- `pe.gob.onpe.votodigital.elgamalcipher.CifradorRngMode`
- `pe.gob.onpe.votodigital.elgamalcipher.LogConfig`

Ejemplo minimo:

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

### Windows

Una aplicacion externa en Windows debe consumir:

- `ElGamalCipher-1.1.0.jar`
- `vecj-2.2.0.dll`
- `vmgj-1.3.0.dll`

Ejemplo:

```powershell
java `
  -Djava.library.path=D:\ruta\prebuilt\windows-x64 `
  -cp D:\ruta\prebuilt\java\ElGamalCipher-1.1.0.jar;mi-app.jar `
  com.ejemplo.Main
```

Importante:

- el artefacto canonico Windows es libreria, no `.exe`
- no copies solo el `jar`; copia tambien las DLL JNI

### Ubuntu

Una aplicacion externa en Ubuntu debe enlazar el `jar` y exponer `libvecj` y `libvmgj`.

Ejemplo:

```bash
java \
  -Djava.library.path=/ruta/al/proyecto/prebuilt/linux-x64 \
  -cp /ruta/al/proyecto/prebuilt/java/ElGamalCipher-1.1.0.jar:mi-app.jar \
  com.ejemplo.Main
```

### Android

Una app Android externa debe consumir el `AAR` compilado:

```kotlin
dependencies {
    implementation(files("/ruta/al/proyecto/prebuilt/android/aar/ElGamalCipher-android-debug.aar"))
}
```

API Android principal:

- `pe.gob.onpe.votodigital.cifrador.android.AndroidCipherRunner`
- `pe.gob.onpe.votodigital.cifrador.android.AndroidCipherLibraryInfo`

El `AAR` ya empaqueta:

- bridge Android
- `jniLibs` para `arm64-v8a`
- `jniLibs` para `x86_64`

## Uso Del Cifrador Desde CLI

Ejecucion base:

```bash
java -jar prebuilt/java/ElGamalCipher-1.1.0.jar \
     <ruta_publicKey> \
     <ruta_votos_planos> \
     <ruta_salida_cifrados> \
     -sw \
     [-p]
```

Parametros:

1. `public_Key_file_name`: ruta a la llave publica
2. `plain_votes_file_name`: archivo con votos planos
3. `ciphered_votes_file_name`: salida `ciphertexts_ext`
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
    -SshHost CHUWIN11 `
    -Password "123456." `
    -RebuildBundle
```

Wrapper `.bat`:

```bat
.\scripts\windows\pruebas\test-remote-verificatum-mix.bat -Password "123456." -SshHost CHUWIN11
```

Resultado esperado:

- `ciphertexts_ext` generado localmente
- `shuffle`, `decrypt` y `vmnv` completados en Linux
- comparación final consistente entre originales y descifrados

### Android

Scripts relevantes:

- `scripts/android/pruebas/test-connected.sh`
- `scripts/android/pruebas/test-hybrid-mix.sh`

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
