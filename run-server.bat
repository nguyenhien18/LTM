@echo off
setlocal
cd /d "%~dp0"
set "LAN_MAVEN=mvn.cmd"
where mvn.cmd >nul 2>&1
if errorlevel 1 (
    set "LAN_MAVEN="
    for /r "%USERPROFILE%\.m2\wrapper\dists" %%M in (mvn.cmd) do if not defined LAN_MAVEN if exist "%%M" set "LAN_MAVEN=%%M"
)
if not defined LAN_MAVEN (
    echo Khong tim thay Maven. Hay cai Maven 3.9+ va them vao PATH.
    pause
    exit /b 1
)
if /I "%~1"=="--check" (
    call "%LAN_MAVEN%" -version
    exit /b
)
rem Keep the running Server's classes separate from Client builds and tests.
set "LAN_SERVER_BUILD=output/server-runtime/%RANDOM%-%RANDOM%-%RANDOM%"
call "%LAN_MAVEN%" "-Dlanexam.build.directory=%LAN_SERVER_BUILD%" compile javafx:run -Pserver
pause
