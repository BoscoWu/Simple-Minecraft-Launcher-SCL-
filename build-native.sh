#!/usr/bin/env bash
# ============================================================
#  Simple MC Launcher - 原生安装包构建
#
#  产物（各平台一个文件，双击安装，用户看不到 app/runtime）：
#    macOS  -> SimpleMCLauncher-<ver>.dmg
#    Linux  -> simplemclauncher_<ver>_amd64.deb
#
#  安装后的位置：
#    macOS  -> /Applications/SimpleMCLauncher.app
#    Linux  -> /opt/simplemclauncher/{bin,lib}  + /usr/bin 启动器
#
#  数据目录（pyLauncher）：放在用户主目录下的
#    macOS  -> ~/Library/Application Support/SimpleMCLauncher/pyLauncher
#    Linux  -> ~/.local/share/SimpleMCLauncher/pyLauncher
#  因为安装到 /opt 和 /Applications 后程序目录不可写。
# ============================================================
set -euo pipefail

APP_NAME="SimpleMCLauncher"
DISPLAY_NAME="Simple MC Launcher"
MAIN_CLASS="com.simplemc.launcher.LauncherApp"
MAIN_JAR="SimpleMCLauncher.jar"

cd "$(dirname "$0")"

APP_VERSION="$(sed -n 's|.*<version>\([0-9][^<]*\)</version>.*|\1|p' pom.xml | head -1)"
[ -n "$APP_VERSION" ] || { echo "ERROR: 无法读取版本号"; exit 1; }
echo "==> Version: $APP_VERSION"

WORK="target/native"
OUT="target/dist"
INPUT="$WORK/input"

rm -rf "$WORK"
mkdir -p "$INPUT" "$OUT"

echo "==> Maven package"
./mvnw -q -B -DskipTests package

[ -f "target/$MAIN_JAR" ] || { echo "ERROR: 缺少 target/$MAIN_JAR"; exit 1; }

