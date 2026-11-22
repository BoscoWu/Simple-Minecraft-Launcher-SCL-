#!/usr/bin/env bash
# ============================================================
#  Simple MC Launcher - 便携版构建
#
#  产物（全部解压即用，无需安装）：
#    Windows -> SimpleMCLauncher-<ver>-windows.zip
#    macOS   -> SimpleMCLauncher-<ver>-macos.zip
#    Linux   -> SimpleMCLauncher-<ver>-linux.tar.gz
#
#  解压后的目录结构：
#    SimpleMCLauncher/
#    ├── SimpleMCLauncher(.exe)   <- 双击运行
#    ├── app/                     程序代码
#    ├── runtime/                 内置 Java（无需另装）
#    └── pyLauncher/              数据目录（配置、游戏、日志）
# ============================================================
set -euo pipefail

APP_NAME="SimpleMCLauncher"
MAIN_CLASS="com.simplemc.launcher.LauncherApp"
MAIN_JAR="SimpleMCLauncher.jar"
DIST_DIR="target/dist"
INPUT_DIR="target/appinput"

cd "$(dirname "$0")"

# 版本号从 pom.xml 读取，避免和 Constants.java 不同步
APP_VERSION="$(sed -n 's|.*<version>\([0-9][^<]*\)</version>.*|\1|p' pom.xml | head -1)"
if [ -z "$APP_VERSION" ]; then
    echo "ERROR: 无法从 pom.xml 读取版本号"
    exit 1
fi
echo "==> Version: $APP_VERSION"

echo "==> Maven package"
./mvnw -q -B -DskipTests package

[ -f "target/$MAIN_JAR" ] || { echo "ERROR: target/$MAIN_JAR 不存在"; exit 1; }
[ -d "target/dependency" ] || { echo "ERROR: target/dependency 不存在"; exit 1; }

rm -rf "$DIST_DIR" "$INPUT_DIR"
mkdir -p "$DIST_DIR" "$INPUT_DIR"

