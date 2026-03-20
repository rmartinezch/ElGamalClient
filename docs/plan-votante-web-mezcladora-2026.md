# Plan Votante Web y Servicio Externo 2026

## Fecha

2026-03-20

## Objetivo

Dejar operativo el flujo de prueba:

`votante Windows -> cifrador .jar -> servicio externo -> Verificatum`

Con estos criterios:

- sin dependencia de `Cifrador.exe`
- con voto plano compatible con `recursos/shuffled_votes.txt`
- con consulta de llave publica nativa desde un servicio HTTP externo
- con envio de votos cifrados a un servicio HTTP externo
- con un flujo manual documentado de extremo a extremo

## Cambio de arquitectura

La arquitectura local con `workflow/votante/mezcladora` fue retirada.

Desde esta fecha el repositorio usa como backend de recepcion:

```text
http://wsantivanez-hm:7040
```

Contrato vigente:

1. `GET /api/public-key`
2. `GET /api/public-key?format=verificatum`
3. `GET /api/auxsids`
4. `POST /api/ciphertexts`

## Estado actual

- [x] `workflow/votante/shared/vote-schema.md` creado
- [x] `workflow/votante/shared/catalogo-opciones.json` creado
- [x] interfaz `Windows` creada en `workflow/votante/windows/app`
- [x] interfaz `Windows` invoca directamente:
  - `dist/windows/image/Cifrador/runtime/bin/java.exe`
  - `dist/windows/image/Cifrador/app/ElGamalCipher-1.1.0.jar`
- [x] la interfaz `Windows` no depende de `Cifrador.exe`
- [x] cliente `Windows` adaptado al servicio `wsantivanez-hm:7040`
- [x] consulta de `auxsid` incorporada en UI y backend del votante
- [x] descarga de llave nativa incorporada
- [x] envio de `ciphertexts_ext` por JSON con `width=1` incorporado
- [x] `workflow/votante/mezcladora` limpiado de codigo operativo
- [x] `scripts/windows/run-mezcladora-didactica.ps1` convertido en aviso
  de retiro
- [x] manual de prueba completo reescrito en `workflow/README.md`
- [ ] shell `Android`

## Validacion realizada

Se confirmo acceso y respuesta del servicio remoto:

- `GET http://wsantivanez-hm:7040/api/auxsids`
- `GET http://wsantivanez-hm:7040/api/public-key`

Observacion validada:

- la llave publica de esta instancia llega envuelta en JSON y el campo
  `content` se debe decodificar desde hexadecimal a bytes antes de
  entregarlo al `.jar`

Respuesta observada de `auxsids` durante esta revision:

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

## Diseno de captura

### Voto canonicamente serializado

Cada elector emite un `BallotBundle` de `5` lineas:

```text
EN ED PP PV1 PV2
```

Cada linea tiene `10` caracteres y usa `00` como relleno de campos no
aplicables.

Ejemplo validado:

```text
0100030000
0200040102
0302040100
0402030104
0500050304
```

### Flujo de cifrado y envio

1. la UI captura decisiones electorales
2. el backend local del votante arma `plain_votes.txt`
3. descarga la llave desde `GET /api/public-key`
4. ejecuta el `.jar` directamente
5. genera `ciphertexts_ext`
6. consulta `GET /api/auxsids`
7. resuelve el `auxsid` a usar
8. envia:

```json
{
  "auxsid": "<auxsid>",
  "ciphertexts_ext": "<contenido>",
  "width": 1
}
```

## Entregables vigentes

### Windows

- `workflow/votante/windows/app/src`
- `workflow/votante/windows/app/public`
- `workflow/votante/windows/run-votante-windows.ps1`
- `workflow/votante/windows/README.md`

### Compartido

- `workflow/votante/shared/vote-schema.md`
- `workflow/votante/shared/catalogo-opciones.json`

### Documentacion

- `workflow/README.md`
- `docs/plan-votante-web-mezcladora-2026.md`

## Pendientes

1. implementar la shell `Android`
2. definir si Android enviara `ciphertexts` internos o `ciphertexts_ext`
3. agregar pruebas automatizadas adicionales sobre el cliente Windows
4. homologar catalogos demo con los insumos electorales finales

## Criterios de cierre

1. `Windows` genera el bundle esperado
2. `Windows` cifra usando solo `java.exe + .jar`
3. `Windows` obtiene la llave desde `wsantivanez-hm:7040`
4. `Windows` publica votos cifrados en `wsantivanez-hm:7040`
5. el manual permite repetir la prueba manual completa sin backend local