# 只拷贝需要的 jar（避免把历史构建产物也塞进 classpath）
cp "target/$MAIN_JAR" "$INPUT/"
for jar in target/dependency/*.jar; do
    [ -f "$jar" ] || continue
    case "$(basename "$jar")" in
        *-mac.jar|*-mac-aarch64.jar|*-linux.jar|*-linux-aarch64.jar)
            cp "$jar" "$INPUT/"
            ;;
    esac
done
echo "    jar: $(ls -1 "$INPUT" | wc -l) 个"

# 运行时模块：缺 java.net.http 会没有网络，缺 jdk.crypto.ec 会 TLS 失败
MODULES="java.base,java.desktop,java.net.http,java.logging,java.management"
MODULES="$MODULES,java.naming,java.prefs,java.xml,jdk.crypto.ec"
MODULES="$MODULES,jdk.crypto.cryptoki,jdk.charsets,jdk.jfr,jdk.unsupported"
ADD_MODULES="$MODULES,javafx.controls,javafx.graphics,javafx.base"

OS="$(uname -s)"
echo "==> OS: $OS"

COMMON_ARGS=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$INPUT"
    --main-jar "$MAIN_JAR"
    --main-class "$MAIN_CLASS"
    --module-path "$INPUT"
    --add-modules "$ADD_MODULES"
    --java-options "-Dfile.encoding=UTF-8"
    --java-options "-Xmx512m"
    --java-options "-XX:+UseSerialGC"
    --vendor "BoscoWu"
    --description "极简 MC 启动器 / Simple MC Launcher"
    --dest "$OUT"
)

case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        echo "==> jpackage exe installer"
        # 打包 Windows 依赖 jar
        for jar in target/dependency/*.jar; do
            [ -f "$jar" ] || continue
            case "$(basename "$jar")" in
                *-win.jar) cp "$jar" "$INPUT/" ;;
            esac
        done
        echo "    jar: $(ls -1 "$INPUT" | wc -l) 个"

        jpackage --type exe "${COMMON_ARGS[@]}" \
            --icon src/main/resources/icon.ico \
            --win-shortcut \
            --win-menu \
            --win-dir-chooser \
            --win-per-user-install \
            --win-menu-group "SimpleMC Launcher"

        EXE=$(ls "$OUT"/*.exe 2>/dev/null | head -1)
        [ -n "$EXE" ] || { echo "ERROR: 未生成 exe"; exit 1; }
        TARGET="$OUT/$APP_NAME-$APP_VERSION-windows.exe"
        [ "$EXE" = "$TARGET" ] || mv "$EXE" "$TARGET"
        echo ""
        echo "==> 产物：$TARGET"
        ;;

    Darwin)
        echo "==> jpackage dmg"
        jpackage --type dmg "${COMMON_ARGS[@]}" \
            --icon src/main/resources/icon.icns \
            --mac-package-name "$DISPLAY_NAME" \
            --mac-package-identifier "com.boscowu.simplemclauncher"

        DMG=$(ls "$OUT"/*.dmg 2>/dev/null | head -1)
        [ -n "$DMG" ] || { echo "ERROR: 未生成 dmg"; exit 1; }
        # 统一命名成带版本号的
        TARGET="$OUT/$APP_NAME-$APP_VERSION-macos.dmg"
        [ "$DMG" = "$TARGET" ] || mv "$DMG" "$TARGET"
        echo ""
        echo "==> 产物：$TARGET"
        ;;

    Linux)
        echo "==> jpackage deb"
        jpackage --type deb "${COMMON_ARGS[@]}" \
            --icon src/main/resources/icon.png \
            --linux-shortcut \
            --linux-menu-group "Game" \
            --linux-app-category "Game" \
            --linux-deb-maintainer "BoscoWu <noreply@github.com>"

        DEB=$(ls "$OUT"/*.deb 2>/dev/null | head -1)
        [ -n "$DEB" ] || { echo "ERROR: 未生成 deb"; exit 1; }
        TARGET="$OUT/$APP_NAME-$APP_VERSION-linux.deb"
        [ "$DEB" = "$TARGET" ] || mv "$DEB" "$TARGET"
        echo ""
        echo "==> 产物：$TARGET"
        ;;

    *)
        echo "ERROR: unsupported OS $OS"; exit 1
        ;;
esac

echo ""
echo "==> 检查产物"
case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        if [ -f "$TARGET" ]; then
            SIZE=$(( $(wc -c < "$TARGET") / 1024 / 1024 ))
            echo "  OK   Windows 安装包 ${SIZE} MB"
        else
            echo "  MISS 安装包"
        fi
        ;;
    Darwin)
        # 验证 dmg 里确实有 .app
        if hdiutil attach "$TARGET" -nobrowse -readonly -mountpoint "$WORK/mnt" >/dev/null 2>&1; then
            APP_BUNDLE=$(find "$WORK/mnt" -maxdepth 1 -name "*.app" | head -1)
            if [ -n "$APP_BUNDLE" ]; then
                echo "  OK   包含 $(basename "$APP_BUNDLE")"
                [ -d "$APP_BUNDLE/Contents/app" ] && echo "  OK   .app 内含 app/" || echo "  MISS .app 内 app/"
                [ -d "$APP_BUNDLE/Contents/runtime" ] && echo "  OK   .app 内含 runtime/" || echo "  MISS .app 内 runtime/"
            else
                echo "  MISS dmg 里没有 .app"
            fi
            hdiutil detach "$WORK/mnt" >/dev/null 2>&1 || true
        fi
        ;;
    Linux)
        if ar t "$TARGET" | grep -q data.tar; then
            echo "  OK   deb 结构正常"
        fi
        dpkg-deb -f "$TARGET" Package Version Architecture 2>/dev/null | while read -r k v; do
            echo "       $k: $v"
        done
        ;;
esac

ls -lh "$OUT" | grep -v "^total"
