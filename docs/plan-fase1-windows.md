# Plan Fase 1: Ejecucion Nativa en Windows

## Objetivo

Lograr que `cifrador` se ejecute de manera nativa en `Windows x64` como
primera fase, manteniendo compatibilidad con:

- las llaves publicas actuales de Verificatum
- el formato de salida actual del cifrador
- el flujo criptografico actual basado en curvas elipticas

Esta fase no busca independizar completamente al proyecto de
Verificatum. El objetivo es portabilidad operativa en Windows.

## Hallazgos Confirmados

1. El cifrador depende directamente de APIs Java de Verificatum:
   `ECqPGroup`, `PGroupElement`, `PPGroup`, `Marshalizer`,
   `RandomDevice`, entre otras.

2. Para la llave publica EC actual, el cifrador no funciona sin la
   libreria nativa `vecj`.

3. La dependencia de `vecj` no es teorica:
   `com.verificatum.vecj.VEC` ejecuta `System.loadLibrary("vecj-2.2.0")`
   y `ECqPGroup` llama directamente a `VEC.getCurve(...)`.

4. El proyecto actual contiene supuestos Linux:
   - uso de `LD_LIBRARY_PATH` en Maven
   - uso de `/dev/TrueRNG0`
   - uso implicito de `/dev/urandom` desde `RandomDevice`

5. Existen dos defectos que deben corregirse antes del port a Windows:
   - manejo incorrecto de rutas absolutas
   - error actual en la lectura de la llave publica

## Alcance de la Fase 1

Incluye:

- soporte para `Windows x64`
- empaquetado ejecutable para usuario final
- carga correcta de llaves publicas actuales
- generacion de salida compatible con el sistema actual

No incluye:

- Android
- macOS
- independencia total de Verificatum
- reemplazo del motor criptografico

## Estrategia General

La estrategia correcta para esta fase es mantener Verificatum como motor
criptografico, pero hacer que el cifrador:

- deje de asumir Linux
- cargue binarios nativos correctos en Windows
- use una fuente de aleatoriedad portable
- se distribuya como aplicacion autocontenida

## Prioridad Inmediata

Antes de iniciar cualquier desarrollo formal de `cifradorM`, los
defectos encontrados en el cifrador actual se consideran bloqueadores.

`cifradorM` no debe arrancar como fase de implementacion mientras estos
problemas sigan abiertos.

Bloqueadores confirmados:

- [x] corregir el manejo incorrecto de rutas absolutas y relativas
- [x] corregir el error actual de lectura de la llave publica
- [x] dejar una ejecucion funcional completa y reproducible en Linux

Condicion de habilitacion de `cifradorM`:

- [x] todos los bloqueadores anteriores deben estar resueltos y validados

## Plan de Trabajo

### 1. Estabilizar la linea base en Linux

Antes de portar a Windows, el cifrador debe ejecutar correctamente en
el entorno actual.

Esta etapa es prioritaria y bloquea el inicio efectivo de `cifradorM`.

Tareas:

- [x] corregir el manejo de rutas para aceptar rutas relativas y absolutas
- [x] corregir el error de lectura de la llave publica
- [x] validar una corrida completa de cifrado con insumos reales
- [x] dejar pruebas reproducibles de entrada y salida

Resultado esperado:

- [x] el cifrador debe completar una ejecucion valida en Linux sin errores
  funcionales

### 2. Reducir dependencias nativas al minimo

Hay que confirmar y documentar que para este flujo basado en llave EC
solo se necesita `vecj`, y no `vmgj`, salvo evidencia contraria.

Tareas:

- [x] revisar dependencias efectivas en runtime
- [ ] retirar del empaquetado lo que no sea necesario para EC
- [x] documentar el conjunto minimo de binarios requeridos

Resultado esperado:

- [x] inventario exacto de librerias nativas necesarias para Windows

### 3. Obtener y compilar `vec` y `vecj` para Windows

El cuello de botella tecnico principal es JNI.

Tareas:

- [x] obtener el codigo fuente de `verificatum-vecj`
- [x] identificar dependencias nativas requeridas por `vecj`
- [x] preparar toolchain Windows para compilar JNI
- [x] compilar los binarios requeridos para `Windows x64`
- [x] verificar carga con una prueba minima de `System.loadLibrary(...)`

Resultado esperado:

- [x] `vecj-2.2.0.dll` y las DLL auxiliares necesarias para el runtime

### 4. Hacer el cifrador agnostico al sistema operativo

El codigo Java debe dejar de depender de detalles Linux.

Tareas:

