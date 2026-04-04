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

## Smoke test local

```bash
./workflow/votante/ios/test-votante-ios-smoke.sh
```

El smoke test valida el flujo completo con mezcladora mock y confirma `accepted=true`.
