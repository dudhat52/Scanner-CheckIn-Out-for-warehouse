@echo off
REM ==========================================================================
REM  Sends the SAME broadcast ScanSettings sends, without printing a QR code.
REM  Usage: simulate_scan.bat IDLOGISTICS_INBOUND
REM  (Implicit broadcast -> exercises the real always-on listener path.)
REM ==========================================================================
if "%~1"=="" (echo Usage: simulate_scan.bat VALUE & exit /b 1)
adb shell am broadcast -a device.scanner.EVENT -c android.intent.category.DEFAULT --ez EXTRA_EVENT_DECODE_RESULT true --es EXTRA_EVENT_DECODE_STRING_VALUE %1 --es EXTRA_EVENT_SYMBOL_NAME QR
