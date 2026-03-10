# Scripts por plataforma

- `windows/`: scripts PowerShell/BAT para build y empaquetado Windows.
- `ubuntu/`: scripts Bash para build/ejecución local y empaquetado portable en Ubuntu.
- `android/`: scripts Bash para doctor/build JNI/build APK/emulador/pruebas Android fase 2.

Regla de uso:

- usar siempre los scripts desde la subcarpeta de plataforma
- no hay wrappers duplicados en `scripts/`
