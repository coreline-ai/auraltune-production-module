param(
    [switch]$SkipUnitTests,
    [switch]$SkipReleaseBuild,
    [switch]$RunConnectedTests,
    [string]$AndroidSerial
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message"
}

function Invoke-Checked([string]$Command, [string[]]$Arguments) {
    Write-Host "$Command $($Arguments -join ' ')"
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Command"
    }
}

function Get-RepoRoot {
    $root = & git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $root) {
        throw "Run this script from inside the AuralTune git repository."
    }
    return (Resolve-Path $root.Trim()).Path
}

function Get-AndroidSdkDir([string]$RepoRoot) {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }

    $localProperties = Join-Path $RepoRoot "local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
        if ($sdkLine) {
            $candidates += (($sdkLine -replace "^sdk.dir=", "") -replace "/", "\")
        }
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw "Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or local.properties sdk.dir."
}

function Index-OfBytes([byte[]]$Haystack, [byte[]]$Needle) {
    if ($Needle.Length -eq 0 -or $Haystack.Length -lt $Needle.Length) {
        return -1
    }

    for ($i = 0; $i -le $Haystack.Length - $Needle.Length; $i++) {
        $matched = $true
        for ($j = 0; $j -lt $Needle.Length; $j++) {
            if ($Haystack[$i + $j] -ne $Needle[$j]) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            return $i
        }
    }

    return -1
}

function Test-ReleaseDebugMarkers([string]$ApkPath) {
    if (-not (Test-Path $ApkPath)) {
        throw "Release APK not found: $ApkPath"
    }

    $markers = @(
        "AudioFxSessionProbe",
        "DebugSupport",
        "firstPlayableUri",
        "debug firstAudioUri",
        "EqState",
        "외부앱 신호 측정",
        "pushToEngine",
        "updateManualEq failed"
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $ApkPath))
    try {
        $hits = New-Object System.Collections.Generic.List[string]
        foreach ($entry in $zip.Entries | Where-Object { $_.Name -like "classes*.dex" }) {
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $bytes = $memory.ToArray()
            } finally {
                $stream.Dispose()
            }

            foreach ($marker in $markers) {
                $needle = [System.Text.Encoding]::UTF8.GetBytes($marker)
                if ((Index-OfBytes $bytes $needle) -ge 0) {
                    $hits.Add("$($entry.Name): $marker")
                }
            }
        }

        if ($hits.Count -gt 0) {
            $hits | Sort-Object -Unique | ForEach-Object { Write-Host $_ }
            throw "Release APK contains debug markers."
        }
    } finally {
        $zip.Dispose()
    }
}

function Test-NativeAlignment([string]$RepoRoot, [string]$AndroidSdkDir) {
    $readelf = Join-Path $AndroidSdkDir "ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
    if (-not (Test-Path $readelf)) {
        throw "llvm-readelf.exe not found: $readelf"
    }

    $searchRoots = @(
        (Join-Path $RepoRoot "app\build\intermediates\merged_native_libs\release"),
        (Join-Path $RepoRoot "audio-engine\build\intermediates\cxx\RelWithDebInfo")
    )
    $so = Get-ChildItem -Path $searchRoots -Recurse -Filter "libauraltune_audio.so" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "arm64-v8a" } |
        Select-Object -First 1

    if (-not $so) {
        throw "release arm64-v8a libauraltune_audio.so not found. Run :app:assembleRelease first."
    }

    $aligns = & $readelf -lW $so.FullName | Select-String "LOAD" | ForEach-Object { ($_ -split "\s+")[-1] }
    $bad = $aligns | Where-Object { [Convert]::ToInt64($_, 16) -lt 16384 }
    if ($bad) {
        throw "LOAD alignment below 0x4000: $($bad -join ', ')"
    }

    Write-Host "OK: $($so.FullName) LOAD alignments $($aligns -join ', ')"
}

$repoRoot = Get-RepoRoot
Set-Location $repoRoot
$gradle = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradle)) {
    $gradle = Join-Path $repoRoot "gradlew"
}

$gradleBaseArgs = @(
    "--no-daemon",
    "--no-build-cache",
    "-Dkotlin.compiler.execution.strategy=in-process"
)

if (-not $SkipUnitTests) {
    Write-Step "Run JVM unit tests"
    Invoke-Checked $gradle ($gradleBaseArgs + @(
        ":audio-engine:testDebugUnitTest",
        ":autoeq-data:testDebugUnitTest",
        ":opra-data:testDebugUnitTest",
        ":app:testDebugUnitTest"
    ))
}

if (-not $SkipReleaseBuild) {
    Write-Step "Assemble release APK"
    Invoke-Checked $gradle ($gradleBaseArgs + @(":app:assembleRelease"))
}

Write-Step "Scan release APK for debug markers"
$apkPath = Join-Path $repoRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
Test-ReleaseDebugMarkers $apkPath
Write-Host "OK: no release debug-marker hits"

Write-Step "Verify 16 KB native alignment"
$sdkDir = Get-AndroidSdkDir $repoRoot
Test-NativeAlignment $repoRoot $sdkDir

if ($RunConnectedTests) {
    Write-Step "Run connected Android tests"
    if ($AndroidSerial) {
        $env:ANDROID_SERIAL = $AndroidSerial
        Write-Host "ANDROID_SERIAL=$AndroidSerial"
    }
    Invoke-Checked $gradle ($gradleBaseArgs + @(":app:connectedDebugAndroidTest"))
}

Write-Step "Manual release gates still required"
Write-Host "- Physical device smoke: confirm correction applies on every output route (no route-based clear)"
Write-Host "- Final commit/release tag after review"

Write-Host ""
Write-Host "Release readiness local gates: PASS"
