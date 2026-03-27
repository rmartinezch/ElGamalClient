# GUI Mezcladora Verificatum

GUI web local para operar varias `parties` de Verificatum en una sola PC, sin `xterm`, usando un panel principal en `7040` y una ventana web por `party` en `70xx`.

Ubicada en `workflow/votante/mezcladora` dentro de `cifradorM`. Las estaciones de voto (`windows`, `android`) consumen el servicio que expone esta mezcladora.

## Resumen

- El panel principal `7040` solo gestiona la etapa preliminar:
  - variables iniciales;
  - creación de una nueva sesión de trabajo;
  - preparación del protocolo;
  - ejecución de `vmn -keygen -e publicKey`;
  - monitor general de eventos y errores.
- Las ventanas de `party` viven en `7041`, `7042`, `7043`, etc.
- Los bloques `Sesión activa` y `Ventanas y parties` se generan dinámicamente solo después de que la sesión fue creada y `keygen` terminó correctamente.
- La interfaz toma como referencia visual los HTML de `Interfaz/` en el repositorio raíz de Verificatum.

## Flujo operativo

### 1. Etapa preliminar en `7040`

El puerto `7040` muestra únicamente:

- `Etapa preliminar`
- `Monitor general`

Desde ahí el operador define:

- etiqueta;
- nombre de elección;
- `SID`;
- `host/IP`;
- número de `parties`;
- umbral mínimo.

Cada clic en `Nueva sesión y ejecutar keygen`:

- crea una carpeta de trabajo nueva;
- reinicia el registro de estaciones y handshakes vigentes;
- prepara `stub.xml`, `privInfo.xml`, `protInfoXX.xml` y `protInfo.xml`;
- ejecuta `keygen`;
- deja la salida y cualquier fallo en el monitor general.

La acumulación de votos cifrados es local a la sesión activa:

- mientras no se cree una sesión nueva, las cargas al mismo `auxsid` se acumulan por defecto;
- si ese `auxsid` ya fue mezclado o descifrado, la GUI abre `default2`, `default3`, etc. heredando el acumulado anterior;
- al crear una sesión nueva, el acumulado anterior deja de aplicar y el primer lote vuelve a empezar en `default`.

### 2. Ventanas de parties en `70xx`

Cuando `keygen` termina bien, la GUI expone las ventanas de las parties:

- `7041` -> `party01`
- `7042` -> `party02`
- `7043` -> `party03`
- etc.

Cada ventana muestra:

- resumen de la sesión;
- artefactos locales;
- estado de fases;
- monitor de eventos;
- monitor de consola con la salida capturada de Verificatum para esa `party`;
- descarga de votos mezclados y descifrados una vez que termine la fase `Descifrar` para el `AuxSID` seleccionado.

### 3. Fases secuenciales por party

Las fases distribuidas replican el comportamiento de las interfaces de mixservers:

- `Precomputación`
- `Mezclar`
- `Descifrar`
- `Verificar`

Reglas:

- `Precomputación` es opcional.
- `Ciphertexts demo` solo existe como utilidad local en `party01`.
- Las fases anteriores a `verify` usan el `AuxSID` de la mezcla.
- Cada `party` se habilita por turno.
- `party02` espera a que `party01` inicie la fase.
- `party03` espera a `party02`, y así sucesivamente.

La GUI muestra estados visuales por fase como:

- `mi turno`
- `esperando party anterior`
- `ya iniciada`
- `en ejecución`
- `completada`
- `bloqueada`
- `omitida`

## Puertos y publicación

Por defecto el arranque escucha en todas las interfaces y publica URLs usando el hostname local.

## Ejecutar

```bash
cd workflow/votante/mezcladora
./start_gui.sh
```

Variables útiles:

- `HOST`: interfaz de escucha real. Por defecto `0.0.0.0`.
- `PUBLIC_HOST`: host que la GUI imprime y usa en enlaces. Por defecto `$(hostname)`.
- `VERIFICATUM_GUI_CLEAN_START`: si vale `1`, limpia `runtime/` al arrancar. Por defecto `1`.

