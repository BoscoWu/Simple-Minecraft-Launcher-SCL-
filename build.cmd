@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM  极简 MC 启动器 - 构建脚本
REM  用法: build.cmd [app-image|exe]
REM    app-image  生成 portable 目录（推荐，启动快）
REM    exe        生成 Windows 安装包（需要 WiX）
REM ============================================================

set TARGET=%1
if "%TARGET%"=="" set TARGET=app-image

set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1
set PATH=%JAVA_HOME%\bin;%PATH%
set MAVEN_OPTS=-Dfile.encoding=UTF-8

echo.
echo [1/3] 编译并打包 fat jar ...
call mvnw.cmd -q -B clean package -DskipTests
if errorlevel 1 goto :fail

echo.
echo [2/3] 收集依赖 ...
call mvnw.cmd -q -B dependency:copy-dependencies -DoutputDirectory=target/libs
if errorlevel 1 goto :fail

echo.
echo [3/3] jpackage 打包 (%TARGET%) ...

set JFX_MODS=javafx.controls,javafx.graphics,javafx.base

if "%TARGET%"=="app-image" (
    rmdir /s /q target\output 2>nul
    jpackage ^
      --type app-image ^
      --name SimpleMCLauncher ^
      --app-version 5.1.0 ^
      --vendor "BoscoWu" ^
      --description "极简 MC 启动器" ^
      --input target\libs ^
      --main-jar SimpleMCLauncher.jar ^
      --main-class com.simplemc.launcher.LauncherApp ^
      --icon src\main\resources\icon.ico ^
      --dest target\output ^
      --java-options "-Dfile.encoding=UTF-8" ^
      --java-options "--module-path $APPDIR" ^
      --java-options "--add-modules %JFX_MODS%"
) else (
    rmdir /s /q target\output 2>nul
    jpackage ^
      --type exe ^
      --name SimpleMCLauncher ^
      --app-version 5.1.0 ^
      --vendor "BoscoWu" ^
      --description "极简 MC 启动器" ^
      --input target\libs ^
      --main-jar SimpleMCLauncher.jar ^
      --main-class com.simplemc.launcher.LauncherApp ^
      --icon src\main\resources\icon.ico ^
      --dest target\output ^
      --win-dir-chooser ^
      --win-shortcut ^
      --win-menu ^
      --java-options "-Dfile.encoding=UTF-8" ^
      --java-options "--module-path $APPDIR" ^
      --java-options "--add-modules %JFX_MODS%"
)

if errorlevel 1 goto :fail

echo.
echo ============================================================
echo  打包完成: target\output
echo ============================================================
dir /b target\output
goto :end

:fail
echo.
echo [错误] 构建失败
exit /b 1

:end
endlocal
