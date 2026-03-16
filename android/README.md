# Android - Fase 2

Proyecto mínimo Android para iniciar el port del cifrador.

## Estado actual

- app base creada en `android/app`
- runner Android con smoke test real de `vecj` y `vmgj`
- runner Android con cifrado real usando `ElGamalCipherService`
- UI basica para:
  - ejecutar prueba JNI
  - importar `publicKey`
  - importar `shuffled_votes.txt`
  - ejecutar cifrado en Android
  - exportar `ciphertexts_ext`
- estructura `jniLibs` operativa para `x86_64` y `arm64-v8a`
- build JNI automatizado con Android NDK
- pruebas instrumentadas para emulador `x86_64`
- prueba híbrida Android -> Linux con mezcla y verificación Verificatum

## Build local

Configurar SDK local:

```bash
cp local.properties.example local.properties
# editar sdk.dir=...
```

Luego compilar:

```bash
../scripts/android/build-jni.sh
./gradlew :app:assembleDebug
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

## Instalación en dispositivo/emulador

```bash
../scripts/android/install-debug.sh
```

## Uso de la UI Android

La app ahora expone una interfaz básica para validación manual en el
teléfono o emulador:

1. ejecutar la prueba JNI
2. seleccionar `publicKey`
3. seleccionar el archivo de votos
4. ejecutar el cifrado
5. exportar `ciphertexts_ext`

Restricciones actuales:

- la UI usa `SecureRandom` en Android
- no hay soporte de `TrueRNG` USB en la app Android actual
- el flujo de importación/exportación usa el selector de documentos del
  sistema

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