Ejemplos:

```bash
./start_gui.sh
PUBLIC_HOST=wsantivanez-hm ./start_gui.sh
HOST=127.0.0.1 PUBLIC_HOST=127.0.0.1 ./start_gui.sh
./start_gui.sh 7140
```

Con el arranque por defecto:

- panel principal: `http://<hostname>:7040`
- `party01`: `http://<hostname>:7041`
- `party02`: `http://<hostname>:7042`
- `party03`: `http://<hostname>:7043`

## Empaquetado portable

Se puede generar un tarball portable de la mezcladora para moverlo a otro Ubuntu que ya tenga Verificatum instalado.

Generación:

```bash
cd workflow/votante/mezcladora
./scripts/package_portable_tarball.sh
```

Salida:

- tarball en `workflow/votante/mezcladora/dist/`
- nombre tipo `verificatum-gui-mezcladora-portable-YYYYMMDD-HHMMSS-<git>.tar.gz`

El paquete incluye:

- `server.py`
- `start_gui.sh`
- `run_mixserver.sh`
- `check_prereqs.sh`
- `static/`
- `README.md`
- `README-PORTABLE.md`

Uso en otra máquina Ubuntu:

```bash
tar -xzf verificatum-gui-mezcladora-portable-*.tar.gz
cd verificatum-gui-mezcladora-portable-*
./check_prereqs.sh
./run_mixserver.sh
```

El lanzador portable usa por defecto `runtime/` dentro del directorio extraído y no limpia sesiones previas salvo que se defina:

```bash
VERIFICATUM_GUI_CLEAN_START=1 ./run_mixserver.sh
```

## Estructura de sesiones y archivos

Las sesiones se crean en:

```text
workflow/votante/mezcladora/runtime/sessions/<session_id>
```

Ejemplo:

```text
workflow/votante/mezcladora/runtime/sessions/servidor-1-20260320-092533
```

Dentro de cada sesión se crean carpetas por `party`:

```text
party01/
party02/
party03/
...
```

Artefactos típicos por `party`:

- `stub.xml`
- `privInfo.xml`
- `protInfo.xml`
- `publicKey`
- `publicKey_ext`
- `ciphertexts`
- `ciphertexts_ext`
- `ciphertextsout`
- `plaintexts_orig`
- `plaintexts`

La prueba de unicidad de mezclas se hace leyendo el directorio `nizkp` de la sesión para detectar `auxsid` ya usados.

## Dependencias

Requiere:

- `python3`
- `vmn`
- `vmni`
- `vmnc`
- `vmnd`
- `vog`

Para pruebas E2E:

- `node`
- `npm`

## API HTTP

El puerto principal también expone endpoints para automatización.

Todos los endpoints API responden con `CORS` abierto (`Access-Control-Allow-Origin: *`) para permitir integración directa desde interfaces de votación web en la red local.

### `GET /api/discovery`

Endpoint de descubrimiento para estaciones de votación. Debe ser el primer endpoint que una interfaz consulte al escanear la red local.

Uso recomendado desde la estación:

- probar primero la mezcladora configurada localmente;
- si no responde o no acepta votos, escanear otros hosts de la red local consultando este endpoint;
- elegir una mezcladora cuyo `accepting_votes` sea `true`;
- luego solicitar `POST /api/handshake` antes de pedir la llave pública o remitir ciphertexts.

Ejemplo:

```bash
curl http://wsantivanez-hm:7040/api/discovery
```

Respuesta típica:

```json
{
  "ok": true,
  "server": {
    "instance_id": "wsantivanez-hm-20260320-131612",
    "hostname": "wsantivanez-hm",
    "public_host": "wsantivanez-hm",
    "base_ui_port": 7040,
    "ui_url": "http://wsantivanez-hm:7040/",
    "api_url": "http://wsantivanez-hm:7040",
    "discovery_url": "http://wsantivanez-hm:7040/api/discovery",
    "handshake_url": "http://wsantivanez-hm:7040/api/handshake",
    "public_key_url": "http://wsantivanez-hm:7040/api/public-key",
    "ciphertexts_url": "http://wsantivanez-hm:7040/api/ciphertexts",
    "has_active_session": true,
    "keygen_ready": true,
    "accepting_votes": true,
    "current_operation": null,
    "handshake_ttl_seconds": 90,
    "registered_station_count": 0
  },
  "session": {
    "session_id": "servidor-1-20260320-092533",
    "session_name": "Servidor 1",
    "election_name": "Elección Verificatum"
  }
}
```

