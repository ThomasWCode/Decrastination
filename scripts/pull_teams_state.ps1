# Pulls the Teams widget's store from the phone into private/teams_widget_state.json (git-ignored),
# and checks that the installed Teams widget is signed with the same key as this app's debug builds
# (app/debug.keystore), which the widget's signature-level provider permission needs.
#
# It never writes the tracked fixture (fixtures/teams_widget_state.json): a failed pull can't damage
# it, and what's copied there is chosen by hand. Exits non-zero if adb, apksigner or keytool fail,
# the store isn't the widget's JSON, or the certificates differ.
#
# Run from the project root:  .\scripts\pull_teams_state.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$private = Join-Path $root "private"
New-Item -ItemType Directory -Force $private | Out-Null
$out = Join-Path $private "teams_widget_state.json"

# A native command's output, or a stop if it failed: PowerShell doesn't stop on their exit codes.
function Invoke-Checked([string]$what, [scriptblock]$command) {
    $output = & $command
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" }
    return $output
}

# A certificate fingerprint as bare lowercase hex, however it's written ("9A:48:B9...", "9a48b9...").
function Get-Fingerprint([string]$line) {
    $hex = ($line -replace "^.*?(SHA-?256[^:]*:)", "") -replace "[^0-9A-Fa-f]", ""
    if ($hex.Length -ne 64) { throw "No SHA-256 fingerprint in: $line" }
    return $hex.ToLowerInvariant()
}

$lines = Invoke-Checked "Reading the widget's store" { adb shell run-as com.teamsassignments.widget cat files/widget_state.json }
$text = $lines -join "`n"
$state = $text | ConvertFrom-Json
if ($null -eq $state.assignments -or $null -eq $state.lastSuccessAt) { throw "That isn't the widget's store (no assignments or lastSuccessAt): nothing saved" }
# Written whole once it's known good, so a failure leaves the last pull as it was.
$partial = "$out.partial"
[IO.File]::WriteAllText($partial, $text, (New-Object Text.UTF8Encoding $false))
Move-Item -Force $partial $out
Write-Host ("Pulled {0} assignments into private\, last successful sync {1}" -f @($state.assignments).Count, ([DateTimeOffset]::FromUnixTimeMilliseconds($state.lastSuccessAt).ToLocalTime()))

# Signature check: the installed widget's certificate against this app's debug keystore.
$apkPath = ((Invoke-Checked "Finding the widget's APK" { adb shell pm path com.teamsassignments.widget }) | Select-Object -First 1) -replace "^package:", ""
$apk = Join-Path $env:TEMP "teams_widget_installed.apk"
Invoke-Checked "Pulling the widget's APK" { adb pull $apkPath.Trim() $apk } | Out-Null
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $buildTools) { throw "No Android build-tools found under $env:LOCALAPPDATA\Android\Sdk\build-tools" }
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
$installedLine = (Invoke-Checked "apksigner" { & $apksigner verify --print-certs $apk }) | Where-Object { $_ -match "SHA-256 digest" } | Select-Object -First 1
$keytool = Join-Path (Split-Path -Parent (Get-Command java).Source) "keytool.exe"
$keystore = Join-Path $root "app\debug.keystore"
$localLine = (Invoke-Checked "keytool" { & $keytool -list -v -keystore $keystore -storepass android -alias androiddebugkey }) | Where-Object { $_ -match "SHA256:" } | Select-Object -First 1
$installed = Get-Fingerprint "$installedLine"
$local = Get-Fingerprint "$localLine"
Write-Host "Installed widget's certificate: $installed"
Write-Host "app\debug.keystore:            $local"
if ($installed -ne $local) { throw "The certificates differ: Decrastination can't read the widget's provider" }
Write-Host "They match."
