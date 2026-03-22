# Votante Android

Implementación aislada de la estación de voto Android. No modifica ni reemplaza la interfaz del cifrador ubicada en `D:\_Proyectos\cifradorM\android`.

## Estado actual

- todo el código de la estación vive bajo `workflow/votante/android`
- la interfaz del cifrador en `android/` fue dejada intacta y separada
- la estación Android hospeda la UI del votante en `WebView`
- descubre mezcladoras en la red local, solicita `handshake`, consulta `GET /api/emission-context`, descarga llave pública y remite `ciphertexts_ext`
- serializa la cédula con el mismo esquema canónico usado en Windows
- cifra localmente reutilizando clases del core Java del cifrador, pero dentro de esta implementación aislada
- muestra constancia de recepción y monitor de eventos

## Ubicacion principal

- actividad Android: `workflow/votante/android/app/src/main/java/pe/gob/onpe/votodigital/votante/android/MainActivity.kt`
- bridge nativo: `workflow/votante/android/app/src/main/java/pe/gob/onpe/votodigital/votante/android/AndroidVoterBridge.kt`
- coordinador de discovery/lease: `workflow/votante/android/app/src/main/java/pe/gob/onpe/votodigital/votante/android/AndroidMixerLeaseCoordinator.kt`
- UI web embebida: `workflow/votante/android/app/src/main/assets/votante/`
- layout host: `workflow/votante/android/app/src/main/res/layout/activity_main.xml`
- manifest: `workflow/votante/android/app/src/main/AndroidManifest.xml`

## Flujo

1. La app abre `file:///android_asset/votante/index.html`.
2. La UI llama al bridge `AndroidBridge`.
3. El bridge descubre mezcladoras activas en la red local.
4. Al confirmar la cédula:
   - consulta `GET /api/emission-context` para obtener el `auxsid` operativo real;
   - descarga la llave pública;
   - genera `plain_votes.txt`;
   - ejecuta el cifrador desacoplado;
   - lee `ciphertexts_ext`;
   - envía el voto cifrado al endpoint remoto.

El `auxsid` visible en la estación queda alineado con el backend:

- la UI muestra el `auxsid` publicado por `GET /api/emission-context`
- después del envío, la estación persiste y refleja el `resolved_auxsid` real devuelto por `POST /api/ciphertexts`
- `GET /api/auxsids` ya no se usa para decidir el lote operativo

## Catalogo

La UI Android usa un catálogo local en:

- `workflow/votante/android/app/src/main/assets/votante/catalogo-opciones.json`

Ese catálogo replica los nombres ficticios de organizaciones políticas y candidaturas usados por la estación Windows.

## Verificacion

En este entorno ya se validó la compilación del módulo aislado y se generó un APK portable en `dist/android/VotanteAndroid-portable.apk`.

## Build portable

El módulo ahora puede compilarse como APK autocontenido con:

```bash
./build-votante-portable-apk.sh
```

Ese script:

- recompila `ElGamalCipher-1.1.0.jar` para Android en Java 17
- copia el `jar` al módulo Android y también lo empaqueta como `asset`
- genera `runtime-config.json` para fijar `serviceBaseUrl` cuando se exporta `VOTANTE_ANDROID_SERVICE_BASE_URL`
- sincroniza `libvecj-2.2.0.so` y `libvmgj-1.3.0.so` para `arm64-v8a` y `x86_64`
- compila el APK del votante con el wrapper Gradle existente en `android/`
- deja el artefacto final en `dist/android/VotanteAndroid-portable.apk`

Para priorizar una mezcladora conocida sin desactivar el barrido de red local:

```bash
VOTANTE_ANDROID_SERVICE_BASE_URL=http://192.168.0.120:7040 ./build-votante-portable-apk.sh
```

La semilla `serviceBaseUrl` es opcional. Si esa URL no responde o no coincide con la sesión activa,
la estación sigue intentando descubrir mezcladoras candidatas en la red local.

## Waydroid + Weston

Para instalar y abrir la estación del votante en Waydroid sobre Weston:

```bash
./run-votante-waydroid-weston.sh
```

Ese script:

- reutiliza `dist/android/VotanteAndroid-portable.apk`
- por defecto limpia instancias previas de Waydroid y Weston antes de arrancar
- instala o reinstala la app en Waydroid
- abre `waydroid show-full-ui`
- lanza `pe.gob.onpe.votodigital.votante.android`

Variables útiles:

- `REINSTALL_APP=0` evita reinstalar si la app ya está visible en Waydroid
- `CLEAN_START=0` reutiliza una sesión sana ya levantada
- `SHOW_UI=0` no abre la ventana de `waydroid show-full-ui`
- `REBUILD_APK=1 VOTANTE_ANDROID_SERVICE_BASE_URL=http://192.168.0.120:7040` recompila e instala usando una semilla explícita de mezcladora
