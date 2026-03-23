# Android Tools

Herramientas Android auxiliares de diagnóstico y validación.

## Estado actual

- herramienta de diagnóstico `truerngdiag`
- utilidades auxiliares para emulador/dispositivo Android
- soporte de pruebas sobre `x86_64` para emulador y Waydroid

La librería Android del cifrador ya no vive aquí. Ahora su proyecto fuente está en:

- `platform/android`

## Alcance

- este directorio no es la ubicación canónica del cifrador Android
- no debe contaminarse con `AAR`, `jar`, `jniLibs` canónicos ni estaciones
- los artefactos canónicos viven en `prebuilt/`

## Herramienta actual

- `truerngdiag`: interfaz de diagnóstico USB/TrueRNG para Android que consume el `AAR` exportado en `prebuilt/android/aar`

## Compilación

```bash
cd /home/willy/cifradorM
./scripts/android/compilacion/build-cifrador.sh
cd /home/willy/cifradorM/tools/android
./gradlew :truerngdiag:assembleDebug
```

## Nota sobre arquitecturas

- `prebuilt/android/jniLibs/arm64-v8a` es para teléfonos Android reales
- `prebuilt/android/jniLibs/x86_64` se mantiene para emulador y Waydroid
