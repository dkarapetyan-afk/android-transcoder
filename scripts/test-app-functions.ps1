# Requires: adb, a device/emulator on Android 16+, Recording Compressor installed.
# One-time on the phone: Settings -> Device library access -> Allow all (not "selected items").
#
# Examples (from the repo root, in PowerShell):
#   .\scripts\test-app-functions.ps1
#   .\scripts\test-app-functions.ps1 -Query clip
#   .\scripts\test-app-functions.ps1 -Path /sdcard/Download/clip.mp4
#   .\scripts\test-app-functions.ps1 -LocalFile C:\Videos\clip.mp4
#   .\scripts\test-app-functions.ps1 -ListOnly
#   .\scripts\test-app-functions.ps1 -Preset SMALLER -Container WEBM -Engine FFMPEG
#   .\scripts\test-app-functions.ps1 -Package com.androidcompress.app

[CmdletBinding()]
param(
    [string] $Package = "com.androidcompress.app.debug",
    [string] $Serial,
    [ValidateSet("VIDEO", "AUDIO", "IMAGE", "ANY")]
    [string] $Kind = "VIDEO",
    [string] $Query,
    [string] $Path,
    [string] $LocalFile,
    [string] $RemoteDir = "/sdcard/Download",
    [ValidateSet("SMALLER", "BALANCED", "HIGHER")]
    [string] $Preset = "SMALLER",
    [ValidateSet("FFMPEG", "MEDIA3")]
    [string] $Engine,
    [ValidateSet("MP4", "WEBM")]
    [string] $Container,
    [string] $ExtraArgs,
    [switch] $SkipStart,
    [switch] $ListOnly,
    [switch] $OpenSettings,
    [string] $SdkDir,
    [string] $AdbPath,
    [int] $Limit = 10,
    [int] $TimeoutSeconds = 180,
    [int] $PollSeconds = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$FunctionClass = "com.androidcompress.app.agent.BaseCompressAppFunctionService"
$script:AdbExe = $null

function Get-RepoRoot {
    $here = $PSScriptRoot
    if (-not [string]::IsNullOrWhiteSpace($here)) {
        return (Resolve-Path (Join-Path $here "..")).Path
    }
    return (Get-Location).Path
}

function ConvertFrom-GradleSdkDir {
    param([string] $Raw)
    if ([string]::IsNullOrWhiteSpace($Raw)) { return $null }
    $value = $Raw.Trim()
    # Gradle local.properties escapes Windows paths as C\:\\Users\\...
    $value = $value -replace '\\:', ':'
    $value = $value -replace '\\\\', '\'
    return $value
}

function Get-SdkDirFromLocalProperties {
    param([string] $RepoRoot)
    $file = Join-Path $RepoRoot "local.properties"
    if (-not (Test-Path -LiteralPath $file)) { return $null }
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match '^\s*sdk\.dir\s*=\s*(.+)\s*$') {
            $dir = ConvertFrom-GradleSdkDir $Matches[1]
            if ($dir -and (Test-Path -LiteralPath $dir)) { return $dir }
        }
    }
    return $null
}

function Get-AdbFromSdk {
    param([string] $Sdk)
    if ([string]::IsNullOrWhiteSpace($Sdk)) { return $null }
    $candidate = Join-Path $Sdk "platform-tools\adb.exe"
    if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    $posix = Join-Path $Sdk "platform-tools/adb"
    if (Test-Path -LiteralPath $posix) { return (Resolve-Path -LiteralPath $posix).Path }
    return $null
}

function Resolve-AdbExe {
    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        if (-not (Test-Path -LiteralPath $AdbPath)) {
            throw "AdbPath not found: $AdbPath"
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }

    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    $sdkCandidates = @()
    if (-not [string]::IsNullOrWhiteSpace($SdkDir)) { $sdkCandidates += $SdkDir }
    foreach ($name in @("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_SDK")) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { $sdkCandidates += $value }
    }
    $repo = Get-RepoRoot
    $fromProps = Get-SdkDirFromLocalProperties $repo
    if ($fromProps) { $sdkCandidates += $fromProps }

    $sdkCandidates += @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk"),
        (Join-Path $env:USERPROFILE "Android\Sdk"),
        "C:\Android\Sdk",
        "C:\Android\android-sdk",
        "${env:ProgramFiles(x86)}\Android\android-sdk",
        "$env:ProgramFiles\Android\android-sdk"
    )

    foreach ($sdk in $sdkCandidates) {
        $found = Get-AdbFromSdk $sdk
        if ($found) { return $found }
    }

    throw @"
