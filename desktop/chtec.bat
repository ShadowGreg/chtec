@echo off
cd /d "%~dp0"
python chtec.py %*
if errorlevel 1 pause