- [x] reemplazar la logica actual que depende de `LD_LIBRARY_PATH`
- [x] introducir deteccion de `os.name` y `os.arch`
- [x] definir una estrategia portable de carga de DLL
- [x] eliminar supuestos sobre `/dev/TrueRNG0`
- [x] reemplazar el RNG por defecto por una abstraccion portable
- [x] usar `SecureRandom` o una capa equivalente en Windows

Resultado esperado:

- [ ] el mismo codigo Java debe poder arrancar en Linux y Windows

### 5. Redisenar la capa de carga de bibliotecas nativas

Esta parte debe quedar explicita y controlada por la aplicacion.

Tareas:

- [x] decidir si se usara `java.library.path` o extraccion automatica de DLL
- [x] definir una carpeta local de runtime nativo
- [x] agregar mensajes de error claros cuando falten DLL
- [x] desacoplar esta logica del `pom.xml`

Resultado esperado:

- [ ] el runtime no debe depender de configuracion manual del usuario

### 6. Empaquetar una distribucion nativa para Windows

El usuario final no deberia necesitar Maven ni una instalacion manual de
Verificatum.

Tareas:

- [x] crear una distribucion para `Windows x64`
- [x] incluir JRE embebido mediante `jpackage` o estrategia equivalente
- [x] incluir DLL requeridas en el paquete final
- [x] crear launcher `.exe`
- [ ] validar ejecucion desde consola y desde doble clic

Resultado esperado:

- [x] paquete autocontenido para Windows

### 7. Validacion funcional y de compatibilidad

No basta con ejecutar; la salida debe ser compatible.

Tareas:

- [ ] comparar resultados con Linux sobre el mismo set de entrada
- [ ] validar formato de salida hexadecimal y ByteTree
- [ ] probar llaves simples y vectoriales si aplica
- [x] ejecutar pruebas de volumen
- [ ] medir tiempo de ejecucion y consumo de memoria

Resultado esperado:

- [ ] evidencia de compatibilidad funcional entre Linux y Windows

### 8. Cierre formal de la Fase 1

La fase termina cuando exista una entrega instalable y usable en
Windows.

Condiciones de cierre:

- [ ] el cifrador corre en `Windows x64`
- [ ] carga llaves actuales de Verificatum
- [ ] genera salidas compatibles con el sistema vigente
- [ ] no exige instalacion manual de Verificatum al usuario final
- [ ] cuenta con guia de uso y troubleshooting basico

## Riesgos Principales

1. `verificatum-vecj` no esta dentro de los repositorios ya clonados y
   debe conseguirse o reconstruirse por separado.

2. El mayor riesgo no esta en Java, sino en:
   - JNI
   - GMP y/o dependencias nativas asociadas
   - empaquetado correcto de DLL

   Hallazgo confirmado en Windows x64:
   - `verificatum-vmgj` requiere adaptar casts de punteros a `intptr_t`
     para no truncar direcciones en 64 bits

3. El RNG actual depende de rutas Linux y debe redisenarse si se quiere
   un comportamiento razonable en Windows.

4. Si se busca tambien independencia de Verificatum en esta misma fase,
   el esfuerzo cambia por completo y aumenta de manera importante.

## Estado de Ejecucion

Avance implementado en esta rama:

- [x] el cifrador ya no depende de `RandomDevice()` como RNG por defecto
- [x] existe deteccion de plataforma (`os.name` y `os.arch`)
- [x] existe layout nativo por plataforma: `libs/<os-arch>`
- [x] el JAR puede relanzarse automaticamente con `java.library.path`
- [x] hay pruebas automaticas de regresion para Linux
- [x] hubo corrida manual valida con `publicKey` y `shuffled_votes.txt`

Pendiente para declarar soporte nativo real en Windows:

- [x] compilar u obtener `vecj-2.2.0.dll`
- [x] identificar e incorporar DLL auxiliares requeridas
- [x] validar carga real de JNI en un host `Windows x64`
- [x] generar y probar el paquete final con `jpackage`

Pendiente de cierre funcional:

- [ ] comparar salidas Linux/Windows byte a byte
- [ ] validar el launcher final con doble clic en entorno de usuario

## Entregables

- [x] correcciones funcionales en el cifrador actual
- [x] soporte de carga nativa para Windows
- [x] paquete autocontenido `Windows x64`
- [ ] documento de instalacion y ejecucion
- [ ] matriz de compatibilidad y dependencias
- [ ] evidencia de pruebas cruzadas Linux/Windows

## Recomendacion

La primera fase debe limitarse a `Windows x64` y mantener Verificatum
como backend criptografico.

Ese enfoque:

- reduce el riesgo
- permite entregar valor rapido
- evita reescribir criptografia en esta etapa
- deja abierta una fase posterior de desacoplamiento progresivo
