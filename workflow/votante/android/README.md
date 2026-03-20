# Votante Android

Espacio reservado para la shell Android del frontend del votante.

Objetivo previsto:

- hospedar la UI web del votante en `WebView`
- serializar `BallotBundle`
- reutilizar el core Java del cifrador existente
- obtener la llave publica desde `http://wsantivanez-hm:7040/api/public-key`
- enviar `ciphertexts` o `ciphertexts_ext` a `http://wsantivanez-hm:7040/api/ciphertexts`

Estado actual:

- pendiente de integracion con la app Android existente
- el backend local de `mezcladora` fue retirado del repositorio
- la integracion objetivo ahora es directa contra el servicio externo
