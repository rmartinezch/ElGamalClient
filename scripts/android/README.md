# Scripts Android

- `doctor.sh`: valida prerequisitos base (Java, wrapper, SDK/adb).
- `build-debug.sh`: compila APK debug (`:app:assembleDebug`).
- `install-debug.sh`: instala APK debug por `adb` (`:app:installDebug`).
- `build-cifrador-portable.sh`: genera imagen portable en `dist/android/image/Cifrador`.
- `package-cifrador-portable.sh`: comprime imagen portable en `dist/android/*.zip`.
- `build-jni.sh`: compila y sincroniza `jniLibs` Android para `x86_64` y `arm64-v8a`.
- `create-avd.sh`: crea el AVD headless usado en pruebas.
- `start-emulator.sh`: arranca el emulador Android con aceleración automática.
- `test-connected.sh`: ejecuta las pruebas instrumentadas conectadas.
- `test-hybrid-mix.sh`: cifra en Android y valida mezcla/descifrado en Linux con Verificatum.
