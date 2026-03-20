# Workflow Manual de Prueba

Guia manual completa para probar el flujo:

`votante Windows -> servicio externo -> Verificatum`

Estado de este manual: `2026-03-20`.

## 1. Objetivo

Validar manualmente que:

1. la interfaz Windows genere un `BallotBundle` valido
2. el cifrador se invoque directamente con `java.exe + .jar`
3. el servicio externo entregue la llave publica nativa compatible con el `.jar`
4. el servicio externo reciba `ciphertexts_ext`
5. el flujo quede registrado con un `auxsid`

## 2. Prerrequisitos

En la maquina Windows:

- repositorio en `D:\_Proyectos\cifradorM`
- `javac` y `java` disponibles en `PATH`
- resolucion de red hacia:
  - `wsantivanez-hm`
- acceso HTTP al servicio:
  - `http://wsantivanez-hm:7040`

Puertos locales usados:

- `8788` para `votante Windows`

## 3. Artefactos principales

- esquema compartido:
  - `workflow/votante/shared/vote-schema.md`
  - `workflow/votante/shared/catalogo-opciones.json`
- interfaz Windows:
  - `workflow/votante/windows/app`
- arranque votante Windows:
  - `workflow/votante/windows/run-votante-windows.ps1`

## 4. Paso 1: validar el servicio externo

Consultar `auxsid`:

```powershell
Invoke-RestMethod -Uri 'http://wsantivanez-hm:7040/api/auxsids'
```

Consultar llave publica nativa:

```powershell
Invoke-WebRequest -UseBasicParsing -Uri 'http://wsantivanez-hm:7040/api/public-key'
```

Consultar llave publica en formato Verificatum:

```powershell
Invoke-WebRequest -UseBasicParsing -Uri 'http://wsantivanez-hm:7040/api/public-key?format=verificatum'
```

Respuesta observada en esta revision:

```json
{
  "ok": true,
  "used_auxsids": [],
  "reserved_auxsids": [],
  "pending_ciphertexts": [],
  "next_shuffle": null,
  "next_decrypt": null,
  "next_verify": null,
  "suggested_auxsid": "default"
}
```

Observacion operativa:

- en esta instancia, `GET /api/public-key` responde un JSON con el campo
  `content` en hexadecimal; el cliente Windows lo decodifica a bytes
  antes de invocar el `.jar`

## 5. Paso 2: iniciar la interfaz del votante Windows

Abrir una consola PowerShell en `D:\_Proyectos\cifradorM` y ejecutar:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1
```

Abrir luego:

```text
http://127.0.0.1:8788
```

Si desea fijar un `auxsid` por defecto:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\workflow\votante\windows\run-votante-windows.ps1 -Auxsid default
```

## 6. Paso 3: emitir un voto manual desde Windows

En la UI del votante:

1. revisar el `auxsid` sugerido
2. elegir el distrito electoral
3. completar las cinco elecciones
4. pulsar `Consultar auxsid` si desea refrescar la sugerencia
5. pulsar `Previsualizar`
6. pulsar `Emitir voto de prueba`

Valores de prueba validados:

- `auxsid=default`
- `districtCode=02`
- `presidentialParty=03`
- `senatorsNationalParty=04`
- `senatorsNationalPv1=01`
- `senatorsNationalPv2=02`
- `senatorsRegionalParty=04`
- `senatorsRegionalPv1=01`
- `deputiesParty=03`
- `deputiesPv1=01`
- `deputiesPv2=04`
- `andeanParty=05`
- `andeanPv1=03`
- `andeanPv2=04`

Bundle esperado:

```text
0100030000
0200040102
0302040100
0402030104
0500050304
```

La interfaz:

1. consulta `GET /api/auxsids`
2. descarga `publicKey` desde `GET /api/public-key`
   y decodifica `content` hexadecimal a bytes
3. ejecuta:

```text
dist/windows/image/Cifrador/runtime/bin/java.exe
dist/windows/image/Cifrador/app/ElGamalCipher-1.1.0.jar
```

4. genera `ciphertexts_ext`
5. envia el lote a:

```text
POST http://wsantivanez-hm:7040/api/ciphertexts
```

Payload:

```json
{
  "auxsid": "default",
  "ciphertexts_ext": "<contenido del archivo>",
  "width": 1
}
```

Los artefactos locales quedan en:

```text
workflow/votante/windows/runtime/submissions/<runId>
```

## 7. Paso 4: enviar manualmente con curl

Si desea omitir la UI y probar solo el servicio:

```powershell
$ciphertextsExt = Get-Content -Raw '.\workflow\votante\windows\runtime\submissions\<runId>\ciphertexts_ext'

$payload = @{
  auxsid = 'default'
  ciphertexts_ext = $ciphertextsExt
  width = 1
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method POST `
  -Uri 'http://wsantivanez-hm:7040/api/ciphertexts' `
  -ContentType 'application/json; charset=utf-8' `
  -Body $payload
```

Si ya tiene `ciphertexts` internos de Verificatum:

```powershell
$ciphertexts = Get-Content -Raw '.\ruta\ciphertexts'

$payload = @{
  auxsid = 'default'
  ciphertexts = $ciphertexts
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method POST `
  -Uri 'http://wsantivanez-hm:7040/api/ciphertexts' `
  -ContentType 'application/json; charset=utf-8' `
  -Body $payload
```

## 8. Paso 5: revisar artefactos locales

Cada envio deja:

- `publicKey`
- `plain_votes.txt`
- `ciphertexts_ext`
- `cifrador.stdout.log`
- `cifrador.stderr.log`

Ubicacion:

```text
workflow/votante/windows/runtime/submissions/<runId>
```

## 9. Observaciones operativas

1. El votante Windows ya no depende de `Cifrador.exe`.
2. La `mezcladora` local fue eliminada del repositorio.
3. El host de servicio configurado por defecto es `wsantivanez-hm`.
4. La llave usada por el cifrador debe pedirse en formato nativo.
5. El envio por defecto desde la UI usa `ciphertexts_ext` con `width=1`.
