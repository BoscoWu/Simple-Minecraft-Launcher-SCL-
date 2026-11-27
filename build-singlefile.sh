#!/usr/bin/env bash
# ============================================================
#  把启动器打成单文件（macOS / Linux）
#
#  macOS  -> SimpleMCLauncher-<ver>-macos.zip   内含一个 SimpleMCLauncher 可执行文件
#  Linux  -> SimpleMCLauncher-<ver>-linux.zip   内含一个 SimpleMCLauncher 可执行文件
#
#  做法：把一个自解压 shell 脚本头 + tar.gz 负载拼接成单个文件。
#  首次运行解压到缓存目录，之后复用。
#  pyLauncher 数据目录放在可执行文件旁边（便携）。
# ============================================================
set -euo pipefail

APP_NAME="SimpleMCLauncher"
MAIN_CLASS="com.simplemc.launcher.LauncherApp"
MAIN_JAR="SimpleMCLauncher.jar"

cd "$(dirname "$0")"

APP_VERSION="$(sed -n 's|.*<version>\([0-9][^<]*\)</version>.*|\1|p' pom.xml | head -1)"
[ -n "$APP_VERSION" ] || { echo "ERROR: 无法读取版本号"; exit 1; }
echo "==> Version: $APP_VERSION"

WORK="target/singlefile"
OUT="target/dist"
PAYLOAD="$WORK/payload"
rm -rf "$WORK"
mkdir -p "$PAYLOAD/app" "$OUT"

echo "==> Maven package"
./mvnw -q -B -DskipTests package

cp "target/$MAIN_JAR" "$PAYLOAD/app/"
for jar in target/dependency/*.jar; do
    [ -f "$jar" ] || continue
    case "$(basename "$jar")" in
        *-mac.jar|*-mac-aarch64.jar|*-linux.jar|*-linux-aarch64.jar)
            cp "$jar" "$PAYLOAD/app/"
            ;;
    esac
done
echo "    app jar: $(ls -1 "$PAYLOAD/app" | wc -l) 个"

# ---------- jlink 精简 JRE ----------
echo "==> jlink 生成精简 JRE"
MODULES="java.base,java.desktop,java.net.http,java.logging,java.management"
MODULES="$MODULES,java.naming,java.prefs,java.xml,jdk.crypto.ec"
MODULES="$MODULES,jdk.crypto.cryptoki,jdk.charsets,jdk.jfr,jdk.unsupported"

jlink --add-modules "$MODULES" --output "$PAYLOAD/jre" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-9

if [ -d "$PAYLOAD/jre/lib" ]; then
    JRE_SIZE=$(du -sm "$PAYLOAD/jre" | cut -f1)
    echo "    JRE: ${JRE_SIZE} MB"
fi

# ---------- 打包负载 ----------
echo "==> 打包负载"
tar -czf "$WORK/payload.tar.gz" -C "$PAYLOAD" .

# ---------- 生成自解压脚本 ----------
STUB="$WORK/stub.sh"
cat > "$STUB" <<'STUB_EOF'
#!/bin/sh
# 极简 MC 启动器 - 单文件自解压启动器
# 首次运行会解压运行时到缓存目录，之后直接复用。
set -e

SELF="$0"
APP_NAME="SimpleMCLauncher"
MAIN_CLASS="com.simplemc.launcher.LauncherApp"

# 解析自身真实路径（处理软链接）
while [ -L "$SELF" ]; do
    TARGET=$(readlink "$SELF")
    case "$TARGET" in
        /*) SELF="$TARGET" ;;
        *)  SELF="$(dirname "$SELF")/$TARGET" ;;
    esac
done
EXE_DIR=$(cd "$(dirname "$SELF")" && pwd)

# 缓存目录：按自身大小+修改时间做键，升级后自动换
KEY=$(printf '%s:%s' "$(wc -c < "$SELF")" "$(stat -c %Y "$SELF" 2>/dev/null || stat -f %m "$SELF")" | cksum | cut -d' ' -f1)
if [ "$(uname)" = "Darwin" ]; then
    CACHE_BASE="$HOME/Library/Caches"
else
    CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}"
fi
CACHE="$CACHE_BASE/$APP_NAME/runtime-$KEY"

JAVA="$CACHE/jre/bin/java"
[ -x "$JAVA" ] || JAVA="$CACHE/jre/Contents/Home/bin/java"

if [ ! -x "$JAVA" ]; then
    echo "首次运行，正在解压运行时（约需几秒）..."
    mkdir -p "$CACHE"
    # 负载从本脚本之后开始
    SKIP=$(awk '/^__PAYLOAD_BELOW__$/{print NR + 1; exit 0;}' "$SELF")
    tail -n +"$SKIP" "$SELF" | tar -xzf - -C "$CACHE"
    echo "解压完成。"
fi

if [ ! -x "$JAVA" ]; then
    echo "错误：找不到 Java 运行时 $JAVA" >&2
    exit 1
fi

APP_DIR="$CACHE/app"
FX_PATH=$(ls "$APP_DIR"/javafx-*.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')

exec "$JAVA" \
    -Dfile.encoding=UTF-8 \
    -Xmx512m \
    -Dscl.app.dir="$EXE_DIR" \
    --module-path "$FX_PATH" \
    --add-modules javafx.controls,javafx.graphics,javafx.base \
    -cp "$APP_DIR/SimpleMCLauncher.jar" \
    "$MAIN_CLASS"

__PAYLOAD_BELOW__
STUB_EOF

# ---------- 拼接 ----------
case "$(uname -s)" in
    Darwin) PLAT="macos" ;;
    Linux)  PLAT="linux" ;;
    *) echo "ERROR: unsupported OS"; exit 1 ;;
esac

OUTFILE="$OUT/$APP_NAME"
cat "$STUB" "$WORK/payload.tar.gz" > "$OUTFILE"
chmod +x "$OUTFILE"

SIZE=$(( $(wc -c < "$OUTFILE") / 1024 / 1024 ))
echo ""
echo "==> 产物：$OUTFILE"
echo "    大小: ${SIZE} MB"

# ---------- 打 zip 便于上传 ----------
(cd "$OUT" && zip -q "$APP_NAME-$APP_VERSION-$PLAT.zip" "$APP_NAME")

echo "==> 压缩包：$OUT/$APP_NAME-$APP_VERSION-$PLAT.zip"
ls -lh "$OUT"
