# ============================================================
#  把启动器打成一个单文件 exe（Windows）
#
#  产物：SingleExe/SimpleMCLauncher.exe
#  用户看到的只有一个文件，双击即用：
#    <任意目录>/
#    ├── SimpleMCLauncher.exe    <- 唯一文件
#    └── pyLauncher/             <- 首次运行后自动生成
#
#  原理：exe 内部携带 payload.zip（jar + jlink 精简 JRE），
#       首次运行解压到 %LOCALAPPDATA%\SimpleMCLauncher\runtime-<hash>。
# ============================================================
param(
    [string]$Version = "",
    [switch]$KeepWork
)

$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $PSScriptRoot
Set-Location $proj

if (-not $Version) {
    $xml = [xml](Get-Content "pom.xml" -Raw)
    $Version = $xml.project.version
}
Write-Host "==> Version: $Version"

$work = Join-Path $proj "target\singleexe"
$outDir = Join-Path $proj "target\dist"
$payloadDir = Join-Path $work "payload"
$jreDir = Join-Path $payloadDir "jre"
$appDir = Join-Path $payloadDir "app"

Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $payloadDir, $appDir, $outDir | Out-Null
# 注意：jreDir 不能预先创建，jlink 要求目标目录不存在

# ---------- 1. 构建 jar ----------
Write-Host "==> Maven package"
& cmd /c "mvnw.cmd -q -B -DskipTests package 2>&1" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }

$mainJar = Join-Path $proj "target\SimpleMCLauncher.jar"
if (-not (Test-Path $mainJar)) { throw "找不到 $mainJar" }
Copy-Item $mainJar $appDir

# JavaFX 平台 jar
Get-ChildItem (Join-Path $proj "target\dependency") -Filter "javafx-*-win.jar" |
    ForEach-Object { Copy-Item $_.FullName $appDir }

$jarCount = (Get-ChildItem $appDir -Filter "*.jar").Count
Write-Host "    app jar: $jarCount 个"

# ---------- 2. jlink 精简 JRE ----------
Write-Host "==> jlink 生成精简 JRE"
$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
if (-not (Test-Path $jlink)) { throw "找不到 jlink: $jlink" }

$modules = @(
    "java.base", "java.desktop", "java.net.http", "java.logging",
    "java.management", "java.naming", "java.prefs", "java.xml",
    "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.charsets",
    "jdk.jfr", "jdk.unsupported"
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

$zipSize = (Get-Item $payloadZip).Length
Write-Host ("    payload.zip: {0:N1} MB" -f ($zipSize / 1MB))

# ---------- 4. 编译 stub 并内嵌 ----------
Write-Host "==> 编译启动器 stub"
$csc = Get-ChildItem "C:\Windows\Microsoft.NET\Framework64" -Filter "csc.exe" -Recurse |
    Where-Object { $_.FullName -like "*v4.0.30319*" } | Select-Object -First 1
if (-not $csc) { throw "找不到 csc.exe（需要 .NET Framework 4）" }

$stubSrc = Join-Path $proj "packaging\Stub.cs"
$stubTmp = Get-Content $stubSrc -Raw
$stubTmp = $stubTmp -replace '@VERSION@', $Version
$stubEdited = Join-Path $work "Stub.cs"
Set-Content -Path $stubEdited -Value $stubTmp -Encoding UTF8

$exe = Join-Path $outDir "SimpleMCLauncher.exe"
$icon = Join-Path $proj "src\main\resources\icon.ico"

& $csc.FullName /nologo /target:winexe /platform:anycpu `
    /out:$exe `
    /r:System.Windows.Forms.dll /r:System.IO.Compression.dll `
    /r:System.IO.Compression.FileSystem.dll `
    /resource:$payloadZip,payload.zip `
    $(if (Test-Path $icon) { "/win32icon:$icon" }) `
    $stubEdited 2>&1 | Select-Object -Last 5

if ($LASTEXITCODE -ne 0) { throw "csc 编译失败" }
if (-not (Test-Path $exe)) { throw "未生成 $exe" }

$exeSize = (Get-Item $exe).Length
Write-Host ""
Write-Host "==> 产物：$exe"
Write-Host ("    大小: {0:N1} MB" -f ($exeSize / 1MB))

if (-not $KeepWork) {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
