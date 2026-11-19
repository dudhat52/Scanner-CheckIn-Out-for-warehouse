@echo off
REM ==========================================================================
REM  Scanner Check-In - one-time setup of ONE PM86 (does not touch provisioning)
REM  Usage:  setup_device.bat  path\to\app.apk
REM  Requires adb (in this folder or on PATH) and ONE connected scanner.
REM ==========================================================================
setlocal
set PKG=com.idlogistics.scannercheckout
set APK=%~1
if "%APK%"=="" set APK=app-debug.apk

echo.
echo === Connected device ===
adb devices
adb get-state >nul 2>&1 || (echo No authorized device. & pause & exit /b 1)
for /f "delims=" %%s in ('adb shell getprop ro.serialno') do set SERIAL=%%s
for /f "delims=" %%m in ('adb shell getprop ro.product.model') do set MODEL=%%m
echo Model:  %MODEL%
echo Serial: %SERIAL%
if /i not "%MODEL%"=="PM86" echo WARNING: this is not a PM86.

echo.
echo === 1. Install / update app ===
if not exist "%APK%" (echo APK not found: %APK% & pause & exit /b 1)
adb install -r "%APK%" || (echo Install failed. & pause & exit /b 1)

echo.
echo === 2. Allow the app to pop up over other apps (Android 11 background launch) ===
adb shell appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo.
echo === 3. Exempt from battery optimisation (keeps the scan listener alive) ===
adb shell dumpsys deviceidle whitelist +%PKG%

echo.
echo === 4. Start the app once (starts the always-on scan listener) ===
adb shell am start -n %PKG%/.MainActivity
timeout /t 3 >nul

echo.
echo === 5. Status reported by the app ===
adb shell am broadcast -a %PKG%.PROVISION -n %PKG%/.ProvisionReceiver --es cmd status

echo.
echo ---------------------------------------------------------------------------
echo  MANUAL STEPS ON THE SCANNER (once):
echo   - ScanSettings ^> Basic ^> Result type = Custom intent (leave intent fields default)
echo   - Volume: raise MEDIA volume so the scan beep is audible
echo   - Optional: Settings ^> System ^> Date ^& time ^> Time zone = Toronto
echo  NEXT: run register_scanner.bat to assign the Asset Tag.
echo ---------------------------------------------------------------------------
pause