# ---------------------------------------------------------------
# 关键：jpackage 的 --input 会把目录下所有内容塞进 app.classpath。
# 如果直接给 target/，历史构建产物（jptest、stage、dist 等）也会被
# 收进去，路径会膨胀到 JVM 无法初始化。
# 所以这里准备一个只含必要 jar 的干净目录。
# ---------------------------------------------------------------
echo "==> 准备干净的应用输入目录"
cp "target/$MAIN_JAR" "$INPUT_DIR/"
for jar in target/dependency/*.jar; do
    [ -f "$jar" ] || continue
    case "$(basename "$jar")" in
        *-win.jar|*-mac.jar|*-linux.jar|*-mac-aarch64.jar|*-linux-aarch64.jar)
            cp "$jar" "$INPUT_DIR/"
            ;;
    esac
done
echo "    jar 数量: $(ls -1 "$INPUT_DIR" | wc -l)"

MODULE_PATH="$INPUT_DIR"

# ---------------------------------------------------------------
# 运行时模块列表。
#
# 不能只写 javafx.*：jpackage 默认只带 java.base，会缺
#   - java.net.http   HttpClient（所有网络请求）
#   - java.desktop    ImageIO / Desktop.open
#   - jdk.crypto.ec   TLS 椭圆曲线（缺这个会 SSLHandshakeException）
# 用 jdeps 分析后的结果再补上日志/管理/字符集等辅助模块。
# ---------------------------------------------------------------
ADD_MODULES="java.base,java.desktop,java.net.http,java.logging"
ADD_MODULES="$ADD_MODULES,java.management,java.naming,java.prefs,java.xml"
ADD_MODULES="$ADD_MODULES,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.charsets"
ADD_MODULES="$ADD_MODULES,jdk.jfr,jdk.unsupported"
ADD_MODULES="$ADD_MODULES,javafx.controls,javafx.graphics,javafx.base"

OS="$(uname -s)"
echo "==> OS: $OS"

# 准备 pyLauncher 数据目录（只放说明文件，运行时自动生成内容）
prepare_data_dir() {
    local dest="$1"
    mkdir -p "$dest/pyLauncher"
    cat > "$dest/pyLauncher/README.txt" <<'EOF'
这个目录存放启动器的全部数据：
  launcher_config.json  配置
  launcher.log          日志
  .minecraft/           游戏目录（版本、模组、存档等）
  servers/              服务端
  java/                 下载的 Java 运行时

整个启动器是便携的：把 SimpleMCLauncher 文件夹搬到别处也能继续用。
EOF
}

# 给 Windows 加一个显眼的启动说明
prepare_windows_readme() {
    local dest="$1"
    cat > "$dest/启动说明.txt" <<'EOF'
极简 MC 启动器 · 便携版

使用方法：
  1. 双击本目录下的 SimpleMCLauncher.exe 即可运行
  2. 不需要安装 Java，runtime 文件夹里已经内置

目录说明：
  SimpleMCLauncher.exe   主程序
  app\                   程序代码（不要删）
  runtime\               内置 Java 运行时（不要删）
  pyLauncher\            数据目录（配置、日志、游戏）

提示：
  · 整个文件夹可以随意移动或复制到 U 盘，不影响使用
  · 若提示「Windows 已保护你的电脑」，点「更多信息」→「仍要运行」
  · 首次启动会在 pyLauncher 里生成游戏目录，属正常现象
EOF
}

build_app_image() {
    local icon="$1"
    echo "==> jpackage app-image"
    jpackage \
        --type app-image \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --input "$INPUT_DIR" \
        --main-jar "$MAIN_JAR" \
        --main-class "$MAIN_CLASS" \
        --module-path "$MODULE_PATH" \
        --add-modules "$ADD_MODULES" \
        --java-options "-Dfile.encoding=UTF-8" \
        --java-options "-Xmx512m" \
        --java-options "-XX:+UseSerialGC" \
        --icon "$icon" \
        --dest "$DIST_DIR"
}

case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        build_app_image src/main/resources/icon.ico

        PKG="$DIST_DIR/$APP_NAME"
        prepare_data_dir "$PKG"
        prepare_windows_readme "$PKG"

        echo "==> 压缩为 zip"
        ( cd "$DIST_DIR" && \
          powershell -NoProfile -Command \
            "Compress-Archive -Path '$APP_NAME' -DestinationPath '$APP_NAME-$APP_VERSION-windows.zip' -Force" )
        ;;
    Darwin)
        # macOS 的 app-image 直接产出 <dest>/SimpleMCLauncher.app，
        # 不会建 SimpleMCLauncher/ 这一层目录。
        build_app_image src/main/resources/icon.icns

        APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
        if [ ! -d "$APP_BUNDLE" ]; then
            echo "ERROR: 未找到 $APP_BUNDLE"
            ls -la "$DIST_DIR"
            exit 1
        fi

        # 把 .app 和 pyLauncher 放进同一个外层文件夹，
        # 保证「解压后是一个文件夹，里面 app 和 runtime 都在」
        PKG="$DIST_DIR/$APP_NAME"
        mkdir -p "$PKG"
        mv "$APP_BUNDLE" "$PKG/"
        prepare_data_dir "$PKG"

        echo "==> 压缩为 zip"
        ( cd "$DIST_DIR" && zip -qry "$APP_NAME-$APP_VERSION-macos.zip" "$APP_NAME" )
        ;;
    Linux)
        build_app_image src/main/resources/icon.png

        PKG="$DIST_DIR/$APP_NAME"
        prepare_data_dir "$PKG"
        chmod +x "$PKG/bin/$APP_NAME" 2>/dev/null || true

        echo "==> 压缩为 tar.gz"
        ( cd "$DIST_DIR" && tar -czf "$APP_NAME-$APP_VERSION-linux.tar.gz" "$APP_NAME" )
        ;;
    *)
        echo "ERROR: unsupported OS $OS"; exit 1
        ;;
esac

# ---------------------------------------------------------------
# 验证产物结构
#
# 不同平台的 jpackage app-image 布局不一样：
#   Windows: <pkg>/SimpleMCLauncher.exe + <pkg>/app/ + <pkg>/runtime/
#   macOS:   <pkg>/<App>.app/...            （zip 里是 .app 包）
#   Linux:   <pkg>/bin/ + <pkg>/lib/app/ + <pkg>/lib/runtime/
# 所以按平台分别检查。
# ---------------------------------------------------------------
echo ""
echo "==> 产物结构检查"
PKG="$DIST_DIR/$APP_NAME"
STRUCT_OK=1

check_dir() {
    if [ -d "$1" ]; then
        echo "  OK   ${1#$DIST_DIR/}"
    else
        echo "  MISS ${1#$DIST_DIR/}"
        STRUCT_OK=0
    fi
}

case "$OS" in
    MINGW*|MSYS*|CYGWIN*)
        check_dir "$PKG"
        check_dir "$PKG/app"
        check_dir "$PKG/runtime"
        check_dir "$PKG/pyLauncher"
        if [ -f "$PKG/$APP_NAME.exe" ]; then
            echo "  OK   ${PKG#$DIST_DIR/}/$APP_NAME.exe"
        else
            echo "  MISS $APP_NAME.exe"
            STRUCT_OK=0
        fi
        ;;
    Darwin)
        check_dir "$PKG"
        check_dir "$PKG/pyLauncher"
        APP_BUNDLE="$(find "$PKG" -maxdepth 1 -name "*.app" 2>/dev/null | head -1)"
        if [ -n "$APP_BUNDLE" ]; then
            echo "  OK   ${APP_BUNDLE#$DIST_DIR/}"
            check_dir "$APP_BUNDLE/Contents/app"
            check_dir "$APP_BUNDLE/Contents/runtime"
            if [ -f "$APP_BUNDLE/Contents/MacOS/$APP_NAME" ]; then
                echo "  OK   ${APP_BUNDLE#$DIST_DIR/}/Contents/MacOS/$APP_NAME"
            else
                echo "  MISS Contents/MacOS/$APP_NAME"
                STRUCT_OK=0
            fi
        else
            echo "  MISS *.app"
            STRUCT_OK=0
        fi
        ;;
    Linux)
        check_dir "$PKG"
        check_dir "$PKG/bin"
        check_dir "$PKG/lib/app"
        check_dir "$PKG/lib/runtime"
        check_dir "$PKG/pyLauncher"
        if [ -x "$PKG/bin/$APP_NAME" ]; then
            echo "  OK   ${PKG#$DIST_DIR/}/bin/$APP_NAME (可执行)"
        else
            echo "  MISS bin/$APP_NAME"
            STRUCT_OK=0
        fi
        ;;
esac

if [ "$STRUCT_OK" -ne 1 ]; then
    echo ""
    echo "ERROR: 产物结构不完整"
    exit 1
fi

# 检查 app.classpath 是否正常（不能有子目录，否则 jpackage 输入目录被污染）
case "$OS" in
    MINGW*|MSYS*|CYGWIN*) APP_DIR_FOR_CFG="$PKG/app" ;;
    Darwin)               APP_DIR_FOR_CFG="$(find "$PKG" -maxdepth 3 -type d -name app | head -1)" ;;
    Linux)                APP_DIR_FOR_CFG="$PKG/lib/app" ;;
esac
CFG="$(find "$APP_DIR_FOR_CFG" -maxdepth 1 -name "*.cfg" 2>/dev/null | head -1)"
if [ -n "$CFG" ]; then
    if grep -q "app.classpath=.*[/\\\\]app[/\\\\]" "$CFG"; then
        echo "  ERROR app.classpath 含嵌套子目录，启动会失败"
        exit 1
    else
        echo "  OK   app.classpath 正常"
    fi
else
    echo "  WARN 未找到 .cfg 文件"
fi

echo ""
echo "==> Done. Output in $DIST_DIR:"
ls -lh "$DIST_DIR" | grep -v "^total"
