# Vote Schema

## Estado

Propuesta inicial para entorno de prueba didactico.

## Objetivo

Definir la estructura canonica del voto que deben compartir:

- la interfaz del votante en `Windows`
- la interfaz del votante en `Android`
- el servicio externo de recepcion
- el archivo plano previo al cifrado usado por el `.jar`

## Unidades de informacion

### 1. `CodedVote`

Unidad minima serializable. Representa una eleccion codificada.

Cada `CodedVote` se serializa como una sola linea de `10` caracteres
numericos usando cinco campos de `2` digitos:

```text
EN ED PP PV1 PV2
```

Significado:

- `EN`: election number
- `ED`: electoral district
- `PP`: political party
- `PV1`: preferential vote 1
- `PV2`: preferential vote 2

### 2. `BallotBundle`

Conjunto ordenado de `CodedVote` que representa la emision completa de
un elector para la jornada activa.

En el flujo de prueba actual, el `BallotBundle` esperado contiene `5`
lineas, una por cada eleccion:

1. presidencial
2. senadores nacionales
3. senadores regionales
4. diputados regionales
5. Parlamento Andino

## Layout fijo

```text
Pos 01-02 = EN
Pos 03-04 = ED
Pos 05-06 = PP
Pos 07-08 = PV1
Pos 09-10 = PV2
```

Reglas globales:

1. cada linea tiene exactamente `10` caracteres
2. solo se aceptan digitos `0-9`
3. no se permiten separadores
4. un archivo temporal del elector contiene una linea por `CodedVote`
5. la serializacion debe ser determinista

## Codigo de relleno

Mientras no se cierre un estandar distinto, el valor canonico para
campo no aplicable es:

```text
00
```

Ejemplos:

- campo `ED` en elecciones nacionales
- `PV2` en senadores regionales
- `PV1` y `PV2` en presidencial

## Elecciones y aplicabilidad

### `EN=01` Presidencial

Patron:

```text
01 00 PP 00 00
```

Campos:

- `PP` obligatorio
- `ED`, `PV1`, `PV2` no aplican

### `EN=02` Senadores nacionales

Patron:

```text
02 00 PP PV1 PV2
```

Campos:

- `PP` obligatorio
- `PV1` aplicable
- `PV2` aplicable
- `ED` no aplica

### `EN=03` Senadores regionales

Patron:

```text
03 ED PP PV1 00
```

Campos:

- `ED` obligatorio
- `PP` obligatorio
- `PV1` aplicable
- `PV2` no aplica

### `EN=04` Diputados regionales

Patron:

```text
04 ED PP PV1 PV2
```

Campos:

- `ED` obligatorio
- `PP` obligatorio
- `PV1` aplicable
- `PV2` aplicable

### `EN=05` Parlamento Andino

Patron:

```text
05 00 PP PV1 PV2
```

Campos:

- `PP` obligatorio
- `PV1` aplicable
- `PV2` aplicable
- `ED` no aplica

## Reglas de captura en la UI

1. la UI no debe pedir al elector el codigo serializado
2. la UI debe capturar decisiones por eleccion
3. `ED` debe venir del padron, sesion o contexto del elector
4. las elecciones regionales deben filtrarse por `ED`
5. `PP` debe pertenecer al catalogo vigente
6. `PV1` y `PV2` deben pertenecer a la lista correspondiente al `PP`
7. `PV1` y `PV2` no deben repetirse si ambos estan presentes

## Reglas de validacion del `BallotBundle`

1. debe existir exactamente un `CodedVote` por eleccion habilitada
2. no puede haber dos lineas con el mismo `EN`
3. el orden canonico del archivo es `01, 02, 03, 04, 05`
4. todas las lineas deben cumplir longitud fija
5. todas las lineas deben cumplir aplicabilidad por eleccion

## Ejemplos validos

```text
0100030000
0200040102
0302040100
0402030104
0500050304
```

Interpretacion:

- presidencial, partido `03`
- senadores nacionales, partido `04`, preferenciales `01` y `02`
- senadores regionales, distrito `02`, partido `04`, preferencial `01`
- diputados regionales, distrito `02`, partido `03`, preferenciales
  `01` y `04`
- Parlamento Andino, partido `05`, preferenciales `03` y `04`

## Ejemplos invalidos

```text
0102030000
```

Invalido porque presidencial no debe usar `ED`.

```text
0302040104
```

Invalido porque senadores regionales no usa `PV2`.

```text
0402030202
```

Invalido si `PV1` y `PV2` no pueden repetir candidato.

## Observaciones

1. Esta especificacion esta orientada a prueba y demostracion.
2. Los catalogos de partidos, distritos y candidaturas reales deben
   venir de insumos oficiales.
3. Antes de pasar a produccion debe validarse contra corpus real y
   reglas definitivas de ONPE.
