# Votante iOS

Estacion de voto orientada a iOS usando frontend web compatible con Safari iOS y backend local de estacion en macOS para cifrado y remision.

## Objetivo

- ejecutar una estacion de emision que pueda operar desde Safari en iPhone o simulador iOS
- reutilizar el cifrador compilado para macOS como backend local de cifrado (`jar + .dylib`)
- mantener el mismo contrato de integracion con la mezcladora (`discovery`, `handshake`, `emission-context`, `public-key`, `ciphertexts`)

## Arranque

```bash
./workflow/votante/ios/run-votante-ios-station.sh
```

UI por defecto:

- http://127.0.0.1:8798

Variables utiles:

- `PORT=8798`: puerto de la estacion iOS
- `BIND_HOST=0.0.0.0`: escuchar para acceso desde iPhone real en la red local
- `SERVICE_BASE_URL=http://host:7040`: semilla de discovery
- `OPEN_SIMULATOR=1`: intenta abrir la URL en el simulador iOS booted

## Compilacion automatica de la app nativa autonoma

```bash
./workflow/votante/ios/build-ios-native-autonomous.sh
```

Este script:

- recompila `verificatum-vcr` en Java puro (`--release 17`)
- recompila `ElGamalCipher` y actualiza `prebuilt/java/ElGamalCipher-1.1.0.jar`
- sincroniza artefactos en `native/verificatum-jars`, `.mvn/local-repo` y `~/.m2/repository`
- ejecuta el build de Gluon para `ios-sim`
- aplica el workaround de linker del simulador
- parchea el chequeo de CPU del binario final
- instala y relanza la app en el simulador booted

Variables utiles:

- `INSTALL_TO_SIMULATOR=0`: solo genera el `.app` sin instalarla
- `LAUNCH_APP=0`: instala, pero no la abre
- `BOOTED_DEVICE=<udid|booted>`: destino de `simctl install`
- `JAVA21_HOME=/ruta/jdk-21`
- `GRAALVM_HOME=/ruta/graalvm`

## Compilacion solo del cifrador Java puro

```bash
./workflow/votante/ios/build-cifrador-java-puro.sh
```

Este script recompila y sincroniza solo:

- `verificatum-vcr` en Java puro
- `ElGamalCipher-1.1.0.jar`
- `prebuilt/java/ElGamalCipher-1.1.0.jar`
- repos locales `.mvn/local-repo`, `native/verificatum-jars` y `~/.m2/repository`

## Fallback local opcional para simulador

La app nativa iOS del simulador ahora intenta cifrar y emitir de forma autonoma.
Solo si quieres forzar un plan de contingencia desde macOS, puedes levantar:

```bash
python3 workflow/votante/ios/run-ios-sim-fallback-bridge.py
```

El bridge escucha por defecto en `http://127.0.0.1:8798` y:

- recibe `POST /api/ballot/submit`
- obtiene `publicKey` desde la mezcladora con Python
- ejecuta `ElGamalCipher-1.1.0.jar` en macOS
- remite `ciphertexts_ext` al `POST /api/ciphertexts`

La app nativa no usa este bridge salvo que se configure explicitamente
`-Dvotante.ios.bridgeFallbackBaseUrl=http://127.0.0.1:8798`.

Variables utiles:

- `SERVICE_BASE_URL=http://192.168.0.120:7040`
- `IOS_SIM_BRIDGE_HOST=127.0.0.1`
- `IOS_SIM_BRIDGE_PORT=8798`
- `IOS_SIM_BRIDGE_STATION_ID=mesa-047612`

## Smoke test local

```bash
./workflow/votante/ios/test-votante-ios-smoke.sh
```

El smoke test valida el flujo completo con mezcladora mock y confirma `accepted=true`.
