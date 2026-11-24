@echo off
setlocal enabledelayedexpansion
set "PROJ=%~dp0"
cd /d "%PROJ%"

set "SDK=C:\Users\Administrator\AppData\Local\Android\Sdk"
set "BT=%SDK%\build-tools\36.0.0"
set "PLATFORM=%SDK%\platforms\android-28\android.jar"
set "JDK=C:\Program Files\OpenJDK\jdk-21.0.2"
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

if not exist "%PLATFORM%" (
  echo [ERROR] missing %PLATFORM%
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\gen
mkdir build\classes
mkdir build\dex
if not exist dist mkdir dist

echo === aapt2 compile ===
"%BT%\aapt2.exe" compile --dir app\src\main\res -o build\res.zip || exit /b 1

echo === aapt2 link ===
"%BT%\aapt2.exe" link -o build\base.apk -I "%PLATFORM%" --manifest app\src\main\AndroidManifest.xml -R build\res.zip --auto-add-overlay --java build\gen --min-sdk-version 26 --target-sdk-version 28 --version-code 2 --version-name 1.1 || exit /b 1

echo === javac ===
python tools\make_filelist.py build\sources.txt .java app\src\main\java build\gen || exit /b 1
"%JDK%\bin\javac.exe" --release 8 -encoding UTF-8 -classpath "%PLATFORM%" -d build\classes @build\sources.txt || exit /b 1

echo === d8 ===
python tools\make_filelist.py build\classes.txt .class build\classes || exit /b 1
call "%BT%\d8.bat" --release --min-api 26 --lib "%PLATFORM%" --output build\dex @build\classes.txt || exit /b 1

echo === package ===
python "%~dp0tools\add_dex.py" build\base.apk build\dex\classes.dex build\unsigned.apk || exit /b 1
"%BT%\zipalign.exe" -f -p 4 build\unsigned.apk build\aligned.apk || exit /b 1

if not exist keystore mkdir keystore
if not exist keystore\hongguo.jks (
  "%JDK%\bin\keytool.exe" -genkeypair -keystore keystore\hongguo.jks -alias hongguo -keyalg RSA -keysize 2048 -validity 10000 -storepass hongguo123 -keypass hongguo123 -dname "CN=Hongguo Adblock, O=Local, C=CN" || exit /b 1
)

echo === sign ===
call "%BT%\apksigner.bat" sign --ks keystore\hongguo.jks --ks-pass pass:hongguo123 --key-pass pass:hongguo123 --ks-key-alias hongguo --out dist\hongguo_adblock.apk build\aligned.apk || exit /b 1
call "%BT%\apksigner.bat" verify --print-certs dist\hongguo_adblock.apk || exit /b 1

echo.
echo BUILD OK: %PROJ%dist\hongguo_adblock.apk
endlocal
