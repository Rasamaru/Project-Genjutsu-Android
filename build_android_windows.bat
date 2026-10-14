@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "GRADLE_VERSION=8.9"
set "GRADLE_ZIP=%CD%\.build-tools\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_EXE=%CD%\.build-tools\gradle-%GRADLE_VERSION%\bin\gradle.bat"

if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk\platforms\android-35" (
    set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
  )
)
if not exist "%ANDROID_HOME%\platforms\android-35" (
  echo [ERROR] Android SDK Platform 35 is missing.
  echo Open Android Studio - SDK Manager and install Android API 35.
  echo Set ANDROID_HOME to your Android SDK folder if necessary.
  pause
  exit /b 1
)
if not defined JAVA_HOME (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
  )
)
where java >nul 2>&1
if errorlevel 1 if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] Install Android Studio with JDK 17+ or set JAVA_HOME.
  pause
  exit /b 1
)
if not exist "%GRADLE_EXE%" (
  echo Downloading official Gradle %GRADLE_VERSION% distribution. Internet required.
  if not exist ".build-tools" mkdir ".build-tools"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://services.gradle.org/distributions/gradle-8.9-bin.zip'; Invoke-WebRequest -Uri $url -OutFile '%GRADLE_ZIP%'; Expand-Archive -LiteralPath '%GRADLE_ZIP%' -DestinationPath '.build-tools' -Force"
  if errorlevel 1 (
    echo [ERROR] Gradle download failed.
    pause
    exit /b 1
  )
)
call "%GRADLE_EXE%" --no-daemon :app:assembleDebug
if errorlevel 1 (
  echo [ERROR] Android build failed. See output above.
  pause
  exit /b 1
)
copy /y "app\build\outputs\apk\debug\app-debug.apk" "ProjectGenjutsu-Android-debug.apk" >nul
if errorlevel 1 (
  echo [ERROR] Build succeeded but APK file was not found.
  pause
  exit /b 1
)
echo.
echo [OK] ProjectGenjutsu-Android-debug.apk
pause
