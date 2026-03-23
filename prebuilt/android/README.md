# Android Prebuilt

Artefactos Android compilados fuera de `tools/`.

## Estructura

- `jniLibs/arm64-v8a/`: bibliotecas JNI Android ARM64
- `jniLibs/x86_64/`: bibliotecas JNI Android x86_64
- `aar/`: `AAR` exportado de la librería Android del cifrador

`tools/android` consume estos prebuilts; no es su ubicación canónica.
