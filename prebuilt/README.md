# Prebuilt

Artefactos nativos ya compilados que el repositorio consume como insumo de desarrollo y empaquetado.

## Estructura

- `android/`: artefactos Android reutilizables (`jniLibs` por ABI y `AAR`)
- `java/`: `jar` Java canónico compartido por Ubuntu y Windows
- `linux-x64/`: bibliotecas `.so` para Linux x64
- `windows-x64/`: bibliotecas `.dll` para Windows x64

## Alcance

- Este directorio no reemplaza a `dist/`.
- `dist/` guarda artefactos finales de distribución.
- `prebuilt/` guarda insumos compilados reutilizados por pruebas, build local y empaquetado.
