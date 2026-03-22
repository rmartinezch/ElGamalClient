# Informe de Respuesta para Estaciones de Votacion

Fecha del informe: 2026-03-21
Zona horaria de referencia: America/Lima (-05)

## Objetivo

Responder al informe de estaciones y fijar el contrato tecnico que la GUI de
votacion debe seguir al integrarse con la mezcladora.

## Resumen ejecutivo

A partir de la revision del comportamiento observado se confirman estos puntos:

- la mezcladora trabaja con una sola sesion activa a la vez;
- dentro de esa sesion puede existir mas de un lote de votos cifrados;
- la unidad efectiva de acumulacion es el `auxsid`;
- la estacion no debe deducir el `auxsid` operativo mirando archivos, listas
  parciales o reglas locales;
- la estacion debe pedirle a la mezcladora el contexto operativo explicito antes
  de cifrar y antes de remitir votos.

La mezcladora fue ajustada para exponer un endpoint especifico:

- `GET /api/emission-context`

Ese endpoint publica, de forma explicita y consistente:

- `session_id`
- `session_name`
- `session_label`
- `election_name`
- `sid`
- `requested_auxsid`
- `resolved_auxsid`
- `auxsid`
- `auxsid_changed`
- `accumulated`
- `accumulated_from_auxsid`
- `accepting_votes`

## Hallazgos sobre el comportamiento anterior

Del informe recibido se desprendian dos problemas distintos:

### 1. Acumulacion interpretada como "por sesion"

Eso no era exacto.

La acumulacion no ocurre por compartir solamente `session_id` o `session_name`.
La acumulacion ocurre cuando dos cargas terminan cayendo sobre el mismo
`auxsid`.

Consecuencia:

- dos estaciones en la misma sesion pueden acumular juntas;
- pero solo si ambas usan el mismo `auxsid` efectivo;
- por eso la fuente de verdad debe venir del backend, no de una reserva local.

### 2. Inconsistencia entre UI y endpoints

Se detecto que la interfaz podia mostrar un `auxsid` sugerido distinto al que la
mezcladora exponia por endpoint.

Tambien se detecto que el backend podia contaminar la lista de `auxsid` con
nombres que no correspondian a lotes reales, por ejemplo derivados de:

- `ciphertexts_ext`
- `plaintexts_orig`

Eso ya fue corregido en la mezcladora.

## Contrato tecnico recomendado para estaciones

La estacion debe seguir este flujo:

1. descubrir una mezcladora activa con `GET /api/discovery`
2. registrar o renovar handshake con `POST /api/handshake`
3. pedir el contexto de emision con `GET /api/emission-context`
4. descargar la llave publica con `GET /api/public-key`
5. cifrar el voto
6. remitir el voto con `POST /api/ciphertexts`

## Regla principal

La estacion no debe decidir localmente el `auxsid` operativo final.

La estacion debe usar el `auxsid` devuelto por:

- `GET /api/emission-context`

y confirmar despues el `resolved_auxsid` devuelto por:

- `POST /api/ciphertexts`

## Endpoint recomendado para estaciones

### `GET /api/emission-context`

Ejemplo:

```bash
curl http://<host>:7040/api/emission-context
curl "http://<host>:7040/api/emission-context?auxsid=default"
```

Respuesta tipica:

```json
{
  "ok": true,
  "session_id": "servidor-1-20260321-180901",
  "session_name": "Servidor 1",
  "session_label": "Servidor 1",
  "election_name": "Elección Verificatum",
  "sid": "ONPE",
  "requested_auxsid": "default",
  "resolved_auxsid": "default22",
  "auxsid": "default22",
  "auxsid_changed": true,
  "accumulated": true,
  "accumulated_from_auxsid": "default22",
  "accepting_votes": true
}
```

Interpretacion:

- `session_id` y `session_name`: identifican la sesion activa real.
- `auxsid` y `resolved_auxsid`: lote real que debe usar la estacion.
- `auxsid_changed`: indica si la mezcladora ajusto el nombre pedido.
- `accumulated`: indica si el lote ya trae acumulado previo.
- `accumulated_from_auxsid`: indica desde que lote previo se hereda el estado.

## Recomendaciones concretas al codigo de estaciones