Interpretación:

- `has_active_session`: existe una sesión activa en la mezcladora.
- `keygen_ready`: la llave pública ya está disponible.
- `accepting_votes`: la mezcladora puede recibir votos cifrados ahora mismo.
- `current_operation`: si no es `null`, la mezcladora está ocupada con una fase y la estación debería buscar otra mezcladora o reintentar luego.

### `POST /api/handshake`

Registra o renueva un handshake de una estación de votación contra la mezcladora activa.

Ejemplo:

```bash
curl -X POST http://wsantivanez-hm:7040/api/handshake \
  -H "Content-Type: application/json" \
  -d '{
    "station_id": "mesa-01",
    "session_id": "servidor-1-20260320-092533",
    "session_name": "Servidor 1",
    "auxsid": "default"
  }'
```

Respuesta típica:

```json
{
  "ok": true,
  "accepted": true,
  "reason": "ok",
  "station_id": "mesa_01",
  "requested_auxsid": "default",
  "lease_id": "wsantivanez-hm-20260320-131612:mesa_01",
  "expires_at": "2026-03-20T13:18:04-05:00",
  "session": {
    "session_id": "servidor-1-20260320-092533",
    "session_name": "Servidor 1"
  }
}
```

Reglas:

- si no hay sesión activa, `accepted` será `false`;
- si `keygen` aún no terminó, `accepted` será `false`;
- si la mezcladora está corriendo `precomp`, `shuffle`, `decrypt` o `verify`, `accepted` será `false`;
- si la estación informa una sesión distinta a la activa, `accepted` será `false`;
- si el handshake es aceptado, la estación recibe un `lease_id` reutilizable mientras no expire.

### `GET /api/handshakes`

Devuelve la lista de estaciones con handshake vigente.

```bash
curl http://wsantivanez-hm:7040/api/handshakes
```

### `GET /api/state`

Devuelve el estado global de la GUI y de la sesión activa.

### `GET /api/public-key`

Entrega la llave pública de la sesión activa junto con el identificador y el nombre de la sesión asociada a esa llave.

Llave nativa:

```bash
curl http://wsantivanez-hm:7040/api/public-key
```

Llave en formato Verificatum:

```bash
curl "http://wsantivanez-hm:7040/api/public-key?format=verificatum"
```

Respuesta típica:

```json
{
  "ok": true,
  "session_id": "servidor-1-20260320-092533",
  "session_name": "Servidor 1",
  "session_label": "Servidor 1",
  "election_name": "Elección Verificatum",
  "native": true,
  "filename": "publicKey_ext",
  "path": ".../party01/publicKey_ext",
  "content": "...."
}
```

### `GET /api/emission-context`

Expone explícitamente el nombre de la sesión activa y el `auxsid` operativo que la estación de votación debe usar en ese momento.

Uso típico desde la estación:

- consultar este endpoint antes de cifrar;
- recuperar `session_name`, `session_id` y `auxsid`;
- usar esos mismos valores al llamar a `POST /api/ciphertexts`.

Ejemplo:

```bash
curl http://wsantivanez-hm:7040/api/emission-context
curl "http://wsantivanez-hm:7040/api/emission-context?auxsid=default"
```

Respuesta típica:

```json
{
  "ok": true,
  "session_id": "servidor-1-20260320-092533",
  "session_name": "Servidor 1",
  "session_label": "Servidor 1",
  "election_name": "Elección Verificatum",
  "sid": "ONPE",
  "requested_auxsid": "default",
  "resolved_auxsid": "default2",
  "auxsid": "default2",
  "auxsid_changed": true,
  "accumulated": true,
  "accumulated_from_auxsid": "default",
  "accepting_votes": true
}
```

