# Votante Windows

Shell didactica para `Windows` con UI web y bridge local al
`ElGamalCipher-1.1.0.jar`.

## Objetivo

- recoger un `BallotBundle` del elector
- serializarlo al esquema `CodedVote`
- invocar directamente `runtime\bin\java.exe + app\ElGamalCipher-1.1.0.jar`
- no depender de `Cifrador.exe`
- remitir `ciphertexts_ext` al servicio externo en `wsantivanez-hm:7040`

## Arranque

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1
```

UI:

```text
http://127.0.0.1:8788
```

Si desea fijar un `auxsid` por defecto:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1 -Auxsid default
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
4. obtiene la llave nativa en `GET /api/public-key`
   y decodifica el campo JSON `content` desde hexadecimal a bytes
5. ejecuta el cifrador con `java.exe -jar ... -sw`
6. envia `ciphertexts_ext` en `POST /api/ciphertexts`
   con `{"station_id","lease_id","session_id","session_name","auxsid","format","ciphertexts_ext","width":1}`
