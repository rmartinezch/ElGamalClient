# Mezcladora

El backend local de `workflow/votante/mezcladora` fue retirado.

Desde `2026-03-20` este repositorio ya no expone una `mezcladora`
didactica local. El flujo de prueba consume un servicio externo en:

```text
http://wsantivanez-hm:7040
```

Contrato operativo vigente:

- `GET /api/public-key`
- `GET /api/public-key?format=verificatum`
- `GET /api/auxsids`
- `POST /api/ciphertexts`

Para arranque y prueba manual revise:

- `workflow/README.md`
- `workflow/votante/windows/README.md`
