@echo off
REM Starts the Asset Tag registration tool. Optional: register_scanner.bat -AdminPin 123456
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0register_scanner.ps1" %*
pause
