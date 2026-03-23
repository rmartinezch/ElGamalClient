# Scripts Windows

Scripts operativos para `Windows x64`.

El `jar` del cifrador que consume Windows no es específico de Windows. Se toma de
`prebuilt/java`, compartido con Ubuntu, mientras que `prebuilt/windows-x64`
solo contiene las DLL nativas JNI específicas de esa plataforma.

## Categorías

- `compilacion/`
  - `build-cifrador.ps1`
  - `bootstrap-verificatum.ps1`
  - `build-native-windows.ps1`
- `empaquetado/`
  - `build-cifrador-portable.ps1`
  - `package-windows.ps1`
  - `package-cifrador-portable.ps1`
- `pruebas/`
  - `test-remote-verificatum-mix.ps1`
  - `test-remote-verificatum-mix.bat`

## Regla

- usar siempre las rutas categorizadas
- `build-cifrador.ps1` es el punto de entrada de compilación del cifrador Windows
- `build-cifrador-portable.ps1` arma la imagen portable en `dist/windows/image/Cifrador`
