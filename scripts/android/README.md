# Scripts Android

Scripts Android organizados por función.

## Categorías

- `compilacion/`
  - `build-cifrador.sh`: entrypoint principal para producir el cifrador Android
  - `build-debug.sh`: compila el `AAR` debug del cifrador Android y lo exporta a `prebuilt/android/aar`
  - `build-jni.sh`: compila y sincroniza `jniLibs` Android para `x86_64` y `arm64-v8a` en `prebuilt/android/jniLibs`
  - `build-truerng-diagnostic.sh`: compila la app `truerngdiag`
- `empaquetado/`
  - `build-cifrador-portable.sh`: genera la imagen portable Android en `dist/android/image/Cifrador`
  - `package-cifrador-portable.sh`: comprime esa imagen en `dist/android/*.zip`
- `pruebas/`
  - `test-connected.sh`: ejecuta pruebas instrumentadas conectadas
  - `test-hybrid-mix.sh`: cifra en Android y valida mezcla/descifrado en Linux con Verificatum
- `despliegue/`
  - `build-and-install-phone.sh`: compila cifrador, `tools/android` y la estación Android, luego instala ambos APK en un teléfono ADB
- `entorno/`
  - `doctor.sh`: valida prerequisitos base
  - `create-avd.sh`: crea el AVD headless usado en pruebas
  - `start-emulator.sh`: arranca el emulador Android
  - `start-waydroid-weston.sh`: reinicia `weston` y `waydroid`
  - `install-debug.sh`: informa que el cifrador Android ya no se instala como APK
  - `install-waydroid-cifrador.sh`: informa que el cifrador Android ya no se distribuye como APK instalable
  - `lib-android-env.sh`: helpers comunes de entorno Android

## Regla

- usar siempre las rutas categorizadas
- `build-cifrador.sh` es el punto de entrada de compilación del cifrador Android

## Flujo Teléfono

Para reproducir compilación + despliegue al teléfono en una sola orden:

```bash
./scripts/android/despliegue/build-and-install-phone.sh
```

Variables útiles:

- `DEVICE_SERIAL=<serial>`: fija el dispositivo ADB cuando hay más de uno conectado
- `BUILD_CIFRADOR=0`: reutiliza el `AAR` y las `jniLibs` ya compiladas
- `BUILD_TOOL_ANDROID=0`: reutiliza `dist/android/TrueRNG-Diagnostico.apk`
- `BUILD_VOTER_ANDROID=0`: reutiliza `dist/android/VotanteAndroid-portable.apk`
- `LAUNCH_APPS=1`: lanza ambas apps al final de la instalación
