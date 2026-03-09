# Plan Fase 2: Port a Android

## Estado de avance (2026-03-09)

Arranque completado en esta fecha:

- se desacopló el core cifrador del parsing CLI (`ElGamalCipherService`,
  `CifradorRequest`, `CifradorRngMode`)
- se creó proyecto Android mínimo en `android/` con Gradle wrapper y app
  base para check de ABI/JNI
- se creó `scripts/android` con flujo inicial (`doctor`, `assembleDebug`,
  `installDebug`)
- se cerró el port JNI Android para `x86_64` y `arm64-v8a`
- se agregó smoke test real sobre `vecj` y `vmgj` dentro de la app
- se agregó automatización para AVD headless y `connectedAndroidTest`
- se integró el core reusable del cifrador dentro de la app Android
- se agregó prueba instrumentada de cifrado real y exportación de
  `ciphertexts_ext`
- se agregó prueba híbrida Android -> Linux con Verificatum
- se agregó empaquetado portable Android bajo `dist/android`

Estado actual del host:

- SDK Android configurado
- NDK instalado
- imagen de emulador `x86_64` instalada
- `KVM` operativo y validado
- emulador `x86_64` operativo para `connectedAndroidTest`
- validación híbrida ejecutada exitosamente en esta máquina:
  - Android cifra `2125` votos reales
  - Linux mezcla, descifra y verifica
  - mismo conjunto de votos: sí
  - mismo orden: no
- imagen portable Android validada desde `dist/android/image/Cifrador`
  usando instalación por `adb` y smoke test instrumentado

## Objetivo

Llevar el cifrador a Android manteniendo compatibilidad criptográfica
con el flujo actual basado en Verificatum, pero adaptando:

- la capa nativa JNI
- el empaquetado por ABI
- el modelo de ejecución móvil
- la lectura y escritura de archivos en Android

Esta fase busca una primera ejecución funcional en Android. No busca
todavía un producto móvil final con UX cerrada.

## Punto de partida

Ya resuelto en la rama actual:

- el cifrador funciona nativamente en `Windows x64`
- el RNG ya está separado por plataforma:
  - Ubuntu usa `RandomDevice` (`/dev/urandom` y TrueRNG)
  - otros sistemas usan `SecureRandom`
- `vecj` y `vmgj` compilan y cargan en Windows
- existe empaquetado portable y prueba automatizada contra Verificatum

Esto reduce trabajo en la parte Java, pero no elimina el principal
bloqueador Android: el port JNI.

## Hipótesis técnica

Android usa kernel Linux, pero no es Linux de escritorio. Por eso:

- las bibliotecas nativas deberán ser `.so`
- deberán compilarse con `Android NDK`
- deberán enlazarse contra `bionic`, no contra `glibc`
- deberán empaquetarse como `jniLibs/<abi>/`

En consecuencia, no se pueden reutilizar directamente ni:

- las `.dll` de Windows
- las `.so` de Ubuntu de escritorio

## Emulador recomendado

Para el arranque del trabajo se recomienda usar:

- `Android Studio Emulator (AVD)`
- dispositivo virtual: `Pixel 8`
- imagen del sistema: `Android 14` o superior
- variante: `Google APIs`
- ABI del emulador: `x86_64`

Motivo:

- es la opción más rápida y estable para validar la parte Java,
  integración Gradle, carga base de la app y flujos de archivos
- permite detectar temprano problemas de inicialización, permisos y
  empaquetado sin depender todavía de un teléfono físico

Límite importante:

- la validación final de JNI para producción debe hacerse en `arm64-v8a`
- eso idealmente debe cerrarse en un dispositivo físico Android ARM64
- alternativamente puede usarse un AVD `arm64-v8a` si el host lo soporta,
  pero será más lento

## Uso del emulador en Ubuntu

Si el host principal de desarrollo será `Ubuntu`, la recomendación es:

- usar `Android Studio Emulator (AVD)`
- usar imagen `x86_64`
- usar aceleración por `KVM`

Verificación base esperada en Ubuntu:

- comprobar aceleración con:
  - `emulator -accel-check`
- confirmar soporte de virtualización
- confirmar acceso a `/dev/kvm`

Modo recomendado en Ubuntu con entorno gráfico:

- ejecutar el emulador normalmente desde Android Studio
- o desde línea de comandos con un AVD `x86_64`

Modo recomendado en Ubuntu sin entorno gráfico o en servidor:

