@echo off
setlocal

REM Build Math Parkour. Requires Java 21 and internet access on the first build.
where gradle >nul 2>nul
if not errorlevel 1 goto USE_INSTALLED_GRADLE

goto BOOTSTRAP_GRADLE

:USE_INSTALLED_GRADLE
echo Building with the installed Gradle...
gradle build
exit /b %ERRORLEVEL%

:BOOTSTRAP_GRADLE
set "GRADLE_VERSION=8.12.1"
set "GRADLE_HOME_LOCAL=%LOCALAPPDATA%\MathParkourBuildTools\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%LOCALAPPDATA%\MathParkourBuildTools\gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_HOME_LOCAL%\bin\gradle.bat" goto BUILD_LOCAL
if not exist "%LOCALAPPDATA%\MathParkourBuildTools" mkdir "%LOCALAPPDATA%\MathParkourBuildTools"
echo Downloading Gradle %GRADLE_VERSION% for this project...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_ZIP%'"
if errorlevel 1 goto DOWNLOAD_FAILED
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%GRADLE_ZIP%' -DestinationPath '%LOCALAPPDATA%\MathParkourBuildTools' -Force"
if errorlevel 1 goto UNPACK_FAILED

:BUILD_LOCAL
call "%GRADLE_HOME_LOCAL%\bin\gradle.bat" build
exit /b %ERRORLEVEL%

:DOWNLOAD_FAILED
echo Failed to download Gradle. Install Gradle %GRADLE_VERSION% manually and run gradle build.
exit /b 1

:UNPACK_FAILED
echo Failed to unpack Gradle.
exit /b 1
