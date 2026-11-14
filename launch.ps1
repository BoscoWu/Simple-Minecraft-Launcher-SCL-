# ============================================================
#  Simple MC Launcher - launch script
#  Starts the launcher and exits immediately. The launcher
#  process is independent and will NOT be killed on exit.
# ============================================================

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# 1. Prefer the packaged exe (fastest, bundles its own JRE)
$packaged = Join-Path $PSScriptRoot "target\output\SimpleMCLauncher\SimpleMCLauncher.exe"
if (Test-Path $packaged) {
    Start-Process -FilePath $packaged -WorkingDirectory (Split-Path $packaged)
    exit 0
}

# 2. Otherwise run from source
$jdk = "C:\Program Files\Java\jdk-21.0.12.1"
$java = Join-Path $jdk "bin\java.exe"
if (-not (Test-Path $java)) {
    $found = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk-2*" } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($found) {
        $java = Join-Path $found.FullName "bin\java.exe"
    } else {
        Write-Host "ERROR: JDK 21 not found at $jdk"
        Read-Host "Press Enter to exit"
        exit 1
    }
}

$classes = Join-Path $PSScriptRoot "target\classes"
$appClass = Join-Path $classes "com\simplemc\launcher\LauncherApp.class"
if (-not (Test-Path $appClass)) {
    Write-Host "First run: compiling..."
    & (Join-Path $PSScriptRoot "mvnw.cmd") -q -B compile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Compile failed."
        Read-Host "Press Enter to exit"
        exit 1
    }
}

$m2 = Join-Path $env:USERPROFILE ".m2\repository"

# JavaFX jars (platform specific)
$jfxJars = @(Get-ChildItem (Join-Path $m2 "org\openjfx") -Recurse -Filter "*-win.jar" -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName)

if ($jfxJars.Count -eq 0) {
    Write-Host "JavaFX dependencies not found. Run 'mvnw.cmd compile' first."
    Read-Host "Press Enter to exit"
    exit 1
}

# Runtime libraries: Jackson (JSON) + webp-imageio (Modrinth icons are WebP)
$libGroups = @("com\fasterxml\jackson", "org\sejda\imageio")
$libJars = @()
foreach ($g in $libGroups) {
    $dir = Join-Path $m2 $g
    if (Test-Path $dir) {
        $found = @(Get-ChildItem $dir -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName)
        if ($found.Count -gt 0) { $libJars += $found }
    }
}
$libJars = @($libJars | Select-Object -Unique)

if ($libJars.Count -eq 0) {
    Write-Host "Runtime libraries not found. Run 'mvnw.cmd compile' first."
    Read-Host "Press Enter to exit"
    exit 1
}

$jfxPath = $jfxJars -join ";"
$libPath = $libJars -join ";"

$argList = @(
    "-Dfile.encoding=UTF-8",
    "--module-path", $jfxPath,
    "--add-modules", "javafx.controls,javafx.graphics,javafx.base",
    "-cp", "$classes;$libPath",
    "com.simplemc.launcher.LauncherApp"
)

Write-Host ("JavaFX jars: " + $jfxJars.Count)
Write-Host ("Library jars: " + $libJars.Count)
Start-Process -FilePath $java -ArgumentList $argList -WorkingDirectory $PSScriptRoot
Write-Host "Launcher started."