- ejecutar el emulador en modo headless con:
  - `-no-window`
- conectarse luego por `adb`

Ejemplo conceptual:

```bash
emulator @Pixel8_API_34 -no-window
adb devices
```

Sobre `weston`:

- `weston` no es un requisito normal para usar el emulador Android en
  Ubuntu
- el flujo estándar es:
  - Android Studio + ventana gráfica normal
  - o emulador headless con `-no-window`
- `weston` solo tendría sentido si se arma un entorno Wayland mínimo y
  se necesita un compositor para mostrar interfaz gráfica en un host que
  no tenga sesión gráfica completa

Recomendación práctica:

- no planificar `weston` como dependencia base
- usar primero:
  - `KVM`
  - AVD `x86_64`
  - Android Studio o `emulator`
  - `adb`
- usar `-no-window` si el host Ubuntu no tiene escritorio utilizable

## Prerrequisitos del host de desarrollo

El plan debe poder ejecutarse tanto desde `Windows` como desde
`Ubuntu`.

Para desarrollar, compilar y desplegar el port Android se requiere en el
host de desarrollo:

- `Android Studio`
- `Android SDK`
- `Android SDK Platform-Tools`
- `Android NDK`
- `JDK 21`
- `Gradle` via wrapper del proyecto Android

Componentes mínimos esperados:

- una plataforma Android reciente para compilar y probar
- al menos una imagen AVD `x86_64`
- soporte de `adb`
- toolchain C/C++ del `NDK`

Uso previsto de cada componente:

- `Android Studio`: edición, AVD, instalación y depuración
- `SDK Platform-Tools`: `adb`, despliegue y extracción de artefactos
- `NDK`: compilación de `libvecj-2.2.0.so` y `libvmgj-1.3.0.so`
- `JDK 21`: build de módulos Java y Gradle

Compatibilidad por sistema host:

- `Windows`
  - válido para Android Studio, AVD, SDK, NDK y `adb`
  - recomendado si se quiere usar un flujo visual con emulador y
    depuración integrada
- `Ubuntu`
  - válido para JDK, Gradle, SDK, NDK y `adb`
  - válido para build y despliegue a teléfono físico
  - también puede usar emulador, preferentemente con `KVM`
  - si no hay escritorio disponible, puede usar emulador headless con
    `-no-window`

Recomendación operativa:

- usar `Windows` o `Ubuntu` indistintamente para compilar y desplegar
- usar el host que tenga mejor soporte local para Android Studio y AVD
- no asumir rutas ni scripts exclusivos de un solo sistema operativo

Prerrequisitos específicos por host:

- en `Windows`
  - `Android Studio`
  - SDK/NDK instalados desde el SDK Manager
  - `adb` accesible desde `Platform-Tools`
- en `Ubuntu`
  - `Android Studio` o un entorno CLI con SDK/NDK
  - `adb` instalado y accesible
  - reglas `udev` si se conectará un teléfono físico por USB

Nota para `Ubuntu`:

- si el teléfono no aparece en `adb devices`, normalmente faltará
  configurar permisos USB o reglas `udev`

## Requisito de root para ADB

No se requiere root para el flujo normal de validación con `adb`, ni en
`Windows` ni en `Ubuntu`.

Con un teléfono Android estándar, sin root, se puede:

- instalar la app
- ver logs
- ejecutar la app
- consultar propiedades del dispositivo
- extraer archivos exportados por la app

Ejemplos de comandos que no requieren root:

- `adb devices`
- `adb install -r app-debug.apk`
- `adb shell getprop ro.product.cpu.abi`
- `adb logcat`
- `adb pull <ruta-exportada> <ruta-local>`

Lo que sí cambia sin root:

- no se puede leer libremente cualquier directorio interno del sistema
- no se puede inspeccionar cualquier carpeta privada de otras apps
- si el cifrador guarda resultados en almacenamiento privado, la app
  debe implementar una ruta de exportación o guardarlos en una ubicación
  accesible para pruebas

Conclusión práctica:

- root no es requisito para validar el cifrador en Android
- sí hace falta diseñar correctamente la exportación de artefactos para
  que `adb pull` pueda recuperar `ciphertexts_ext`

## Entornos de validación

### Entorno 1: Emulador x86_64

Objetivo:

- validar la app Android mínima
- validar empaquetado y carga de recursos
- validar la API Java del cifrador
- detectar problemas de permisos, rutas y lifecycle

No cierra:

- compatibilidad ABI final de `vecj` y `vmgj` para ARM64

