@echo off
REM Add GUID_DEVINTERFACE_ADB {F72FE0D4-CBCB-407d-8814-9ED673D0DD6B} to the fastboot device's interface GUIDs
reg add "HKLM\SYSTEM\CurrentControlSet\Enum\USB\VID_18D1&PID_D00D\00019157L001724\Device Parameters" /v DeviceInterfaceGUIDs /t REG_MULTI_SZ /d "{F72FE0D4-CBCB-407d-8814-9ED673D0DD6B}\0{17488D48-7413-4D46-92C2-9F31B26DD9D8}" /f
if %errorlevel% neq 0 echo REG_ADD_FAILED
REM Re-enumerate the device so the new GUID takes effect
pnputil /restart-device "USB\VID_18D1&PID_D00D\00019157L001724"
if %errorlevel% neq 0 echo RESTART_FAILED
echo ALL_DONE
timeout /t 3 >nul
