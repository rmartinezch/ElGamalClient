# Estado de Bloqueadores

Fecha de verificacion: 2026-03-06

## Bloqueadores Prioritarios

- [x] Corregido el manejo de rutas absolutas y relativas.
- [x] Corregida la carga de la llave publica en Linux con las librerias
  nativas locales del proyecto.
- [x] Validada una ejecucion funcional completa y reproducible en Linux.

## Cambios Aplicados

- [x] `ElGamalMain` ya no concatena manualmente el directorio actual con
  los argumentos de entrada.
- [x] Se agrego `NativeLibraryLoader` para relanzar el JAR con
  `java.library.path` correcto cuando encuentra `prebuilt/` local.
- [x] `ElGamalPublicKey` ahora reporta mejor la causa raiz cuando falta
  una dependencia nativa.
- [x] Se agregaron pruebas automatizadas para ejecucion con rutas
  relativas y absolutas.

## Evidencia de Pruebas

- [x] `mvn test`
  Resultado: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [x] `mvn -q -DskipTests package`
  Resultado: empaquetado exitoso del JAR

- [x] `java -jar target/ElGamalCipher-1.1.0.jar <abs-publicKey> <abs-votos> <abs-salida> -sw`
  Resultado: el proceso se relanza automaticamente con
  `java.library.path=/home/soettamusb/verificatum/cifrador/prebuilt/linux-x64`,
  carga la llave publica y genera salida cifrada valida

- [x] Verificacion del archivo de salida
  Resultado: archivo generado con contenido hexadecimal, tamano probado:
  `335` bytes en la corrida manual de referencia

## Notas

- El flujo validado en esta etapa corresponde al entorno Linux actual.
- La siguiente fase puede arrancar sobre una linea base ya funcional.
