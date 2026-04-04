# Scripts iOS

Scripts iOS organizados por funcion.

## Categorias

- `entorno/`
  - validaciones de toolchain y prerequisitos del host macOS
- `compilacion/`
  - `build-cifrador.sh`: genera el bundle `iOS bridge` consumido por la estacion iOS
- `pruebas/`
  - pruebas de integracion iOS sobre backend local de cifrado
- `empaquetado/`
  - empaquetado del bundle para distribucion de la estacion iOS

## Regla

- usar siempre las rutas categorizadas
- `build-cifrador.sh` es el punto de entrada de compilacion del cifrador iOS
- el cifrado de la estacion iOS se ejecuta en bridge local macOS con `jar + .dylib`
