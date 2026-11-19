@echo off
REM Shows what the app knows about this scanner (serial, Asset Tag, mode, permissions, queue).
adb shell am broadcast -a com.idlogistics.scannercheckout.PROVISION -n com.idlogistics.scannercheckout/.ProvisionReceiver --es cmd status
adb shell dumpsys activity services com.idlogistics.scannercheckout | findstr /i "ScanListenerService isForeground"
pause
