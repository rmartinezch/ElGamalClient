@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "PS1=%SCRIPT_DIR%configure_windows_portproxy.ps1"
set "PS_ARGS=-NoProfile -ExecutionPolicy Bypass -NoExit -File ""%PS1%"""

if not exist "%PS1%" (
  echo No se encontro el script:
  echo %PS1%
  pause
  exit /b 1
)

net session >nul 2>&1
if %errorlevel%==0 (
  powershell.exe %PS_ARGS%
  set "RC=%errorlevel%"
  if not "%RC%"=="0" (
    echo.
    echo El script termino con error %RC%.
    pause
  )
  exit /b %RC%
)

echo Solicitando elevacion de administrador...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-NoExit','-File','%PS1%' -Wait"

set "RC=%errorlevel%"
if not "%RC%"=="0" (
  echo.
  echo No se pudo relanzar PowerShell con privilegios elevados. Codigo %RC%.
  pause
)
exit /b %RC%
