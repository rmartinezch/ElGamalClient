# Ubuntu

## Objetivo

Documentar el flujo nativo de `Ubuntu` del cifrador y separar claramente
scripts/procedimientos respecto a `Windows`.

## RNG en Ubuntu

En Ubuntu el cifrador no usa `SecureRandom`.

- `-sw`: usa `RandomDevice()` (base `/dev/urandom`).
- `-hw`: usa TrueRNG desde:
  - `-Delgamal.rng.device=<ruta>`
  - `ELGAMAL_RNG_DEVICE`
  - valor por defecto `/dev/TrueRNG0`

Si el dispositivo TrueRNG no existe o no es legible, el flujo vuelve a
`RandomDevice()` (`/dev/urandom`).

## Scripts Ubuntu

- `scripts/ubuntu/bootstrap-verificatum.sh`
- `scripts/ubuntu/build-jar.sh`
- `scripts/ubuntu/run-cifrador.sh`
- `scripts/ubuntu/build-cifrador-portable.sh`
- `scripts/ubuntu/package-cifrador-portable.sh`

Uso típico:

```bash
./scripts/ubuntu/build-jar.sh
./scripts/ubuntu/run-cifrador.sh recursos/publicKey recursos/shuffled_votes.txt salida/ciphertexts_ext -sw
```

## Portabilidad Ubuntu

La imagen portable Ubuntu se genera con:

```bash
./scripts/ubuntu/build-cifrador-portable.sh
```

Carpeta resultante:

- `dist/linux/image/Cifrador`

Paquete comprimido:

```bash
./scripts/ubuntu/package-cifrador-portable.sh
```

Salida:

- `dist/linux/Cifrador-1.1.0-linux-x64-portable.tar.gz`

## Separación por plataforma

- `scripts/windows/*`: build/empaquetado/pruebas remotas de Windows.
- `scripts/ubuntu/*`: compilación/ejecución local Ubuntu.
- `scripts/*`: wrappers de compatibilidad para comandos antiguos.
