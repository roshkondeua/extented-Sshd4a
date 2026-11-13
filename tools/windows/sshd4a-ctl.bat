@echo off
rem Sshd4a extented - start/stop the SSH server over adb and report which address answers.
rem Usage: sshd4a-ctl.bat start | stop | status [-Device serial] [-Port N] [-Wait sec] [-NoAdb]
setlocal
set "PS1=%~dp0sshd4a-ctl.ps1"
if "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" help
    pause
    exit /b 0
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
exit /b %errorlevel%
