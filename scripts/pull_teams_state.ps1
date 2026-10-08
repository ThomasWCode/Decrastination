# Refreshes fixtures/teams_widget_state.json from the phone, and checks that the
# installed Teams widget was signed by this PC's debug keystore (required for the
# signature-level ContentProvider permission in Phase 0).
#
# Run from the project root:  .\scripts\pull_teams_state.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "fixtures\teams_widget_state.json"

adb shell run-as com.teamsassignments.widget cat files/widget_state.json | Out-File -Encoding utf8 $out
$state = Get-Content $out -Raw | ConvertFrom-Json
Write-Host ("Pulled {0} assignments, last successful sync {1}" -f $state.assignments.Count, ([DateTimeOffset]::FromUnixTimeMilliseconds($state.lastSuccessAt).ToLocalTime()))

# Signature check
$apkPath = (adb shell pm path com.teamsassignments.widget | Select-Object -First 1) -replace "^package:", ""
$tmp = Join-Path $env:TEMP "teams_widget_installed.apk"
adb pull $apkPath.Trim() $tmp | Out-Null
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
$installed = & (Join-Path $buildTools.FullName "apksigner.bat") verify --print-certs $tmp | Select-String "SHA-256"
$keytool = Join-Path (Split-Path -Parent (Get-Command java).Source) "keytool.exe"
$committed = Join-Path (Split-Path -Parent $root) "TeamsAssignmentsWidget\app\debug.keystore"
$local = & $keytool -list -v -keystore $committed -storepass android -alias androiddebugkey | Select-String "SHA256:"
Write-Host "Installed APK cert:        $installed"
Write-Host "Teams repo debug.keystore: $local"
Write-Host "These must match (they did on 7 Oct 2026: 9A:48:B9:F9...7B:F6). Decrastination signs its debug builds with a copy of that keystore so the signature permission is granted."