### Entorno 2: Dispositivo físico ARM64

Objetivo:

- validar `libvecj-2.2.0.so`
- validar `libvmgj-1.3.0.so`
- validar rendimiento real
- validar consumo de memoria
- validar comportamiento estable en arquitectura objetivo

Metodo de validacion:

- despliegue desde el host de desarrollo mediante `adb`
- ejecucion de la app en el telefono Android real
- recuperacion de logs y artefactos mediante `adb`

## Riesgos principales

1. `vecj` y `vmgj` pueden requerir cambios específicos para compilar
   contra `Android NDK`.

2. Dependencias nativas subyacentes como `verificatum-vec` y
   `verificatum-gmpmee` pueden asumir APIs o flags de Linux de
   escritorio.

3. El cargador nativo actual deberá adaptarse al modelo Android, donde
   la carga de `.so` depende del empaquetado del APK/AAB.

4. El modelo actual CLI del cifrador no encaja directamente en Android;
   habrá que desacoplar el core del launcher de escritorio.

5. El acceso arbitrario al filesystem no es válido en Android. Habrá que
   rediseñar entradas y salidas para:
   - `DocumentFile`
   - `ContentResolver`
   - almacenamiento interno de app

## Estrategia

La forma correcta de portarlo es por capas:

1. aislar el core Java reutilizable
2. compilar y cargar JNI en Android
3. empaquetar por ABI
4. montar una app mínima de prueba
5. validar cifrado real con `publicKey` y votos de prueba

No conviene empezar por una UI final.

## Plan de trabajo

### 1. Separar el core reutilizable del launcher actual

Objetivo:

- dejar el cifrador invocable como librería o módulo Java reutilizable

Tareas:

- extraer la lógica de `ElGamalMain` a una API invocable
- separar parsing CLI de la lógica de cifrado
- dejar una interfaz tipo:
  - `encrypt(publicKey, votesInput, output, rngMode)`

Resultado esperado:

- el cifrador puede usarse desde escritorio o desde Android sin depender
  del launcher actual

### 2. Crear un proyecto Android mínimo

Objetivo:

- disponer de un host Android para pruebas JNI y de archivos

Tareas:

- crear módulo Android app o sample app
- configurar `minSdk`, `targetSdk` y Gradle
- añadir una pantalla mínima o runner interno de pruebas
- preparar carga de archivos de entrada y salida

Resultado esperado:

- una app Android puede invocar el core del cifrador

### 3. Portar `vecj` y `vmgj` a Android NDK

Objetivo:

- generar librerías JNI Android

Tareas:

- preparar toolchain `Android NDK`
- compilar dependencias C/C++ requeridas
- compilar:
  - `libvecj-2.2.0.so`
  - `libvmgj-1.3.0.so`
- validar símbolos JNI exportados
- corregir diferencias de tipos y punteros si aparecen

ABIs objetivo iniciales:

- `x86_64` para emulador
- `arm64-v8a` para producción

Resultado esperado:

- las librerías JNI cargan en Android

### 4. Adaptar la carga nativa a Android

Objetivo:

- hacer que el runtime Java cargue las `.so` empaquetadas por Android

Tareas:

- revisar el cargador nativo actual
- evitar supuestos de layout tipo `libs/windows-x64`
- usar carga compatible con Android:
  - `System.loadLibrary(...)`
  - `jniLibs/<abi>/`
- validar secuencia de carga de dependencias

Resultado esperado:

- el cifrador encuentra y carga `vecj` y `vmgj` dentro del APK

### 5. Validar RNG en Android

Objetivo:

- confirmar que `SecureRandom` es suficiente y operativo en Android

Tareas:

- validar `PlatformRandomSource` en Android
- medir estabilidad bajo paralelismo
- confirmar que `-sw` funciona sin supuestos de escritorio

Resultado esperado:

- RNG portable funcionando en Android

Nota:

- `-hw` no es prioridad para el primer POC Android
- primero debe quedar cerrada la ruta `SecureRandom`

### 6. Rediseñar entrada y salida de archivos

Objetivo:

- adaptar I/O al sandbox Android

Tareas:

- dejar una ruta de carga de `publicKey`
- dejar una ruta de carga de votos
- dejar una ruta de escritura de `ciphertexts_ext`
- evitar dependencia de paths arbitrarios

Resultado esperado:

- el cifrador puede procesar insumos reales dentro del modelo Android

### 7. Validar compatibilidad funcional

