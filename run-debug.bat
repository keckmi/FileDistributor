@echo on
setlocal

set "BASE=%~dp0"
set "JAVA=C:\Program Files\Java\jdk-21\bin\java.exe"
set "JAR=%BASE%file-distributor.jar"
set "CFG=%BASE%config.json"

echo Script dir: %BASE%
echo Using JAR : %JAR%
echo Using CFG : %CFG%
echo Args      : %*

if not exist "%JAR%" (
  echo ERROR: Shaded jar not found: %JAR%
  pause
  exit /b 1
)

if not exist "%CFG%" (
  echo ERROR: config.json not found at: %CFG%
  pause
  exit /b 1
)

rem --- change working dir to this folder, so relative paths resolve here ---
pushd "%BASE%"

"%JAVA%" -jar "%JAR%" %*

echo Exit code: %errorlevel%
popd
pause
endlocal