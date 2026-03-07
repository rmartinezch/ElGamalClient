# Windows x64

## Objetivo

Esta rama prepara al cifrador para empaquetarse y ejecutarse de manera
nativa en `Windows x64`, manteniendo el backend criptográfico de
Verificatum.

## Estado actual

Implementado en esta rama:

- detección de sistema operativo y arquitectura
- carga de bibliotecas nativas desde `libs/<os-arch>` o `libs`
- relanzamiento automático del JAR con `java.library.path`
- RNG portable basado en `SecureRandom`
- soporte para parametrizar un dispositivo RNG por hardware
- pruebas automáticas de regresión en Linux
- build nativo Windows x64 reproducible desde código fuente
- validación real en Windows con `publicKey` y `shuffled_votes.txt`
- generación de `app-image` con `jpackage`

Validado en Windows x64:

- compilación local de `vecj-2.2.0.dll`
- compilación local de `vmgj-1.3.0.dll`
- carga JNI real desde `libs/windows-x64`
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
- salida generada en archivo sin depender de `RandomDevice()` como RNG
  por defecto

## Layout esperado

```text
libs/
  linux-x64/
    libvecj-2.2.0.so
  windows-x64/
    vecj-2.2.0.dll
    vmgj-1.3.0.dll
```

El cargador busca primero `libs/<os-arch>` y luego `libs`.

## RNG

- `-sw`: usa `PlatformRandomSource`, basado en `SecureRandom`
- `-hw`: usa un dispositivo definido por
  `-Delgamal.rng.device=<ruta>` o `ELGAMAL_RNG_DEVICE`

Si el dispositivo no está disponible, el flujo vuelve a `SecureRandom`.

TODO de RNG en Windows:

- agregar soporte operativo y documentado para un dispositivo TrueRNG
  real en Windows usando `-hw`
- validar en un host Windows real qué ruta o interfaz expone el
  dispositivo para que `RandomDevice` pueda abrirlo correctamente

## Empaquetado Windows

Se incluye el script:

- [package-windows.ps1](/home/soettamusb/verificatum/cifradorM/scripts/package-windows.ps1)
- [build-native-windows.ps1](/home/soettamusb/verificatum/cifradorM/scripts/build-native-windows.ps1)

`build-native-windows.ps1`:

- instala `MSYS2 UCRT64` si no existe
- compila `verificatum-vec` y `verificatum-gmpmee`
- compila `vecj-2.2.0.dll` y `vmgj-1.3.0.dll`
- deja las DLL en `libs/windows-x64`
- ejecuta una prueba mínima JNI

`package-windows.ps1`:

- valida la presencia del JAR
- valida la presencia de `vecj-2.2.0.dll`
- valida la presencia de `vmgj-1.3.0.dll`
- arma un directorio de entrada para `jpackage`
- genera una `app-image` para Windows con launcher de consola

## Scripts de automatizacion

Se agregan estos scripts operativos:

- `scripts/build-cifrador-exe.ps1`
- `scripts/package-cifrador-portable.ps1`
- `scripts/test-remote-verificatum-mix.bat`
- `scripts/test-remote-verificatum-mix.ps1`

`build-cifrador-exe.ps1`:

- asegura dependencias Maven locales si faltan
- compila el proyecto con Maven
- compila DLL nativas Windows si faltan
- empaqueta `dist/windows/image/Cifrador/Cifrador.exe`

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
