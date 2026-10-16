[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$AndroidSdkRoot,

    [string]$DeviceSerial,

    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$packageName = 'com.example.cameraagent'
$cameraPermission = 'android.permission.CAMERA'
$internetPermission = 'android.permission.INTERNET'
$sdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path
$adbPath = Join-Path $sdkRoot 'platform-tools\adb.exe'
$buildToolsRoot = Join-Path $sdkRoot 'build-tools'

if (!(Test-Path -LiteralPath $adbPath)) {
    throw "ADB was not found at $adbPath"
}

$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) {
    throw "No Android build-tools installation found under $buildToolsRoot"
}
$aaptPath = Join-Path $buildTools.FullName 'aapt.exe'
if (!(Test-Path -LiteralPath $aaptPath)) {
    throw "aapt was not found at $aaptPath"
}

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot 'gradlew.bat') ':app:assembleDebug'
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
    if (!(Test-Path -LiteralPath $apkPath)) {
        throw "Expected APK was not created at $apkPath"
    }

    $packagedPermissions = & $aaptPath 'dump' 'permissions' $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw "aapt failed with exit code $LASTEXITCODE"
    }
    $permissionText = $packagedPermissions -join "`n"
    if ($permissionText -notmatch [regex]::Escape("uses-permission: name='$cameraPermission'")) {
        throw "The APK does not declare the required camera permission. Output:`n$permissionText"
    }
    if ($permissionText -notmatch [regex]::Escape("uses-permission: name='$internetPermission'")) {
        throw "The APK does not declare the required internet permission for manual cloud requests. Output:`n$permissionText"
    }
    foreach ($unexpectedPermission in @('android.permission.ACCESS_NETWORK_STATE')) {
        if ($permissionText -match [regex]::Escape("uses-permission: name='$unexpectedPermission'")) {
            throw "Unexpected permission in APK: $unexpectedPermission"
        }
    }

    Write-Host "PASS: APK built at $apkPath"
    Write-Host "PASS: packaged permissions contain CAMERA and INTERNET, without ACCESS_NETWORK_STATE"
    if ($BuildOnly) {
        return
    }

    $deviceLines = @(& $adbPath 'devices' -l)
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed with exit code $LASTEXITCODE"
    }
    $readyDevices = @($deviceLines | Where-Object { $_ -match '^\S+\s+device\s' })
    if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
        if ($readyDevices.Count -ne 1) {
            throw "Connect exactly one Android device or pass -DeviceSerial. Ready devices: $($readyDevices -join '; ')"
        }
        $DeviceSerial = ($readyDevices[0] -split '\s+')[0]
    } elseif (!($readyDevices | Where-Object { $_ -match "^$([regex]::Escape($DeviceSerial))\s+device\s" })) {
        throw "ADB device '$DeviceSerial' is not in the ready device list: $($readyDevices -join '; ')"
    }

    & $adbPath '-s' $DeviceSerial 'install' '-r' $apkPath
    if ($LASTEXITCODE -ne 0) {
        throw "APK install failed on $DeviceSerial with exit code $LASTEXITCODE"
    }
    & $adbPath '-s' $DeviceSerial 'shell' 'pm' 'grant' $packageName $cameraPermission
    if ($LASTEXITCODE -ne 0) {
        throw "Could not grant camera permission on $DeviceSerial"
    }
    & $adbPath '-s' $DeviceSerial 'logcat' '-c'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not clear logcat on $DeviceSerial"
    }
    & $adbPath '-s' $DeviceSerial 'shell' 'monkey' '-p' $packageName '-c' 'android.intent.category.LAUNCHER' '1'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not launch $packageName on $DeviceSerial"
    }
    Start-Sleep -Seconds 8

    $appPid = (& $adbPath '-s' $DeviceSerial 'shell' 'pidof' $packageName | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($appPid)) {
        throw "Camera Agent is not running on $DeviceSerial"
    }
    $logLines = @(& $adbPath '-s' $DeviceSerial 'logcat' '-d' '-t' '1000')
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read logcat on $DeviceSerial"
    }
    $logText = $logLines -join "`n"
    $packagePattern = [regex]::Escape($packageName)
    $hasFatalForApp = $logText -match "(?s)FATAL EXCEPTION.*?Process:\s+$packagePattern(?:\s|$)"
    $hasAnrForApp = $logText -match "(?m)ANR in $packagePattern(?:\s|$)"
    if ($hasFatalForApp -or $hasAnrForApp) {
        throw "Camera Agent reported a fatal error on ${DeviceSerial}. Inspect logcat for process $packageName."
    }

    $evidenceDirectory = Join-Path $repoRoot 'build\verification'
    New-Item -ItemType Directory -Path $evidenceDirectory -Force | Out-Null
    $safeSerial = $DeviceSerial -replace '[^A-Za-z0-9_.-]', '_'
    $remoteScreenshot = '/sdcard/cameraagent-verification.png'
    $localScreenshot = Join-Path $evidenceDirectory "cameraagent-$safeSerial.png"
    & $adbPath '-s' $DeviceSerial 'shell' 'screencap' '-p' $remoteScreenshot
    if ($LASTEXITCODE -ne 0) {
        throw "Could not capture screen on $DeviceSerial"
    }
    & $adbPath '-s' $DeviceSerial 'pull' $remoteScreenshot $localScreenshot
    if ($LASTEXITCODE -ne 0) {
        throw "Could not pull verification screenshot from $DeviceSerial"
    }

    Write-Host "PASS: installed and launched on $DeviceSerial (PID $appPid)"
    Write-Host "PASS: no Camera Agent fatal exception or ANR in the sampled logcat"
    Write-Host "Evidence screenshot: $localScreenshot"
    Write-Host 'Manual camera, scene, control, and photo checks remain in docs/DEVICE_CAPABILITY_MATRIX.md.'
}
finally {
    Pop-Location
}
