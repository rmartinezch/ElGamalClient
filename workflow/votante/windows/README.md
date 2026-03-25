# Votante Windows

Shell didactica para `Windows` con UI web y bridge local al
`ElGamalCipher-1.1.0.jar`.

## Objetivo

- recoger un `BallotBundle` del elector
- serializarlo al esquema `CodedVote`
- invocar directamente `java.exe + app\ElGamalCipher-1.1.0.jar + DLL nativas`
- no depender de `Cifrador.exe` ni de ningun launcher del cifrador
- consultar `GET /api/emission-context` antes de emitir
- remitir `ciphertexts_ext` al servicio externo en `wsantivanez-hm:7040`

## Reutilizacion

El cifrador Windows queda desacoplado de esta UI:

- puede ser invocado o consumido por cualquier interfaz de votación para Windows
- la interfaz ubicada en `workflow/votante/windows` es solo un ejemplo de uso dentro de este repositorio
- el punto estable de integración es el flujo compilado `ElGamalCipher-1.1.0.jar + DLL nativas`
- la estación no consume fuentes del cifrador; consume el bundle compilado en `dist/windows/library/Cifrador`

## Arranque

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1
```

UI:

```text
http://127.0.0.1:8788
http://<ip-local-de-la-estacion>:8788
```

Por defecto la estacion escucha en `0.0.0.0`, por lo que queda accesible desde la red local. Si desea restringirla solo a la maquina local, use:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1 -BindHost 127.0.0.1
```

Si desea fijar un `auxsid` por defecto:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1 -Auxsid default
```

Antes del arranque, el cifrador Windows debe existir como artefacto compilado:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\windows\empaquetado\build-cifrador-portable.ps1
```

## Rutas relevantes

- fuentes Java:
  - `workflow/votante/windows/app/src`
- UI estatica:
  - `workflow/votante/windows/app/public`
- ejecuciones locales:
  - `workflow/votante/windows/runtime/submissions`

## Contrato

La ruta `POST /api/ballot/submit`:

1. construye el `BallotBundle`
2. descubre mezcladoras candidatas con `GET /api/discovery`
3. solicita `handshake` y conserva `station_id + lease_id`
4. consulta `GET /api/emission-context` para obtener el `auxsid` operativo real
5. obtiene la llave nativa en `GET /api/public-key`
   y decodifica el campo JSON `content` desde hexadecimal a bytes
6. ejecuta el cifrador con `java.exe -jar ... -sw`
7. envia `ciphertexts_ext` en `POST /api/ciphertexts`
   con `{"station_id","lease_id","session_id","session_name","auxsid","format","ciphertexts_ext","width":1}`

La estación Windows queda alineada al backend:

- el `auxsid` visible sale de `GET /api/emission-context`
- después del envío se persiste `resolved_auxsid`, `accumulated` y `accumulated_from_auxsid`
- `GET /api/auxsids` deja de decidir el lote operativo
- `serviceBaseUrl` puede usarse como semilla opcional, pero la estación sigue barriendo la red local si esa URL no responde o no coincide con la sesión activa

## Portable del votante

`package-votante-windows-portable.ps1` toma el cifrador ya compilado desde:

- `dist/windows/library/Cifrador/app/ElGamalCipher-1.1.0.jar`
- `dist/windows/library/Cifrador/libs/windows-x64`

El runtime Java portable de la estación se empaqueta por separado para ejecutar:

- el servidor local del votante
- el `jar` del cifrador consumido como librería

Si ese bundle no existe, lo reconstruye llamando a `scripts/windows/empaquetado/build-cifrador-portable.ps1`.
