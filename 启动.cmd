@echo off
chcp 65001 >nul
cd /d "%~dp0"

REM ============================================================
REM  极简 MC 启动器 - 启动器（双击即可运行）
REM  说明：本脚本启动后会立即退出，启动器独立运行，不会被关闭。
REM ============================================================

powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0launch.ps1"
exit /b 0
