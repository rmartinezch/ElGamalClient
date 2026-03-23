# Scripts Ubuntu

Estos scripts son para flujo local en Ubuntu/Linux.

## Categorías

- `entorno/`
  - `bootstrap-verificatum.sh`: valida artefactos locales de Verificatum en `.mvn/local-repo`
- `compilacion/`
  - `build-cifrador.sh`: entrypoint principal para producir el cifrador Ubuntu
  - `build-native-linux.sh`: compila `vec`, `gmpmee`, `vecj` y `vmgj` desde `native/verificatum-src`
- `ejecucion/`
  - `run-cifrador.sh`: compila si hace falta y ejecuta el `jar` canónico
- `empaquetado/`
  - `build-cifrador-portable.sh`: genera imagen portable en `dist/linux/image/Cifrador`
  - `package-cifrador-portable.sh`: comprime imagen portable en `dist/linux/*.tar.gz`

## Regla

- usar siempre las rutas categorizadas
- `build-cifrador.sh` es el punto de entrada de compilación del cifrador Ubuntu
- ese entrypoint compila el `jar` canónico y regenera las `.so` Linux en `prebuilt/linux-*`
- `build-native-linux.sh` es un paso interno especializado; no reemplaza al entrypoint `build-cifrador.sh`
