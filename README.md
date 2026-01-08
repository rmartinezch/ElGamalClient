# ElGamalClient - Cifrado de Votos Electrónicos

Este proyecto implementa el cifrado de votos electrónicos utilizando el esquema de cifrado ElGamal sobre curvas elípticas. Es una herramienta crítica diseñada para procesar grandes volúmenes de votos de manera segura y eficiente.

---

## 🚀 Mejoras y Optimizaciones

El proyecto ha sido sometido a un proceso de refactorización intensivo para mejorar drásticamente su rendimiento y usabilidad. A continuación se detalla el estado original y las mejoras implementadas.

### 📉 Estado Original (Antes)
*   **Procesamiento Secuencial:** La codificación y el cifrado de votos se realizaban uno por uno en un bucle simple.
*   **Cuello de Botella en RNG:** El acceso al generador de números aleatorios (`RandomSource`) estaba protegido por un bloque `synchronized`, obligando a los hilos (si hubiera habido) a esperar su turno, impidiendo el paralelismo real.
*   **Rendimiento:** Cifrar 10,000 votos tomaba aproximadamente **1 minuto y 22 segundos**.

### 📈 Mejoras Realizadas (Después)

#### 1. Paralelismo Masivo con Java Streams
Se reemplazaron los bucles secuenciales por **Streams Paralelos** (`Arrays.stream().parallel()`). Esto permite utilizar **todos los núcleos disponibles** del procesador simultáneamente para realizar las costosas operaciones matemáticas de exponenciación modular.

#### 2. Eliminación de Bloqueos (Lock-Free)
Para evitar que los hilos compitieran por el recurso compartido de números aleatorios, se implementó **`ThreadLocal<RandomSource>`**.
*   **Resultado:** Cada hilo tiene su propia instancia del generador de números aleatorios. Ningún hilo espera a otro, logrando una escalabilidad casi lineal.

#### 3. Barra de Progreso (`-p`)
Se añadió una funcionalidad opcional para visualizar el avance del proceso en tiempo real sin impactar el rendimiento.
*   Uso: Agregar el flag `-p` al final del comando.
*   Implementación: Hilo independiente de bajo costo y contadores atómicos `Non-blocking`.

#### 4. Gestión Inteligente de Hardware RNG (`-hw`)
Se implementó una lógica condicional para manejar dispositivos de entropía por hardware (USB TrueRNG):
*   **Modo Software (`-sw`):** Utiliza paralelismo máximo.
*   **Modo Hardware (`-hw`):** Desactiva automáticamente el paralelismo para evitar la saturación y colisiones en el dispositivo físico, garantizando la estabilidad del sistema.

---

## ⚡ Comparativa de Rendimiento (Benchmark)

Prueba realizada con **10,000 votos**:

| Métrica | Versión Original | Versión Optimizada | Mejora |
| :--- | :---: | :---: | :---: |
| **Tiempo de Ejecución** | ~1m 22s | **~10s** | **~8x más rápido** |
| **Uso de CPU** | Un solo núcleo | Multi-núcleo (100%) | Eficiencia total |

*Nota: Escalado probado exitosamente hasta 1,000,000 de votos (aprox. 17 mins).*

---

## 🛠️ Instrucciones de Uso

### Requisitos
*   Java 21 o superior
*   Maven 3.x

### Compilación
```bash
mvn clean package -DskipTests
```

### Ejecución
```bash
java -jar target/ElGamalCipher-1.0-SNAPSHOT-jar-with-dependencies.jar \
     <ruta_publicKey> \
     <ruta_votos_planos> \
     <ruta_salida_cifrados> \
     -sw \
     [-p]
```

**Parámetros:**
1.  `public_Key_file_name`: Ruta al archivo de clave pública.
2.  `plain_votes_file_name`: Archivo de entrada con votos.
3.  `ciphered_votes_file_name`: Archivo de salida.
4.  `-sw` / `-hw`: Usar generador por software (rápido) o hardware (seguro/lento).
5.  `-p` (Opcional): Mostrar barra de progreso.

**Ejemplo:**
```bash
java -jar ElGamalCipher.jar publicKey votos.txt cifrados.txt -sw -p
```

---

## 📜 Historial de Revisiones

### Versión 1.1.0 (Actual)
*   **Fecha:** 2026-01-08
*   **Código Limpio (Clean Code):** Refactorización completa para cumplir con **SonarQube Quality Gate A**.
    *   Reducción de complejidad cognitiva en `ElGamalMain`.
    *   Uso de **Virtual Threads** (Java 21) para tareas secundarias.
    *   Corrección de Code Smells (Manejo de recursos, Logs, Constantes).
*   **Identificación:** El binario ahora muestra su versión al inicio de la ejecución.

### Versión 1.0.0 (Optimizada)
*   **Fecha:** 2026-01-07
*   **Rendimiento:** Implementación de Streams Paralelos y ThreadLocal. Optimización 8x.
*   **Seguridad:** Corrección de concurrencia para Hardware RNG.
*   **Usabilidad:** Barra de progreso visual.
