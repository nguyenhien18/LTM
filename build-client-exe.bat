@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0packaging\windows\Build-ClientExe.ps1"
if errorlevel 1 (
    echo Dong goi that bai. Xem thong bao loi phia tren.
    pause
    exit /b 1
)
echo File gui cho hoc vien: %~dp0dist\LANExamClient.exe
pause