### 1. Fuente unica del auxsid

La estacion debe dejar de usar como fuente principal:

- `GET /api/auxsids`
- sugerencias locales guardadas
- reservas locales de `auxsid`

Esas fuentes pueden servir para diagnostico, pero no para decidir el lote final.

Fuente correcta:

- `GET /api/emission-context`

### 2. Momento de consulta

La estacion debe consultar `GET /api/emission-context` inmediatamente antes de:

- descargar la llave publica para una emision;
- cifrar el voto;
- enviar `POST /api/ciphertexts`.

No conviene guardar por mucho tiempo un `auxsid` previamente consultado si la
mezcladora pudo cambiar de estado entre una emision y otra.

### 3. Confirmacion posterior al envio

Despues de `POST /api/ciphertexts`, la estacion debe leer y persistir:

- `session_id`
- `session_name`
- `resolved_auxsid`
- `accumulated`
- `accumulated_from_auxsid`

Si `resolved_auxsid` difiere del solicitado, la estacion debe actualizar su
estado local con el valor real que devolvio la mezcladora.

### 4. Handshake

La estacion debe seguir mandando:

- `station_id`
- `lease_id`
- `session_id`
- `session_name`

al llamar a:

- `POST /api/ciphertexts`

Esto mantiene trazabilidad y valida que la carga corresponde a la sesion activa.

### 5. Polling recomendado

Para reducir ruido y estados inconsistentes:

- `GET /api/discovery` cada `10-15s`;
- `POST /api/handshake` solo al acercarse `expires_at` o antes de emitir;
- `GET /api/emission-context` justo antes de emitir;
- evitar loguear "mezcladora activa" repetidamente si no cambiaron:
  - `instance_id`
  - `session_id`
  - `auxsid`

## Comportamiento esperado por parte de la estacion

Mientras la sesion siga abierta, la estacion debe mostrar al operador:

- `serviceEndpoint` segun la mezcladora seleccionada;
- `serviceSession` usando `session_name` del backend;
- `serviceAuxsid` usando `auxsid` de `GET /api/emission-context` o
  `resolved_auxsid` de `POST /api/ciphertexts`.

Es decir:

- el `auxsid` visible en la estacion debe coincidir exactamente con el publicado
  por la mezcladora;
- no debe mostrarse un valor calculado por reglas locales si el backend ya dio
  uno distinto.

## Aclaracion sobre sesiones nuevas

La acumulacion solo vale dentro de la sesion activa.

Si el operador crea una nueva sesion en la mezcladora:

- se reinicia el espacio de trabajo;
- se reinician los handshakes;
- el acumulado anterior deja de aplicar;
- el primer lote de la nueva sesion vuelve a empezar desde el contexto que
  decida la mezcladora para esa sesion.

Por eso la estacion no debe arrastrar `auxsid` de una sesion anterior.

## Estado actual de la mezcladora

Despues de las correcciones aplicadas:

- `GET /api/emission-context` expone explicitamente sesion y lote operativo;
- la UI de parties fue alineada con ese endpoint;
- `GET /api/auxsids` ya no debe contaminarse con pseudo-auxsids como `ext`,
  `orig`, `ext_default2`, etc.;
- la remezcla de un lote existente sigue siendo posible desde `party01`;
- el `auxsid` visible en interfaz y el publicado por endpoint deben coincidir.

## Conclusiones para el equipo de estaciones

El cambio recomendado no es "adivinar mejor" el `auxsid`.

El cambio recomendado es:

- tratar a la mezcladora como fuente de verdad del lote operativo;
- consultar `GET /api/emission-context` antes de cada emision;
- usar ese `auxsid` exacto al cifrar y al publicar;
- reflejar en la UI exactamente los valores devueltos por la mezcladora.

## Accion recomendada

Actualizar la GUI de votacion para que el flujo operativo quede asi:

1. `GET /api/discovery`
2. `POST /api/handshake`
3. `GET /api/emission-context`
4. `GET /api/public-key`
5. cifrado local
6. `POST /api/ciphertexts`
7. actualizar UI con `resolved_auxsid`

Ese es el contrato recomendado a partir del estado actual del backend de la
mezcladora.
