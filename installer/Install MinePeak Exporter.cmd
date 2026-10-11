@echo off
rem MinePeak Exporter: copies your PEAK (your Steam PEAK isn't changed), installs BepInEx and the exporter in the copy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause
