# Scripts

Scripts organizados por plataforma y por función.

## Estructura

- `android/`
  - `compilacion/`
  - `empaquetado/`
  - `pruebas/`
  - `entorno/`
- `ubuntu/`
  - `compilacion/`
  - `empaquetado/`
  - `ejecucion/`
  - `entorno/`
- `windows/`
  - `compilacion/`
  - `empaquetado/`
  - `pruebas/`

## Regla

- los scripts vivos están solo en las subcarpetas funcionales
- no se mantienen wrappers duplicados en `scripts/<plataforma>/`
- cada plataforma debe exponer un entrypoint `build-cifrador` en `compilacion/`
- Android, Ubuntu y Windows regeneran sus insumos nativos desde `native/verificatum-src`
