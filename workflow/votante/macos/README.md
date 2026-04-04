# Votante macOS

Estacion de voto para macOS basada en el mismo backend Java usado por Windows, pero consumiendo el cifrador macOS (`jar + .dylib`) compilado en este repositorio.

## Objetivo

- generar `BallotBundle` canónico
- cifrar localmente en macOS con `ElGamalCipher-1.1.0.jar` y librerias JNI macOS
- descubrir mezcladoras activas, obtener `handshake`, consultar `GET /api/emission-context` y remitir `ciphertexts_ext`

## Arranque

```bash
./workflow/votante/macos/run-votante-macos.sh
```

UI por defecto:

- http://127.0.0.1:8789

Variables utiles:

- `PORT=8799`: cambia puerto local de la estacion
- `BIND_HOST=127.0.0.1`: restringe escucha local
- `PUBLIC_HOST=<ip-o-host>`: host publicado en logs
- `SERVICE_BASE_URL=http://host:7040`: semilla de discovery
- `AUXSID=default`: auxsid inicial sugerido

## Smoke test local

```bash
./workflow/votante/macos/test-votante-macos-smoke.sh
```

Ese smoke test:

- compila el cifrador macOS real
- levanta una mezcladora mock local
- ejecuta `DidacticWindowsVoterSmokeTest` en perfil `macos`
- valida que el flujo llegue a `accepted=true`