Could not find adb.exe. Install Android platform-tools or pass -SdkDir / -AdbPath.
Looked at ANDROID_HOME, ANDROID_SDK_ROOT, local.properties sdk.dir, and %LOCALAPPDATA%\Android\Sdk\platform-tools.
"@
}

function Get-AdbPrefix {
    if ([string]::IsNullOrWhiteSpace($Serial)) { return @() }
    return @("-s", $Serial)
}

function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $all = @(Get-AdbPrefix) + $Arguments
    Write-Verbose ("$script:AdbExe " + ($all -join " "))
    # adb/am/monkey write status to stderr. With $ErrorActionPreference=Stop that
    # becomes a terminating NativeCommandError even when the exit code is 0.
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $oldNative = $null
    if (Test-Path Variable:PSNativeCommandUseErrorActionPreference) {
        $oldNative = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        $output = & $script:AdbExe @all 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldEap
        if ($null -ne $oldNative) {
            $PSNativeCommandUseErrorActionPreference = $oldNative
        }
    }
    $text = @(
        $output | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() }
            else { "$_" }
        }
    ) -join "`n"
    if ($code -ne 0) {
        throw "adb failed ($code): $text"
    }
    return $text
}

function ConvertTo-CompactJson {
    param($Value)
    return ($Value | ConvertTo-Json -Compress -Depth 10)
}

function ConvertFrom-AppFunctionOutput {
    param([Parameter(Mandatory = $true)][string] $Text)
    $start = $Text.IndexOf("{")
    $end = $Text.LastIndexOf("}")
    if ($start -lt 0 -or $end -le $start) {
        throw "App Function did not return JSON.`n$Text"
    }
    $slice = $Text.Substring($start, $end - $start + 1)
    try {
        return $slice | ConvertFrom-Json
    } catch {
        throw "Could not parse App Function JSON.`n$slice"
    }
}

function Write-UnixText {
    param([string] $Path, [string] $Text)
    $normalized = $Text -replace "`r`n", "`n" -replace "`r", "`n"
    if (-not $normalized.EndsWith("`n")) { $normalized += "`n" }
    [IO.File]::WriteAllText($Path, $normalized, [Text.UTF8Encoding]::new($false))
}

function Invoke-AppFunction {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [hashtable] $Parameters
    )
    $fn = "${FunctionClass}#${Name}"
    $json = if ($null -eq $Parameters -or $Parameters.Count -eq 0) {
        "{}"
    } else {
        ConvertTo-CompactJson $Parameters
    }
    Write-Host ">> $Name $json" -ForegroundColor DarkCyan
    # Windows adb shell eats double quotes. Keep JSON off the command line:
    # push a tiny sh wrapper and the payload, then run the wrapper.
    $id = [guid]::NewGuid().ToString("N")
    $remoteParams = "/data/local/tmp/rc-appfn-$id.json"
    $remoteSh = "/data/local/tmp/rc-appfn-$id.sh"
    $localParams = Join-Path ([IO.Path]::GetTempPath()) ("rc-appfn-$id.json")
    $localSh = Join-Path ([IO.Path]::GetTempPath()) ("rc-appfn-$id.sh")
    try {
        Write-UnixText -Path $localParams -Text $json
        # Literal $(cat ...) must survive PowerShell. Backtick-escape $ and ().
        $deviceWrapper = "#!/system/bin/sh`nexec cmd app_function execute-app-function --package $Package --function '$fn' --parameters `"`$`(cat $remoteParams`)`""
        Write-UnixText -Path $localSh -Text $deviceWrapper
        $null = Invoke-Adb -Arguments @("push", $localParams, $remoteParams)
        $null = Invoke-Adb -Arguments @("push", $localSh, $remoteSh)
        $raw = Invoke-Adb -Arguments @("shell", "sh", $remoteSh)
    } finally {
        Remove-Item -LiteralPath $localParams, $localSh -ErrorAction SilentlyContinue
        try {
            $null = Invoke-Adb -Arguments @("shell", "rm", "-f", $remoteParams, $remoteSh)
        } catch {
            # Best-effort.
        }
    }
    Write-Host $raw
    if ($raw -match "not found|App function not found|Unknown command|Does not exist") {
        throw "App Functions are not available. Need Android 16+, the app opened once, and package $Package."
    }
    return ConvertFrom-AppFunctionOutput $raw
}

