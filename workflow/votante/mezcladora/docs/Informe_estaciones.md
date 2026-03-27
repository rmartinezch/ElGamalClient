# Informe Tecnico de Acumulacion entre Estaciones

Fecha del informe: 2026-03-21
Zona horaria de referencia: America/Lima (-05)

## Resumen ejecutivo

Se verificaron dos estaciones del votante conectadas a la misma mezcladora:

- Telefono Android real por ADB USB: `ESU4C19412000693`
- Waydroid sobre Weston: `192.168.240.112:5555`

Ambas estaciones estuvieron conectadas a:

- `serviceEndpoint = http://192.168.0.120:7040`
- `serviceSession = Servidor 1`

En la corrida relevante de acumulacion, ambas estaciones usaron el mismo
`auxsid = default3`.

Resultado observado:

- el primer envio a `default3` respondio `accumulated=false`
- el segundo envio a `default3` respondio `accumulated=true`

Conclusion:

- la mezcladora si acumula cuando dos envios caen sobre el mismo `auxsid`
- la acumulacion no depende solo de compartir la misma sesion
- la unidad efectiva de acumulacion es el `auxsid`

## Metodologia

Se tomaron evidencias desde:

- `GET /api/discovery`
- `GET /api/auxsids`
- arbol de accesibilidad Android (`uiautomator dump`) en telefono y Waydroid
- monitor de eventos visible dentro de cada estacion

Archivos de evidencia usados:

- `/.build/votante-phone.xml`
- `/.build/votante-waydroid.xml`

## Estado de la mezcladora

Consulta a `GET http://192.168.0.120:7040/api/discovery`:

- `session_id = servidor-1-20260321-180901`
- `session_name = Servidor 1`
- `session_label = Servidor 1`
- `election_name = Elección Verificatum`
- `accepting_votes = true`

Consulta a `GET http://192.168.0.120:7040/api/auxsids` a las `2026-03-21 19:12:07 -05`:

```json
{
  "ok": true,
  "used_auxsids": ["default", "default2", "default22"],
  "reserved_auxsids": ["default", "default2", "default22", "default3", "ext", "ext_default2", "ext_default22", "ext_default3", "orig", "orig_default2", "orig_default22"],
  "pending_ciphertexts": ["default", "default2", "default22", "default3", "ext", "ext_default2", "ext_default22", "ext_default3"],
  "next_shuffle": "default3",
  "next_decrypt": null,
  "next_verify": "default",
  "suggested_auxsid": "default4"
}
```

Observacion:

- el backend mantiene una sola sesion activa
- pero maneja multiples lotes separados por `auxsid`

Consulta posterior a `GET http://192.168.0.120:7040/api/auxsids` a las
`2026-03-21 19:18:09 -05`:

- `suggested_auxsid = default4`
- `next_shuffle = default3`

Esto confirma que, aun con `default3` todavia pendiente de procesamiento, la
mezcladora ya esta exponiendo `default4` como siguiente `auxsid` sugerido para
nuevas estaciones o nuevos envios.

## Comparacion de estaciones

### 1. Estado visible actual

Tanto telefono como Waydroid mostraban en la UI:

- `serviceEndpoint = http://192.168.0.120:7040`
- `serviceSession = Servidor 1`
- `serviceAuxsid = default3`

Esto confirma que ambas estaciones podian ver el mismo `auxsid` de trabajo en
la corrida relevante.

### 2. Waydroid

Del monitor de eventos de Waydroid:

- `23:36:13` la estacion inicializa y detecta `Servidor 1`
- `23:36:13` `Auxsid asignado automaticamente: default`
- `23:36:42` envio a `auxsid = default`
- `23:36:42` respuesta API con `accumulated=false`
- `23:58:36` nueva emision
- `23:58:36` `Auxsid operativo resuelto: default3`
- `23:58:37` respuesta API con `accumulated=false`

Extracto relevante:

```text
[23:58:37] ... Auxsid operativo resuelto: default3
[23:58:37] ... Respuesta API => ... accumulated=false ...
```

