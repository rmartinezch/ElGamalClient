# Native

Fuentes nativas y JNI usadas para compilar `vecj` y `vmgj` para varias plataformas.

## Estructura

- `verificatum-src/verificatum-vec-2.5.0`
- `verificatum-src/verificatum-gmpmee-2.1.0`
- `verificatum-src/verificatum-vecj-2.2.0`
- `verificatum-src/verificatum-vmgj-1.3.0`

## Uso

- Android: `scripts/android/compilacion/build-jni.sh` prioriza este árbol antes de buscar en `VERIFICATUM_SOURCE_ROOT` o en `~/mixnet`.
- Ubuntu: `scripts/ubuntu/compilacion/build-native-linux.sh` recompila `vec`, `gmpmee`, `vecj` y `vmgj` directamente desde este árbol.
- Windows: `scripts/windows/compilacion/build-native-windows.ps1` prioriza este árbol antes de buscar fuentes fuera del repositorio.

Este directorio concentra el insumo fuente común para Ubuntu, Windows y Android. Los artefactos empaquetados finales no van aquí: deben quedar en `dist/`.