function Get-AfValue {
    param(
        $Object,
        [string[]] $Names,
        [int] $Depth = 0
    )
    if ($null -eq $Object -or $Depth -gt 8) { return $null }
    if ($Object -is [string] -or $Object -is [ValueType]) { return $null }

    if ($Object -is [System.Collections.IEnumerable] -and -not ($Object -is [string])) {
        $list = @($Object)
        if ($list.Count -eq 1) { return Get-AfValue -Object $list[0] -Names $Names -Depth $Depth }
        foreach ($item in $list) {
            $found = Get-AfValue -Object $item -Names $Names -Depth ($Depth + 1)
            if ($null -ne $found) { return $found }
        }
        return $null
    }

    $props = @()
    if ($Object.PSObject) { $props = @($Object.PSObject.Properties) }
    foreach ($name in $Names) {
        $prop = $props | Where-Object { $_.Name -ieq $name } | Select-Object -First 1
        if ($prop) {
            $value = $prop.Value
            if ($value -is [System.Collections.IEnumerable] -and -not ($value -is [string])) {
                $inner = @($value)
                if ($inner.Count -eq 1) { $value = $inner[0] }
            }
            return $value
        }
    }
    foreach ($prop in $props) {
        $found = Get-AfValue -Object $prop.Value -Names $Names -Depth ($Depth + 1)
        if ($null -ne $found) { return $found }
    }
    return $null
}

function Get-JobId {
    param($Payload)
    $id = Get-AfValue $Payload @("jobId")
    if (-not [string]::IsNullOrWhiteSpace([string] $id)) { return [string] $id }
    return $null
}

function Test-AdbReady {
    $script:AdbExe = Resolve-AdbExe
    Write-Host "Using adb: $script:AdbExe" -ForegroundColor DarkGray
    $devices = Invoke-Adb -Arguments @("devices")
    $ready = @($devices -split "`n" | Where-Object { $_ -match "`tdevice$" })
    if ($ready.Count -eq 0) {
        throw "No adb device in 'device' state. Plug in the phone and allow USB debugging."
    }
}

function Open-Compressor {
    Write-Host "Opening $Package so App Functions can index..." -ForegroundColor DarkGray
    $null = Invoke-Adb -Arguments @(
        "shell", "am", "start",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-n", "$Package/com.androidcompress.app.MainActivity"
    )
    Start-Sleep -Seconds 2
}

function Open-AppSettings {
    Write-Host "Opening Android settings for $Package. Enable Device library access -> Allow all." -ForegroundColor Yellow
    $null = Invoke-Adb -Arguments @(
        "shell", "am", "start",
        "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
        "-d", "package:$Package"
    )
}

Test-AdbReady

if ($OpenSettings) {
    Open-AppSettings
    return
}

Open-Compressor

$listed = Invoke-Adb -Arguments @("shell", "cmd", "app_function", "list-app-functions")
if ($listed -notmatch [regex]::Escape($Package)) {
    throw "Package $Package is not in 'cmd app_function list-app-functions'. Open the app once on Android 16+ and retry."
}

$caps = Invoke-AppFunction -Name "describeCapabilities"
$granted = Get-AfValue $caps @("libraryAccessGranted")
if ($null -eq $granted) {
    $defaults = Invoke-AppFunction -Name "getAppDefaults"
    $granted = Get-AfValue $defaults @("libraryAccessGranted")
}
$granted = [bool] $granted
Write-Host ("libraryAccessGranted = {0}" -f $granted)
if (-not $granted) {
    Open-AppSettings
    throw "Grant Device library access in the app Settings (Allow all), then rerun this script."
}

