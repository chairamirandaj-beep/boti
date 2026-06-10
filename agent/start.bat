@echo off
echo Cerrando agente anterior si existe...
taskkill /F /IM python.exe /FI "WINDOWTITLE eq agent*" >nul 2>&1
taskkill /F /FI "COMMANDLINE eq *main.py*" >nul 2>&1

echo Limpiando cache...
rmdir /S /Q __pycache__ >nul 2>&1

echo Iniciando agente boti...
cd /D "%~dp0"
python -u main.py
pause
