@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-win.ps1" %*