Interpretación:

- `session_id` y `session_name`: sesión activa que debe usar la estación.
- `requested_auxsid`: `auxsid` solicitado por la estación.
- `resolved_auxsid` y `auxsid`: `auxsid` efectivo que la mezcladora espera en ese momento.
- `auxsid_changed`: indica si la mezcladora ajustó el `auxsid`.
- `accumulated`: indica si ese `auxsid` ya hereda votos cifrados acumulados.
- `accumulated_from_auxsid`: de qué lote previo hereda el acumulado.

### `GET /api/auxsids`

Devuelve `auxsid` usados, reservados, pendientes y el sugerido para la próxima mezcla.

```bash
curl http://wsantivanez-hm:7040/api/auxsids
```

Respuesta típica:

```json
{
  "ok": true,
  "used_auxsids": ["default"],
  "reserved_auxsids": ["default", "default_2"],
  "pending_ciphertexts": ["default_2"],
  "next_shuffle": "default_2",
  "next_decrypt": null,
  "next_verify": null,
  "suggested_auxsid": "default_3"
}
```

### `POST /api/ciphertexts`

Recibe votos cifrados y resuelve la validez de la llamada en el mismo endpoint.

Comportamiento:

- valida primero en `party01`;
- convierte el formato si corresponde;
- solo replica a las demás `parties` si la validación fue correcta;
- si llega otra carga para el mismo `auxsid` y esa mezcla todavía no fue usada, la carga se acumula sobre los `ciphertexts` ya recibidos;
- si el `auxsid` ya fue usado en mezcla, descifrado, verificación o ya existe en `nizkp`, lo cambia automáticamente;
- si el archivo no corresponde al formato declarado, responde error y no deja una carga inválida lista para `shuffle`.
- puede validar opcionalmente que la carga pertenece a la sesión activa usando `session_id` y/o `session_name`.
- puede validar opcionalmente que la estación tiene un `handshake` vigente usando `station_id` y `lease_id`.

#### Enviar formato interno de Verificatum

```bash
curl -X POST http://wsantivanez-hm:7040/api/ciphertexts \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "servidor-1-20260320-092533",
    "session_name": "Servidor 1",
    "station_id": "mesa-01",
    "lease_id": "wsantivanez-hm-20260320-131612:mesa_01",
    "auxsid": "default",
    "format": "raw",
    "ciphertexts": "....contenido del archivo ciphertexts...."
  }'
```

#### Enviar formato nativo

```bash
curl -X POST http://wsantivanez-hm:7040/api/ciphertexts \
  -H "Content-Type: application/json" \
  -d '{
    "session_id": "servidor-1-20260320-092533",
    "session_name": "Servidor 1",
    "station_id": "mesa-01",
    "lease_id": "wsantivanez-hm-20260320-131612:mesa_01",
    "auxsid": "default",
    "format": "native",
    "ciphertexts_ext": "....contenido del archivo ciphertexts_ext....",
    "width": 1
  }'
```

#### Regla de compatibilidad

Si se envía `ciphertexts` sin `format`, el backend lo trata como `native` por seguridad.

#### Respuesta detallada

Respuesta típica:

