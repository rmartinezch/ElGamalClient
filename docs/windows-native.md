# Windows x64

## Objetivo

Esta rama prepara al cifrador para empaquetarse y ejecutarse de manera
nativa en `Windows x64`, manteniendo el backend criptográfico de
Verificatum.

## Estado actual

Implementado en esta rama:

- detección de sistema operativo y arquitectura
- carga de bibliotecas nativas desde `prebuilt/<os-arch>` o `prebuilt`
  en desarrollo, y desde `libs/<os-arch>` o `libs` en artefactos
  portables
- relanzamiento automático del JAR con `java.library.path`
- RNG portable basado en `SecureRandom`
- pruebas automáticas de regresión en Linux
- build nativo Windows x64 reproducible desde código fuente
- validación real en Windows con `publicKey` y `shuffled_votes.txt`
- generación de `app-image` con `jpackage`

Validado en Windows x64:

- compilación local de `vecj-2.2.0.dll`
- compilación local de `vmgj-1.3.0.dll`
- carga JNI real desde `prebuilt/windows-x64`
- ejecución completa del cifrador con `2125` votos
- generación del paquete final con `jpackage`

Hallazgo técnico adicional:

- el flujo real no depende solo de `vecj`; también requiere
  `vmgj-1.3.0.dll`
- `verificatum-vmgj` usa casts de punteros vía `long`, lo cual rompe en
  `Windows x64`; para compilarlo correctamente se debe usar `intptr_t`

## Validacion ejecutada en Linux

Validado en esta rama:

- `mvn test`
- `mvn -DskipTests package`
- ejecucion real del JAR con `publicKey` y `shuffled_votes.txt`

Resultado observado:

- carga correcta de la llave publica EC
- cifrado completo de `2125` votos
- salida generada en archivo usando `SecureRandom` en el flujo Windows

## Layout esperado

```text
prebuilt/
  linux-x64/
    libvecj-2.2.0.so
  windows-x64/
    vecj-2.2.0.dll
    vmgj-1.3.0.dll
```

El cargador busca primero `prebuilt/<os-arch>` y luego `prebuilt`.
Para los empaquetados finales mantiene compatibilidad con `libs/<os-arch>`.

## RNG

- `-sw`: usa `PlatformRandomSource`, basado en `SecureRandom`
- `-hw`: se mantiene por compatibilidad de CLI, pero en Windows también
  usa `SecureRandom`

## Empaquetado Windows

Se incluye el script:

- `scripts/windows/empaquetado/package-windows.ps1`
- `scripts/windows/compilacion/build-native-windows.ps1`

`build-native-windows.ps1`:

- instala `MSYS2 UCRT64` si no existe
- compila `verificatum-vec` y `verificatum-gmpmee`
- compila `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
- deja las DLL en `prebuilt/windows-x64`
- ejecuta una prueba mínima JNI

`package-windows.ps1`:

- valida la presencia del JAR
- valida la presencia de `vecj-2.2.0.dll`
- valida la presencia de `vmgj-1.3.0.dll`
- arma un directorio de entrada para `jpackage`
- genera una `app-image` para Windows con launcher de consola

## Scripts de automatizacion

Se agregan estos scripts operativos:

- `scripts/windows/compilacion/build-cifrador.ps1`
- `scripts/windows/empaquetado/build-cifrador-portable.ps1`
- `scripts/windows/empaquetado/package-cifrador-portable.ps1`
- `scripts/windows/pruebas/test-remote-verificatum-mix.bat`
- `scripts/windows/pruebas/test-remote-verificatum-mix.ps1`

`build-cifrador.ps1`:

- asegura dependencias Maven locales si faltan
- compila el proyecto con Maven
- compila DLL nativas Windows si faltan
- exporta el `jar` canónico y las DLL JNI a `prebuilt/`

`build-cifrador-portable.ps1`:

- consume `prebuilt/java` y `prebuilt/windows-x64`
- copia un runtime Java embebido
- compila `Cifrador.exe`
- deja `dist/windows/image/Cifrador`

`package-cifrador-portable.ps1`:

- empaqueta la carpeta `dist/windows/image/Cifrador`
- genera `dist/windows/Cifrador-1.1.0-windows-x64-portable.zip`
- puede reconstruir el ejecutable antes de comprimir

`test-remote-verificatum-mix.bat`:

- delega en `test-remote-verificatum-mix.ps1`
- sirve para lanzar la prueba desde consola o doble clic

`test-remote-verificatum-mix.ps1`:

- usa `Cifrador.exe` para cifrar los votos reales
- usa `OpenSSH` nativo de Windows
- automatiza la prueba contra un Verificatum Linux remoto
- ejecuta `vmn -shuffle`, `vmn -decrypt` y `vmnv`
- compara `plaintexts` contra `recursos/shuffled_votes.txt`
- deja logs y resumen en `.build/remote-mix-test-<host>`
- devuelve el control al prompt con un resumen final

## Validación mínima esperada en Windows

1. `java -jar ... -sw` debe relanzarse con `java.library.path` correcto
2. la llave pública debe cargar sin `UnsatisfiedLinkError`
3. deben cargarse `vecj` y `vmgj` sin error JNI
4. debe generarse salida hexadecimal compatible
5. la app-image debe ejecutar sin instalación manual de Verificatum