Interpretacion:

- Waydroid fue el primer envio observado sobre `default3`
- por eso la mezcladora respondio `accumulated=false`

### 3. Telefono Android

Del monitor de eventos del telefono:

- `18:45:54` `Auxsid asignado automaticamente: default2`
- `18:46:17` envio a `auxsid = default2`
- `18:46:17` respuesta API con `accumulated=false`
- `18:58:49` nueva emision
- `18:58:49` `Auxsid operativo resuelto: default3`
- `18:58:49` respuesta API con `accumulated=true`

Extractos relevantes:

```text
[18:46:17] ... Auxsid operativo resuelto: default2
[18:46:17] ... Respuesta API => ... accumulated=false ...
```

```text
[18:58:49] ... Auxsid operativo resuelto: default3
[18:58:49] ... Respuesta API => ... accumulated=true ...
```

Adicionalmente, dentro del bloque `session.events` que vino en la respuesta del
segundo envio del telefono aparecen estas lineas:

```text
[2026-03-21T18:58:37-05:00] Ciphertexts validados y cargados vía API para AuxSID default3 con formato native.
[2026-03-21T18:58:46-05:00] Ciphertexts validados y cargados vía API para AuxSID default3 con formato native. Carga acumulada sobre ciphertexts previos desde AuxSID default3.
```

Interpretacion:

- para cuando el telefono envio a `default3`, ya existia al menos un lote previo
  en `default3`
- por eso la mezcladora respondio `accumulated=true`

## Secuencia temporal consolidada

Orden observado:

1. Waydroid envio a `default` y recibio `accumulated=false`
2. Telefono envio a `default2` y recibio `accumulated=false`
3. Waydroid envio a `default3` y recibio `accumulated=false`
4. Telefono envio a `default3` y recibio `accumulated=true`

Esto es consistente con una politica donde:

- el primer voto sobre un `auxsid` abre el lote
- el segundo voto sobre el mismo `auxsid` se acumula

## Hallazgo principal para el equipo de mezcladora

La mezcladora no esta acumulando por sesion en abstracto.

La mezcladora acumula por `auxsid`.

Por lo tanto:

- compartir la misma sesion no garantiza acumulacion
- compartir el mismo `auxsid` si habilita la acumulacion
- la primera estacion que abre un `auxsid` obtiene `accumulated=false`
- la segunda estacion que envia al mismo `auxsid` obtiene `accumulated=true`

## Inconsistencia observada

Aunque ambas estaciones pudieron coincidir en `default3`, el endpoint
`/api/auxsids` ya reportaba al mismo tiempo:

- `next_shuffle = default3`
- `suggested_auxsid = default4`

Esto sugiere que el backend:

- conserva `default3` como lote pendiente de procesamiento
- pero ya ofrece `default4` como siguiente lote sugerido

Eso puede provocar que dos estaciones consultando en momentos ligeramente
distintos no siempre obtengan el mismo `auxsid` sugerido, aun dentro de la
misma sesion activa.

## Conclusion operativa

Con la implementacion actual del backend:

- la afirmacion "misma sesion abierta => ambas estaciones siempre deben ver el
  mismo auxsid" no se cumple de forma estable
- lo que si se verifico es:
  - ambas estaciones pueden llegar a ver el mismo `auxsid`
  - cuando ambas usan el mismo `auxsid`, la mezcladora si acumula

## Recomendacion al equipo de mezcladora

Si el contrato deseado es:

> mientras una sesion siga abierta, todas las estaciones deben acumular sobre el
> mismo lote

entonces el backend deberia:

- mantener un `suggested_auxsid` comun y estable por sesion mientras el lote siga abierto
- no rotar a un nuevo `auxsid` hasta cerrar explicitamente el lote anterior

Si el contrato deseado es distinto y se quiere trabajar con multiples lotes por
sesion, entonces conviene documentar explicitamente que:

- la acumulacion se hace por `auxsid`
- no por sesion
- y que estaciones consultando en distintos momentos pueden recibir
  `auxsid` distintos dentro de la misma sesion activa
