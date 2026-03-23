# Android - Fase 2

Librería Android del cifrador reutilizable por otras apps, incluida la estación de votación en `workflow/votante/android`.

## Estado actual

- módulo librería creado en `android/app`
- runner Android con smoke test real de `vecj` y `vmgj`
- runner Android con cifrado real usando `ElGamalCipherService`
- estructura `jniLibs` operativa para `x86_64` y `arm64-v8a`
- build JNI automatizado con Android NDK
- pruebas instrumentadas para emulador `x86_64`
- prueba híbrida Android -> Linux con mezcla y verificación Verificatum

La UI del votante Android ya no vive aquí. Ahora consume esta librería desde:

- `workflow/votante/android/app`

## Reutilizacion

Esta librería del cifrador está pensada para ser reutilizada:

- puede ser consumida por cualquier interfaz de votación Android
- la app en `workflow/votante/android` es solo un ejemplo de consumo dentro de este repositorio
- otras apps pueden integrarla como módulo Gradle o empaquetarla como `AAR`

## Build local

Configurar SDK local:

```bash
cp local.properties.example local.properties
# editar sdk.dir=...
```

Luego compilar:

```bash
../scripts/android/build-jni.sh
./gradlew :app:assembleRelease
```

## Empaquetado portable en `dist/`

Imagen portable:

```bash
../scripts/android/build-cifrador-portable.sh
```

ZIP portable:

```bash
../scripts/android/package-cifrador-portable.sh
```

Salida:

- `dist/android/image/Cifrador/apk/Cifrador.apk`
- `dist/android/image/Cifrador/apk/pruebas_auto.apk`
- `dist/android/image/Cifrador/recursos/publicKey`
- `dist/android/image/Cifrador/recursos/shuffled_votes.txt`
- `dist/android/image/Cifrador/scripts/install.sh`
- `dist/android/image/Cifrador/scripts/run-smoke-test.sh`
- `dist/android/Cifrador-1.1.0-android-portable.zip`

## Consumo desde otra app

La estación Android del votante la consume como módulo Gradle incluido:

- `workflow/votante/android/settings.gradle.kts`
- alias local: `:cifradorlib`
- directorio real: `../../../android/app`

## Pruebas automáticas en emulador

```bash
../scripts/android/test-connected.sh
```

Prueba híbrida completa:

```bash
SKIP_ANDROID_JNI=1 ../scripts/android/test-hybrid-mix.sh
```

Este flujo:

1. genera una elección temporal en Linux con Verificatum,
2. inyecta `publicKey` y `shuffled_votes.txt` al sandbox de la app,
3. cifra los votos dentro del emulador Android,
4. recupera `ciphertexts_ext`,
5. ejecuta `shuffle`, `decrypt` y `vmnv` en Linux,
6. verifica que el conjunto de votos original y el descifrado coincidan
   pero en distinto orden.

Smoke test desde la imagen portable:

```bash
../dist/android/image/Cifrador/scripts/install.sh
../dist/android/image/Cifrador/scripts/run-smoke-test.sh
```

Nota:

- en este host ya quedó validado con `KVM` operativo
- para ejecutar `connectedAndroidTest` y la prueba híbrida se requiere:
  - `KVM` operativo en Ubuntu
  - o un dispositivo físico Android con `adb`
  - binarios Verificatum (`vog`, `vmni`, `vmn`, `vmnc`, `vmnv`) en Linux

## Siguientes hitos

1. Adaptar I/O del cifrado a `ContentResolver` y almacenamiento interno.
2. Definir flujo de importación/exportación usable fuera de pruebas.
3. Validar también en ABI `arm64-v8a` sobre dispositivo físico.
