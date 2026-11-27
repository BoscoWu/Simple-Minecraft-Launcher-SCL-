# ============================================================
#  Windows 单文件打包
#
#  产物：target/dist/SimpleMCLauncher.exe
#
#  最终形态（用户解压后看到的）：
#    SimpleMCLauncher.exe     双击运行，唯一需要的文件
#    pyLauncher/              首次运行自动生成
#
#  exe 内部携带 payload.zip（jar + jlink 精简 JRE），
#  首次运行解压到 %LOCALAPPDATA%\SimpleMCLauncher\runtime-<hash>。
# ============================================================
param(
    [string]$Version = "",
    [switch]$KeepWork
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $PSScriptRoot
Set-Location $proj

if (-not $Version) {
    # 必须显式按 UTF-8 读；默认按 ANSI 读会让中文注释变成乱码，XML 解析失败
    $xmlText = [System.IO.File]::ReadAllText((Join-Path $proj "pom.xml"),
        [System.Text.Encoding]::UTF8)
    $xml = [xml]$xmlText
    $Version = $xml.project.version
}
Write-Host "==> Version: $Version"

$work       = Join-Path $proj "target\singleexe"
$outDir     = Join-Path $proj "target\dist"
$payloadDir = Join-Path $work "payload"
$jreDir     = Join-Path $payloadDir "jre"
$appDir     = Join-Path $payloadDir "app"

Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $payloadDir, $appDir, $outDir | Out-Null
# jreDir 不能预建，jlink 要求输出目录不存在

# ---------- 1. 构建 jar ----------
Write-Host "==> Maven package"
& cmd /c "mvnw.cmd -q -B -DskipTests package 2>&1" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }

$mainJar = Join-Path $proj "target\SimpleMCLauncher.jar"
if (-not (Test-Path $mainJar)) { throw "找不到 $mainJar" }
Copy-Item $mainJar $appDir

Get-ChildItem (Join-Path $proj "target\dependency") -Filter "*.jar" |
    Where-Object { $_.Name -match '^javafx-.*-win\.jar$' } |
    ForEach-Object { Copy-Item $_.FullName $appDir }

Write-Host "    app jar: $((Get-ChildItem $appDir -Filter '*.jar').Count) 个"

# ---------- 2. jlink 精简 JRE ----------
Write-Host "==> jlink 生成精简 JRE"
$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
if (-not (Test-Path $jlink)) { throw "找不到 jlink: $jlink" }

# 这个 JRE 有两个用途，模块必须同时满足：
#   1. 跑启动器本身：java.net.http（网络）、jdk.crypto.ec（TLS）
#   2. 跑 Minecraft：java.compiler、java.sql 等
#      （用 jdeps 分析 1.21.1 游戏 jar 得出的依赖）
# 少任何一个，用户没装 Java 时游戏就起不来。
$modules = @(
    # 启动器
    "java.base", "java.desktop", "java.net.http", "java.logging",
    "java.management", "java.naming", "java.prefs", "java.xml",
    "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.charsets",
    "jdk.jfr", "jdk.unsupported",
    # Minecraft 需要但上面没有的
    "java.compiler", "java.sql", "java.instrument", "jdk.zipfs",
    "jdk.management", "java.security.jgss"
) -join ","

& $jlink --add-modules $modules --output $jreDir `
    --strip-debug --no-header-files --no-man-pages --compress=zip-9
if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }

$jreSize = (Get-ChildItem $jreDir -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host ("    JRE: {0:N1} MB" -f ($jreSize / 1MB))

# ---------- 3. 打 payload.zip ----------
Write-Host "==> 打包 payload"
$payloadZip = Join-Path $work "payload.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $payloadDir, $payloadZip,
    [System.IO.Compression.CompressionLevel]::Optimal, $false)

Write-Host ("    payload.zip: {0:N1} MB" -f ((Get-Item $payloadZip).Length / 1MB))

# ---------- 4. 编译 stub ----------
Write-Host "==> 编译启动器"
$csc = Get-ChildItem "C:\Windows\Microsoft.NET\Framework64" -Filter "csc.exe" -Recurse |
    Where-Object { $_.FullName -like "*v4.0.30319*" } | Select-Object -First 1
if (-not $csc) { throw "找不到 csc.exe（需要 .NET Framework 4）" }

$stubSrc = Join-Path $proj "packaging\LauncherStub.cs"
$exe     = Join-Path $outDir "SimpleMCLauncher.exe"
$icon    = Join-Path $proj "src\main\resources\icon.ico"

# 参数必须逐个用数组元素传，不能写成 "/resource:$x,name"
# （PowerShell 不会展开那种 token 里的变量）
$cscArgs = @(
    "/nologo"
    "/target:winexe"
    "/platform:anycpu"
    "/out:$exe"
    "/r:System.Windows.Forms.dll"
    "/r:System.IO.Compression.dll"
    "/r:System.IO.Compression.FileSystem.dll"
    ("/resource:" + $payloadZip + ",payload.zip")
)
if (Test-Path $icon) { $cscArgs += ("/win32icon:" + $icon) }
$cscArgs += $stubSrc

& $csc.FullName @cscArgs 2>&1 | Select-Object -Last 5
if ($LASTEXITCODE -ne 0) { throw "csc 编译失败" }
if (-not (Test-Path $exe)) { throw "未生成 $exe" }

Write-Host ""
Write-Host "==> 产物：$exe"
Write-Host ("    大小: {0:N1} MB" -f ((Get-Item $exe).Length / 1MB))

if (-not $KeepWork) {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
