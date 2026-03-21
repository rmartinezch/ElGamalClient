# Votante Android

Implementación aislada de la estación de voto Android. No modifica ni reemplaza la interfaz del cifrador ubicada en `D:\_Proyectos\cifradorM\android`.

## Estado actual

- todo el código de la estación vive bajo `workflow/votante/android`
- la interfaz del cifrador en `android/` fue dejada intacta y separada
- la estación Android hospeda la UI del votante en `WebView`
- descubre mezcladoras en la red local, solicita `handshake`, descarga llave pública y remite `ciphertexts_ext`
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
   - descarga la llave pública;
   - genera `plain_votes.txt`;
   - ejecuta el cifrador desacoplado;
   - lee `ciphertexts_ext`;
   - envía el voto cifrado al endpoint remoto.

## Catalogo

La UI Android usa un catálogo local en:

- `workflow/votante/android/app/src/main/assets/votante/catalogo-opciones.json`

Ese catálogo replica los nombres ficticios de organizaciones políticas y candidaturas usados por la estación Windows.

## Verificacion

La integración quedó implementada, pero en este entorno no se pudo ejecutar `assembleDebug` porque falta configurar el Android SDK local (`local.properties` o `ANDROID_HOME`).
