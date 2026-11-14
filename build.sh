#!/usr/bin/env bash
# ============================================================
#  Simple MC Launcher - cross-platform jpackage build
#  Produces (per OS):
#    Windows -> target/dist/SimpleMCLauncher-<ver>-windows.zip
#               (or a real .exe installer when WiX is available)
#    macOS   -> target/dist/SimpleMCLauncher-<ver>.dmg
#    Linux   -> target/dist/simplemc-launcher_<ver>_amd64.deb
# ============================================================
set -euo pipefail

APP_NAME="SimpleMCLauncher"
APP_VERSION="5.1.0"
MAIN_CLASS="com.simplemc.launcher.LauncherApp"
MAIN_JAR="SimpleMCLauncher.jar"
DIST_DIR="target/dist"

cd "$(dirname "$0")"

echo "==> Maven package"
./mvnw -q -B -DskipTests package

[ -d target/dependency ] || { echo "ERROR: target/dependency missing"; exit 1; }
[ -f "target/$MAIN_JAR" ] || { echo "ERROR: target/$MAIN_JAR missing"; exit 1; }

mkdir -p "$DIST_DIR"

MODULE_PATH="target/dependency"
ADD_MODULES="javafx.controls,javafx.graphics,javafx.base"

OS="$(uname -s)"
echo "==> OS: $OS"

build_app_image() {
    echo "==> jpackage app-image"
    jpackage \
        --type app-image \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --input target \
        --main-jar "$MAIN_JAR" \
        --main-class "$MAIN_CLASS" \
        --module-path "$MODULE_PATH" \
        --add-modules "$ADD_MODULES" \
        --java-options "-Dfile.encoding=UTF-8" \
        --icon "$1" \
        --dest "$DIST_DIR"
}

case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        if command -v candle >/dev/null 2>&1; then
            echo "==> WiX detected, building .exe installer"
            jpackage \
                --type exe \
                --name "$APP_NAME" \
                --app-version "$APP_VERSION" \
                --input target \
                --main-jar "$MAIN_JAR" \
                --main-class "$MAIN_CLASS" \
                --module-path "$MODULE_PATH" \
                --add-modules "$ADD_MODULES" \
                --java-options "-Dfile.encoding=UTF-8" \
                --icon src/main/resources/icon.ico \
                --win-shortcut --win-menu --win-dir-chooser \
                --vendor "SimpleMC" \
                --description "极简 MC 启动器" \
                --dest "$DIST_DIR"
        else
            echo "==> WiX not available, building portable app-image + zip"
            build_app_image src/main/resources/icon.ico
            ( cd "$DIST_DIR" && \
              powershell -NoProfile -Command \
                "Compress-Archive -Path '$APP_NAME' -DestinationPath '$APP_NAME-$APP_VERSION-windows.zip' -Force" )
        fi
        ;;

    Darwin)
        echo "==> jpackage dmg"
        jpackage \
            --type dmg \
            --name "$APP_NAME" \
            --app-version "$APP_VERSION" \
            --input target \
            --main-jar "$MAIN_JAR" \
            --main-class "$MAIN_CLASS" \
            --module-path "$MODULE_PATH" \
            --add-modules "$ADD_MODULES" \
            --java-options "-Dfile.encoding=UTF-8" \
            --icon src/main/resources/icon.icns \
            --mac-package-name "$APP_NAME" \
            --dest "$DIST_DIR"
        ;;

    Linux)
        echo "==> jpackage deb"
        jpackage \
            --type deb \
            --name "$APP_NAME" \
            --app-version "$APP_VERSION" \
            --input target \
            --main-jar "$MAIN_JAR" \
            --main-class "$MAIN_CLASS" \
            --module-path "$MODULE_PATH" \
            --add-modules "$ADD_MODULES" \
            --java-options "-Dfile.encoding=UTF-8" \
            --icon src/main/resources/icon.png \
            --linux-shortcut \
            --linux-menu-group "Game" \
            --vendor "SimpleMC" \
            --description "Simple MC Launcher" \
            --dest "$DIST_DIR"
        ;;

    *)
        echo "ERROR: unsupported OS $OS"; exit 1
        ;;
esac

echo "==> Done. Output in $DIST_DIR:"
ls -la "$DIST_DIR"
