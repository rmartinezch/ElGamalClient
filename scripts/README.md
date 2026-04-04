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
- `macos/`
  - `compilacion/`
  - `empaquetado/`
  - `ejecucion/`
  - `entorno/`
- `ios/`
  - `compilacion/`
  - `empaquetado/`
  - `pruebas/`
  - `entorno/`

## Regla

- los scripts vivos están solo en las subcarpetas funcionales
- no se mantienen wrappers duplicados en `scripts/<plataforma>/`
- cada plataforma debe exponer un entrypoint `build-cifrador` en `compilacion/`
- Android, Ubuntu, Windows, macOS e iOS deben mantener su compilación por categorías y sin scripts sueltos en la raíz de plataforma