```json
{
  "ok": true,
  "validated": true,
  "format_received": "native",
  "format_resolved": "native",
  "input_fields": ["ciphertexts_ext"],
  "session_id": "servidor-1-20260320-092533",
  "session_name": "Servidor 1",
  "resolved_auxsid": "default",
  "auxsid_changed": false,
  "accumulated": false,
  "handshake_validated": true,
  "station_id": "mesa_01",
  "lease_id": "wsantivanez-hm-20260320-131612:mesa_01",
  "slots": {
    "auxsid": "default",
    "ciphertexts": "ciphertexts",
    "ciphertexts_ext": "ciphertexts_ext",
    "ciphertextsout": "ciphertextsout",
    "plaintexts_orig": "plaintexts_orig",
    "plaintexts": "plaintexts"
  },
  "party_validated": "party01",
  "width": 1,
  "validated_files": [
    ".../party01/ciphertexts_ext",
    ".../party01/ciphertexts"
  ],
  "replicated_files": {
    "ciphertexts": [
      ".../party01/ciphertexts",
      ".../party02/ciphertexts",
      ".../party03/ciphertexts"
    ],
    "ciphertexts_ext": [
      ".../party01/ciphertexts_ext",
      ".../party02/ciphertexts_ext",
      ".../party03/ciphertexts_ext"
    ]
  },
  "replicated_to": ["party01", "party02", "party03"],
  "written_files": [
    ".../party01/ciphertexts_ext",
    ".../party01/ciphertexts",
    ".../party02/ciphertexts_ext",
    ".../party02/ciphertexts",
    ".../party03/ciphertexts_ext",
    ".../party03/ciphertexts"
  ],
  "session": "servidor-1-20260320-092533"
}
```

Interpretación de campos:

- `validated`: la carga fue aceptada y quedó usable.
- `format_received`: lo que declaró el cliente.
- `format_resolved`: el formato que realmente procesó el backend.
- `input_fields`: campos usados del payload.
- `session_id` y `session_name`: identifican la sesión a la que pertenece la carga.
- `resolved_auxsid`: `auxsid` final asignado a la mezcla.
- `auxsid_changed`: indica si fue renombrado automáticamente.
- `accumulated`: indica si esta carga se anexó a `ciphertexts` ya existentes del mismo `auxsid`.
- `accumulated_from_auxsid`: indica desde qué `auxsid` previo heredó el acumulado; vale `null` si la carga inicia un lote nuevo.
- `handshake_validated`: indica si la carga quedó asociada a un handshake vigente.
- `station_id` y `lease_id`: identidad de estación validada cuando se envían en la carga.
- `party_validated`: `party` en la que se hizo la validación inicial.
- `validated_files`: archivos generados durante la validación.
- `replicated_files`: rutas replicadas por tipo de archivo.
- `replicated_to`: `parties` que recibieron la carga.
- `written_files`: resumen plano de archivos escritos.

### `GET /api/plaintexts`

Entrega los votos mezclados y descifrados de un `auxsid` ya procesado. Se puede consultar desde `7040` o desde cualquier ventana de `party`.

Ejemplos:

```bash
curl "http://wsantivanez-hm:7040/api/plaintexts?auxsid=default"
curl "http://wsantivanez-hm:7042/api/plaintexts?auxsid=default"
curl "http://wsantivanez-hm:7043/api/plaintexts?auxsid=default&format=verificatum"
```

Parámetros:

- `auxsid`: mezcla a consultar. Por defecto `default`.
- `party`: `party` desde la cual se desea leer el archivo. Si se consulta desde `7042`, por defecto usa `party02`.
- `format=native|verificatum`: `native` devuelve `plaintexts`; `verificatum` devuelve `plaintexts_orig`.

### `GET /api/plaintexts/download`

Descarga el archivo de plaintexts como adjunto HTTP.

Ejemplos:

```bash
curl -OJ "http://wsantivanez-hm:7040/api/plaintexts/download?auxsid=default"
curl -OJ "http://wsantivanez-hm:7042/api/plaintexts/download?auxsid=default"
```

En la GUI, cada ventana de `party` expone este mismo flujo con el botón `Descargar votos descifrados` después de que el `AuxSID` indicado ya haya sido descifrado.

## Pruebas E2E

Las pruebas Playwright viven en `GUI/tests/`.

```bash
cd /home/soettamusb/verificatum/GUI
npm install
npm run test:e2e
```

Durante estas pruebas se levanta el backend con `VERIFICATUM_GUI_TESTMODE=1`, por lo que:

- no se ejecuta Verificatum real;
- sí se exponen `7040` y las ventanas `70xx`;
- sí se recorre el flujo completo de la GUI;
- sí se validan los estados secuenciales y la API.
