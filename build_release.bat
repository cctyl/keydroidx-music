@echo off
setlocal

REM ============================================================
REM  Build release APK with lint INCLUDED.
REM
REM  minSdk=19（Android 4.4）：高于 minSdk 的 API 调用只会在旧设备上
REM  「运行时」抛 NoSuchMethodError / NoClassDefFoundError，编译器发现不了，
REM  Lint 的 NewApi 规则是唯一静态防线。
REM  ⚠ 禁止给本命令加 `-x lint`，也禁止在 app/build.gradle 里 disable 'NewApi'。
REM    （assembleRelease 只跑 lintVital 的致命项，拦不住 NewApi error，故这里
REM      显式执行 :app:lintRelease；误报请在调用点逐处 @SuppressLint/@RequiresApi。）
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
