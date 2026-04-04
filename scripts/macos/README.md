# Scripts macOS

Scripts macOS organizados por funcion.

## Categorias

- `entorno/`
  - `bootstrap-verificatum.sh`: valida artefactos Maven locales de Verificatum
- `compilacion/`
  - `build-cifrador.sh`: entrypoint principal para producir el cifrador macOS
  - `build-native-macos.sh`: compila `vec`, `gmpmee`, `vecj` y `vmgj` para Darwin
- `ejecucion/`
  - `run-cifrador.sh`: ejecuta el jar canonico con `.dylib` macOS
- `empaquetado/`
  - `build-cifrador-portable.sh`: arma imagen portable en `dist/macos/image/Cifrador`
  - `package-cifrador-portable.sh`: comprime la imagen en `dist/macos/*.tar.gz`

## Regla

- usar siempre las rutas categorizadas
- `build-cifrador.sh` es el punto de entrada de compilacion del cifrador macOS
- el flujo debe mantener separacion entre artefacto Java canonico y binarios nativos de plataforma
