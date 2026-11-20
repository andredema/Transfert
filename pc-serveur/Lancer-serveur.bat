@echo off
chcp 65001 >nul
cd /d "%~dp0"
python serveur.py %*
if errorlevel 1 py serveur.py %*
pause
