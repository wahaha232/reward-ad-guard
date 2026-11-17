@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0screen_off.ps1" off %*
timeout /t 3 >nul
