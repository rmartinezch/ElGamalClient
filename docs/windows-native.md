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

Pendiente exclusivo de Windows:

- incorporar `vecj-2.2.0.dll`
- incorporar DLL auxiliares necesarias
- validar carga real en un host Windows
- generar el paquete final con `jpackage`

Bloqueador actual:

- no se dispone en este arbol de trabajo del binario `vecj-2.2.0.dll`
- tampoco existe aqui el proyecto `verificatum-vecj` para reconstruirlo
  directamente

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
    ...
```

El cargador busca primero `libs/<os-arch>` y luego `libs`.

## RNG

- `-sw`: usa `PlatformRandomSource`, basado en `SecureRandom`
- `-hw`: usa un dispositivo definido por
  `-Delgamal.rng.device=<ruta>` o `ELGAMAL_RNG_DEVICE`

Si el dispositivo no está disponible, el flujo vuelve a `SecureRandom`.

## Empaquetado Windows

Se incluye el script:

- [package-windows.ps1](/home/soettamusb/verificatum/cifradorM/scripts/package-windows.ps1)

Ese script:

- valida la presencia del JAR
- valida la presencia de `vecj-2.2.0.dll`
- arma un directorio de entrada para `jpackage`
- genera una `app-image` para Windows

## Validación mínima esperada en Windows

1. `java -jar ... -sw` debe relanzarse con `java.library.path` correcto
2. la llave pública debe cargar sin `UnsatisfiedLinkError`
3. debe generarse salida hexadecimal compatible
4. la app-image debe ejecutar sin instalación manual de Verificatum
