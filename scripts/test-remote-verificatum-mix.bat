@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-remote-verificatum-mix.ps1" %*
exit /b %ERRORLEVEL%