Objetivo:

- asegurar que Android genera ciphertexts compatibles

Tareas:

- cifrar usando `publicKey` real
- exportar `ciphertexts_ext`
- probar mezcla y descifrado en Verificatum Linux
- comparar `plaintexts` contra los votos originales

Resultado esperado:

- compatibilidad funcional entre Android y el backend Linux

### 8. Validacion en telefono con ADB

Objetivo:

- definir un flujo reproducible de prueba sobre un dispositivo Android
  real

Premisa:

- la compilacion se hace en el host de desarrollo
- el telefono no compila
- el telefono ejecuta el APK ya construido y permite validar ABI real
  `arm64-v8a`

Preparacion del telefono:

- activar `Opciones de desarrollador`
- activar `Depuracion USB`
- conectar el telefono por USB
- verificar visibilidad con:
  - `adb devices`

Opcional:

- migrar luego a `adb` por Wi-Fi si el equipo y el telefono estan en la
  misma red

Notas por host:

- en `Windows`, el flujo normal usa `adb` desde `Platform-Tools`
- en `Ubuntu`, además del binario `adb`, puede ser necesario ajustar
  permisos USB para que el teléfono sea visible

Flujo base de validacion:

1. compilar el APK y las `.so` en la PC
2. instalar la app con:
   - `adb install -r app-debug.apk`
3. verificar ABI objetivo con:
   - `adb shell getprop ro.product.cpu.abi`
4. ejecutar la app o runner interno de prueba
5. inspeccionar logs con:
   - `adb logcat`
6. extraer `ciphertexts_ext` o archivos equivalentes con:
   - `adb pull <ruta-remota> <ruta-local>`
7. enviar la salida Android a Verificatum Linux para mezcla y descifrado
8. comparar los `plaintexts` contra los votos originales

El paso 1 debe ser reproducible tanto en `Windows` como en `Ubuntu`.

Comandos base esperados:

```powershell
adb devices
adb install -r app-debug.apk
adb shell getprop ro.product.cpu.abi
adb logcat
adb pull /sdcard/Download/ciphertexts_ext .\ciphertexts_ext
```

Ejemplos equivalentes en `Ubuntu`:

```bash
adb devices
adb install -r app-debug.apk
adb shell getprop ro.product.cpu.abi
adb logcat
adb pull /sdcard/Download/ciphertexts_ext ./ciphertexts_ext
```

Nota de diseño:

- si la app guarda archivos en almacenamiento privado, habrá que definir
  una estrategia explícita de exportación para pruebas
- opciones razonables:
  - almacenamiento compartido controlado
  - exportación mediante `ContentResolver`
  - acción interna de exportación solo para testing

Resultado esperado:

- evidencia reproducible de que la app Android:
  - carga JNI
  - cifra correctamente
  - genera un `ciphertexts_ext` exportable
  - puede validarse extremo a extremo contra Verificatum Linux

### 9. Medir límites operativos

Objetivo:

- conocer si el port móvil es viable a escala útil

Tareas:

- medir tiempo de cifrado
- medir memoria
- medir tamaño del paquete final
- evaluar comportamiento con lotes de votos crecientes

Resultado esperado:

- datos reales para decidir si Android queda como opción productiva o
  solo como cliente especializado

## Entregables

- módulo Android mínimo para pruebas
- build JNI Android para `x86_64`
- build JNI Android para `arm64-v8a`
- app de validación interna
- guía de validación con `adb` sobre teléfono físico
- guía de preparación del host en `Windows` y en `Ubuntu`
- evidencia de cifrado real compatible con Verificatum Linux
- documento de empaquetado Android por ABI

## Criterios de cierre de la fase

- el cifrador corre en Android
- `PlatformRandomSource` funciona con `SecureRandom`
- JNI carga correctamente en Android
- se genera `ciphertexts_ext` válido
- Verificatum Linux puede mezclar y descifrar la salida Android
- existe una guía técnica reproducible del build Android
- la guía funciona tanto en `Windows` como en `Ubuntu`

## Recomendación

El primer objetivo no debe ser una app final, sino un POC técnico con
este orden:

1. emulador `x86_64`
2. JNI Android funcional
3. dispositivo físico `arm64-v8a`
4. validación criptográfica extremo a extremo
5. recién después, UI o endurecimiento de producto

## Referencias oficiales

- Android Emulator:
  https://developer.android.com/studio/run/emulator
- Android NDK:
  https://developer.android.com/ndk
