# Cifrador Android

Proyecto fuente de la librería Android del cifrador.

## Alcance

- este proyecto existe solo para compilar el cifrador Android
- el artefacto canónico es un `AAR`
- los `.so` Android se consumen desde `prebuilt/android/jniLibs`
- los artefactos compilados finales se exportan fuera de esta carpeta

Este proyecto no debe contaminarse con:

- interfaces de diagnóstico
- estaciones de votación
- artefactos canónicos de salida

## Salidas

- `prebuilt/android/aar/ElGamalCipher-android-debug.aar`
- `prebuilt/android/jniLibs/<abi>/libvecj-2.2.0.so`
- `prebuilt/android/jniLibs/<abi>/libvmgj-1.3.0.so`

## Soporte RNG

- `SecureRandom`: expuesto por `AndroidCipherRunner.encryptSandboxInputs()` con `CifradorRngMode.SOFTWARE`
- `TrueRNG`: expuesto por `AndroidCipherRunner.encryptSandboxInputs()` con `CifradorRngMode.HARDWARE`
- la ruta `TrueRNG` usa `AndroidTrueRngSupport` y `AndroidUsbTrueRngRandomSource`

## Compilación

Desde la raíz del repositorio:

```bash
./scripts/android/compilacion/build-cifrador.sh
```

`platform/android/app/build` es solo salida temporal de Gradle, no ubicación canónica de artefactos.

## Por qué Android sí necesita este proyecto

Ubuntu y Windows consumen el cifrador como un `jar` Java común más bibliotecas JNI por plataforma.
Android no puede integrarse solo con ese `jar`, porque la plataforma exige un artefacto `AAR`
para empaquetar de forma consistente:

- `AndroidManifest`
- clases Android/Kotlin
- `jniLibs` por ABI
- metadatos de build Android/Gradle

Por eso `platform/android` existe como proyecto fuente de librería Android. No reemplaza al
core Java del repositorio; solo adapta ese core al modelo de empaquetado requerido por Android.
