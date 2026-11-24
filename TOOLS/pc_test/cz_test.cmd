@echo off
rem CASHZINE / Reward Ad Guard PC test tool. See README.md in this folder.
rem   cz_test.cmd                      run one CASHZINE collection and record it (default)
rem   cz_test.cmd quick                CASHZINE quick items only: coins, Lucky Draw, 10 min reading
rem   cz_test.cmd dry                  dry run: decide only, never tap
rem   cz_test.cmd snap                 switch screens by hand, press Enter to record each
rem   cz_test.cmd pull                 pull the phone's latest run and analyze it
rem   cz_test.cmd install              download the latest guard from GitHub and install it
rem   cz_test.cmd install -Apk x.apk   install a given APK (-Build: build from source)
rem   cz_test.cmd analyze -Dir folder  analyze a test folder again
rem   cz_test.cmd run -Steps coins,reading -ReadMin 5   only some CASHZINE steps
rem   cz_test.cmd run -App bitwalk -TimeoutMin 30   more options, PowerShell style
chcp 65001 >nul
set "MODE=%~1"
if "%MODE%"=="" set "MODE=run"
set "REST="
if not "%~1"=="" shift
:collect
if "%~1"=="" goto go
set REST=%REST% %1
shift
goto collect
:go
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0cz_test.ps1" -Mode %MODE% %REST%
set CODE=%ERRORLEVEL%
echo.
echo Exit code %CODE%  (0=done 2=no phone 3=run install first 4=busy/not started 5=install failed 6=no adb)
echo Results: testruns folder next to this file, open REPORT.txt
pause
exit /b %CODE%
