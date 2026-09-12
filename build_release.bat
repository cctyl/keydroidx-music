@echo off
setlocal

REM ============================================================
REM  Build release APK with lint INCLUDED.
REM
REM  minSdk=19 (Android 4.4): API calls above minSdk only throw
REM  NoSuchMethodError / NoClassDefFoundError at RUNTIME on old
REM  devices; the compiler (using compileSdk) cannot catch them.
REM  Lint's NewApi rule is the only static guard.
REM  WARNING: Do NOT add '-x lint' to this command, and do NOT
REM  disable 'NewApi' in app/build.gradle. assembleRelease only
REM  runs lintVital's fatal items and cannot block NewApi errors,
REM  so we run ':app:lintRelease' explicitly. Suppress false
REM  positives per-call with @SuppressLint / @RequiresApi.
REM
REM  Output: app\build\outputs\apk\release\KeydroidXMusic-v*.apk
REM
REM  Usage: build_release.bat
REM ============================================================

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%app\build\outputs\apk\release"

echo [1/2] Lint (NewApi must pass) + build release APK (may take a while) ...
call "%ROOT%gradlew.bat" :app:lintRelease assembleRelease
if errorlevel 1 (
    echo [ERROR] Lint or build failed. See log above.
    pause
    exit /b 1
)

echo [2/2] Opening output folder:
echo   %OUTDIR%
start "" "%OUTDIR%"

echo Done.
endlocal
pause
