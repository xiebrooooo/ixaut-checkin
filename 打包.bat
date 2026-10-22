@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 依赖：JDK 17、Android SDK（platform 34 + build-tools 34.0.0）、Gradle 8.9
rem 用法：先设好 JAVA_HOME 与 ANDROID_HOME（或 ANDROID_SDK_ROOT），再双击本脚本

if "%JAVA_HOME%"=="" (
  echo [错误] 未设置 JAVA_HOME（需要 JDK 17）
  pause & exit /b 1
)
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if "%ANDROID_HOME%"=="" (
  echo [错误] 未设置 ANDROID_HOME / ANDROID_SDK_ROOT
  pause & exit /b 1
)
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not exist "%ANDROID_HOME%\platforms\android-34\android.jar" (
  echo [错误] 找不到 Android SDK platform 34：%ANDROID_HOME%
  pause & exit /b 1
)
where gradle >nul 2>nul
if errorlevel 1 (
  echo [错误] PATH 里找不到 gradle（需要 Gradle 8.9）
  pause & exit /b 1
)

if not exist "local.properties" (
  echo sdk.dir=%ANDROID_HOME:\=/%> "local.properties"
)

echo === 开始编译（首次会比较慢，要下依赖）===
call gradle assembleDebug
if errorlevel 1 (
  echo.
  echo [失败] 编译没过，看上面的报错。
  pause & exit /b 1
)

set "APK=app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK%" (
  if not exist "输出" mkdir "输出"
  copy /y "%APK%" "输出\i西理签到.apk" >nul
  echo.
  echo [成功] APK 已输出到：%cd%\输出\i西理签到.apk
) else (
  echo.
  echo [异常] 编译完成但没找到 APK：%APK%
)
pause
