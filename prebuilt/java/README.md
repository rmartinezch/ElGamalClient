# Prebuilt Java

Artefactos Java canónicos producidos por Maven y reutilizados por Ubuntu y Windows.

## Regla

- el `jar` del cifrador es bytecode Java y no cambia por sistema operativo
- por eso la ubicación canónica es única: `prebuilt/java`
- Ubuntu y Windows deben consumir el mismo `jar`

## Relación con `target/`

- `target/` sigue siendo salida transitoria de Maven
- `target/` contiene clases, reportes de pruebas y artefactos temporales del build
- el `jar` que se publica para consumo posterior debe copiarse desde `target/` a esta carpeta

## Artefacto esperado

- `prebuilt/java/ElGamalCipher-1.1.0.jar`
