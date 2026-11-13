param(
    [switch]$Zip
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

$AppName = "MaraFlow"
$AppVersion = "1.0.0"
$MainClass = "cz.maramat.Main"
$JarName = "Maraflow-1.0-SNAPSHOT.jar"
$IconPath = Join-Path $ScriptDir "src\main\resources\icons\MaraFlow.ico"
$DistDir = Join-Path $ScriptDir "dist\win"
$InputDir = Join-Path $ScriptDir "target\jpackage-input"

$MvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $MvnCmd) {
    $Candidates = @(
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA Ultimate\plugins\maven\lib\maven3\bin\mvn.cmd",
        "$env:ProgramFiles\JetBrains\IntelliJ IDEA*\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
    foreach ($Pattern in $Candidates) {
        $Found = Get-ChildItem -Path $Pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($Found) { $MvnCmd = $Found.FullName; break }
    }
} else {
    $MvnCmd = $MvnCmd.Source
}
if (-not $MvnCmd) {
    throw "Maven (mvn) not found on PATH or in IntelliJ IDEA."
}

Write-Host "==> 1/3: Building shaded JAR with Maven..."
& $MvnCmd clean package -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (Test-Path $InputDir) { Remove-Item -Recurse -Force $InputDir }
New-Item -ItemType Directory -Force -Path $InputDir | Out-Null
Copy-Item (Join-Path $ScriptDir "target\$JarName") $InputDir

Write-Host "==> 2/3: Packaging $AppName.exe app-image with jpackage..."
$AppOutDir = Join-Path $DistDir $AppName
if (Test-Path $AppOutDir) { Remove-Item -Recurse -Force $AppOutDir }
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

& jpackage `
  --type app-image `
  --name $AppName `
  --app-version $AppVersion `
  --vendor "Maramat" `
  --input $InputDir `
  --main-jar $JarName `
  --main-class $MainClass `
  --icon $IconPath `
  --dest $DistDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($Zip) {
    Write-Host "==> 3/3: Creating Windows distribution ZIP..."
    $ZipPath = Join-Path $DistDir "$AppName-Windows-x64.zip"
    if (Test-Path $ZipPath) { Remove-Item -Force $ZipPath }
    Compress-Archive -Path $AppOutDir -DestinationPath $ZipPath -CompressionLevel Optimal
    Write-Host "ZIP: $ZipPath"
}

Write-Host "=========================================================="
Write-Host "SUCCESS! Windows app-image created:"
Write-Host "  $AppOutDir\$AppName.exe"
Write-Host "Right-click $AppName.exe -> Pin to taskbar / Pin to Start."
Write-Host "=========================================================="
