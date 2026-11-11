@echo off
chcp 65001 >nul
title VozIA Heuristica - Texto a Voz Humanizada (PC)
echo ===============================================================
echo   🎙️  VozIA Heuristica - Texto a Voz Humanizada (MP3)
echo ===============================================================
echo.

if not exist ".venv\Scripts\python.exe" (
    echo [1/2] Creando entorno virtual e instalando dependencias por primera vez...
    python -m venv .venv
    ".\.venv\Scripts\pip.exe" install -r requirements.txt
    echo.
)

echo Iniciando motor en segundo plano para Computadora...
echo.

set "EDGE_PATH=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE_PATH%" set "EDGE_PATH=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
set "CHROME_PATH=%ProgramFiles%\Google\Chrome\Application\chrome.exe"

if exist "%EDGE_PATH%" (
    start "" "%EDGE_PATH%" --app="http://localhost:8000"
) else if exist "%CHROME_PATH%" (
    start "" "%CHROME_PATH%" --app="http://localhost:8000"
) else (
    start "" "http://localhost:8000"
)

".\.venv\Scripts\python.exe" -m uvicorn app:app --host 0.0.0.0 --port 8000
pause