$importTarget = $Path
if (-not [string]::IsNullOrWhiteSpace($LocalFile)) {
    if (-not (Test-Path -LiteralPath $LocalFile)) {
        throw "LocalFile not found: $LocalFile"
    }
    $name = [System.IO.Path]::GetFileName($LocalFile)
    $remotePath = ($RemoteDir.TrimEnd("/") + "/" + $name)
    Write-Host "Pushing $LocalFile -> $remotePath"
    $null = Invoke-Adb -Arguments @("push", $LocalFile, $remotePath)
    $importTarget = $remotePath
}

if ([string]::IsNullOrWhiteSpace($importTarget)) {
    $listParams = @{
        kind  = $Kind
        limit = $Limit
    }
    if (-not [string]::IsNullOrWhiteSpace($Query)) {
        $listParams.query = $Query
    }
    $media = Invoke-AppFunction -Name "listDeviceMedia" -Parameters $listParams
    $items = @(Get-AfValue $media @("items"))
    if ($items.Count -eq 1 -and $null -eq $items[0]) { $items = @() }
    if ($items.Count -eq 0) {
        throw "listDeviceMedia returned no $Kind files. Push a clip or pass -LocalFile / -Path."
    }
    Write-Host "Found $($items.Count) item(s):"
    $i = 0
    foreach ($item in $items) {
        $i++
        $itemName = Get-AfValue $item @("displayName")
        $itemKind = Get-AfValue $item @("kind")
        $itemUri = Get-AfValue $item @("contentUri")
        Write-Host ("  {0}. {1}  {2}  {3}" -f $i, $itemName, $itemKind, $itemUri)
    }
    if ($ListOnly) { return }
    $importTarget = [string] (Get-AfValue $items[0] @("contentUri"))
    Write-Host "Importing $(Get-AfValue $items[0] @('displayName'))"
}

$imported = Invoke-AppFunction -Name "importDeviceMedia" -Parameters @{ uriOrPath = $importTarget }
$jobId = Get-JobId $imported
if ([string]::IsNullOrWhiteSpace($jobId)) {
    throw "importDeviceMedia did not return a jobId.`n$(ConvertTo-CompactJson $imported)"
}
Write-Host "jobId = $jobId" -ForegroundColor Green

$settings = @{ preset = $Preset }
if ($Engine) { $settings.engine = $Engine }
if ($Container) { $settings.container = $Container }
if ($ExtraArgs) { $settings.ffmpegExtraArgs = $ExtraArgs }
$null = Invoke-AppFunction -Name "updateJobSettings" -Parameters @{
    jobId    = $jobId
    settings = $settings
}
$preview = Invoke-AppFunction -Name "previewEncode" -Parameters @{ jobId = $jobId }
Write-Host ("preview encoder={0} estimateBytes={1}" -f (Get-AfValue $preview @("encoderLabel")), (Get-AfValue $preview @("estimateBytes")))

if ($SkipStart) {
    Write-Host "SkipStart set; job is READY. Start it from the app or rerun without -SkipStart."
    return
}

$started = Invoke-AppFunction -Name "startJob" -Parameters @{
    jobId             = $jobId
    deleteSourceAfter = $false
}
Write-Host (Get-AfValue $started @("message"))

$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds $PollSeconds
    $progress = Invoke-AppFunction -Name "getProgress" -Parameters @{ jobId = $jobId }
    $status = [string] (Get-AfValue $progress @("status"))
    Write-Host ("[{0}] {1}%  {2}" -f $status, (Get-AfValue $progress @("percent")), (Get-AfValue $progress @("message")))
    if ($status -in @("SUCCEEDED", "FAILED", "CANCELLED")) { break }
} while ([DateTime]::UtcNow -lt $deadline)

if ($status -eq "FAILED") {
    $log = Invoke-AppFunction -Name "getEncodeLog" -Parameters @{
        jobId    = $jobId
        maxChars = 4000
    }
    Write-Host (Get-AfValue $log @("text"))
    throw "Encode failed for $jobId"
}
if ($status -ne "SUCCEEDED") {
    throw "Timed out after $TimeoutSeconds s. Last status: $status"
}

$job = Invoke-AppFunction -Name "getJob" -Parameters @{ jobId = $jobId }
Write-Host ("Done. {0} -> {1} ({2})" -f (Get-AfValue $job @("displayName")), (Get-AfValue $job @("outputFolder")), (Get-AfValue $job @("status"))) -ForegroundColor Green
