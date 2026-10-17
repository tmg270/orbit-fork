@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo                     GROKLINK BUILDER
echo ============================================================
echo.
echo This will prepare the Android build tools and compile GrokLink.
echo The first run downloads several hundred MB of official tools.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build_groklink.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if "%EXITCODE%"=="0" (
  echo ============================================================
  echo BUILD COMPLETE
  echo Look for the newly created GrokLink-vX.Y.Z-debug.apk in this folder.
  echo ============================================================
) else (
  echo ============================================================
  echo BUILD FAILED - error code %EXITCODE%
  echo Take a screenshot of the error above and share it when asking for help.
  echo ============================================================
)
echo.
pause
exit /b %EXITCODE%
