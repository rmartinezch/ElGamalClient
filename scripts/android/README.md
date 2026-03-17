# Scripts Android

- `doctor.sh`: valida prerequisitos base (Java, wrapper, SDK/adb).
- `build-debug.sh`: compila APK debug (`:app:assembleDebug`).
- `install-debug.sh`: instala APK debug por `adb` (`:app:installDebug`).
- `build-cifrador-portable.sh`: genera imagen portable en `dist/android/image/Cifrador`.
- `package-cifrador-portable.sh`: comprime imagen portable en `dist/android/*.zip`.
- `build-jni.sh`: compila y sincroniza `jniLibs` Android para `x86_64` y `arm64-v8a`.
- `create-avd.sh`: crea el AVD headless usado en pruebas.
- `start-emulator.sh`: arranca el emulador Android con aceleración automática.
- `start-waydroid-weston.sh`: reinicia `weston` y `waydroid` desde cero y abre la UI sobre un socket Wayland nuevo.
- `install-waydroid-cifrador.sh`: instala el APK, un visor TXT, y los recursos sueltos en Waydroid, y ejecuta una autoprueba con esos archivos.
- `test-connected.sh`: ejecuta las pruebas instrumentadas conectadas.
- `test-hybrid-mix.sh`: cifra en Android y valida mezcla/descifrado en Linux con Verificatum.
