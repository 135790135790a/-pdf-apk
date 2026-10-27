#Requires -Version 5.1
<#
.SYNOPSIS
    发版前置检查：确认这个 APK 就是你想发的那个。

.DESCRIPTION
    逐项核对，任何一项不符合就 exit 1（可作为发版流水线的门禁）：

      1. APK 能被 aapt2 解析（没下坏）
      2. 包名 / versionCode / versionName 与 app/build.gradle.kts 完全一致
      3. minSdk / targetSdk 与 build.gradle.kts 一致
      4. 签名证书 SHA-256 是固定的那把（防止误用别的 keystore 发版）
      5. 权限只允许白名单里的几条（前台服务 + 导入通知，不该有存储权限）
      6. 文件大小在合理范围（没漏文件、也没被打包进大资源）
      7. zipalign 对齐（apksigner verify 会一并检查）

.PARAMETER Apk
    APK 路径，通常是 app\build\outputs\apk\release\app-release.apk

.PARAMETER GradleFile
    app\build.gradle.kts 路径，默认按仓库结构自动找

.PARAMETER ExpectCertSha256
    期望的签名证书 SHA-256（小写十六进制，可带冒号）。留空则跳过这一项。

.EXAMPLE
    .\verify-release.ps1 -Apk app\build\outputs\apk\release\app-release.apk
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$GradleFile = "",
    [string]$ExpectCertSha256 = "ec13aa98eea9a8952904a39b56a69e30aa1dcada1593cd0f4c4ead7731caa2bd",
    [int]$MinSizeMb = 4,
    [int]$MaxSizeMb = 30
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($GradleFile)) {
    $GradleFile = Join-Path $repo 'app\build.gradle.kts'
}
if (-not (Test-Path -LiteralPath $GradleFile)) {
    Write-Host "[FAIL] 找不到 $GradleFile" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $Apk)) {
    Write-Host "[FAIL] 找不到 APK：$Apk" -ForegroundColor Red
    exit 1
}
$Apk = (Resolve-Path -LiteralPath $Apk).Path

# ---------------------------------------------------------------- 工具定位
function Find-BuildTools {
    param([string]$Explicit)
    $roots = @()
    if ($Explicit) { $roots += $Explicit }
    if ($env:ANDROID_HOME) { $roots += (Join-Path $env:ANDROID_HOME 'build-tools') }
    if ($env:ANDROID_SDK_ROOT) { $roots += (Join-Path $env:ANDROID_SDK_ROOT 'build-tools') }
    $roots += 'C:\Users\86139\Documents\deepseek-harness\default-workspace\_toolchain\android-sdk\build-tools'
    foreach ($r in $roots) {
        if (Test-Path -LiteralPath $r) {
            $ver = Get-ChildItem -LiteralPath $r -Directory |
                Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
                Select-Object -First 1
            if ($ver) { return $ver.FullName }
        }
    }
    return $null
}

$bt = Find-BuildTools -Explicit $BuildTools
if (-not $bt) {
    Write-Host "[FAIL] 找不到 build-tools（设 ANDROID_HOME 或用 -BuildTools 指定）" -ForegroundColor Red
    exit 1
}
$aapt2 = Join-Path $bt 'aapt2.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

$problems = New-Object System.Collections.Generic.List[string]

# ---------------------------------------------------------------- 1. badging
$badging = & $aapt2 dump badging $Apk 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] aapt2 解析失败，APK 可能损坏：" -ForegroundColor Red
    $badging | Select-Object -First 5 | ForEach-Object { Write-Host "    $_" }
    exit 1
}
$line = $badging | Where-Object { $_ -match '^package: ' } | Select-Object -First 1
if (-not $line) {
    Write-Host "[FAIL] badging 里没有 package 行" -ForegroundColor Red
    exit 1
}
function Get-Attr([string]$text, [string]$name) {
    if (-not $text) { return $null }
    # badging 有两种写法：package 行是 name='x'，sdk 行是 sdkVersion:'24'
    $m = [regex]::Match($text, $name + "='([^']*)'")
    if ($m.Success) { return $m.Groups[1].Value }
    $m = [regex]::Match($text, $name + ":'([^']*)'")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
$pkg = Get-Attr $line 'name'
$vc = Get-Attr $line 'versionCode'
$vn = Get-Attr $line 'versionName'
$minSdk = Get-Attr ($badging | Where-Object { $_ -match '^sdkVersion:' } | Select-Object -First 1) 'sdkVersion'
$tLine = $badging | Where-Object { $_ -match '^targetSdkVersion:' } | Select-Object -First 1
$targetSdk = Get-Attr $tLine 'targetSdkVersion'
$labelLine = $badging | Where-Object { $_ -match "^application-label:" } | Select-Object -First 1
$label = ($labelLine -replace "^application-label:'?", '') -replace "'$", ''

# ---------------------------------------------------------------- 2. gradle 期望值
$g = Get-Content -LiteralPath $GradleFile -Raw
$expVc = [regex]::Match($g, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$expVn = [regex]::Match($g, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$expMin = [regex]::Match($g, 'minSdk\s*=\s*(\d+)').Groups[1].Value
$expTarget = [regex]::Match($g, 'targetSdk\s*=\s*(\d+)').Groups[1].Value

if ($pkg -ne 'com.mangashelf.reader') { $problems.Add("包名是 $pkg，期望 com.mangashelf.reader") }
if ($vc -ne $expVc) { $problems.Add("versionCode 是 $vc，build.gradle.kts 里是 $expVc（忘了改？）") }
if ($vn -ne $expVn) { $problems.Add("versionName 是 $vn，build.gradle.kts 里是 $expVn") }
if ($minSdk -ne $expMin) { $problems.Add("minSdk 是 $minSdk，期望 $expMin") }
if ($targetSdk -ne $expTarget) { $problems.Add("targetSdk 是 $targetSdk，期望 $expTarget") }

# ---------------------------------------------------------------- 3. 权限
# AndroidX Core 会在 targetSdk 34 上自动声明一个包名内的 receiver 保护权限，
# 它不是运行时权限，属于框架自动生成，允许出现在白名单里的这一条。
# 另外三条是 1.5.1 起为「导入不被打断」加的前台服务权限：
# FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC 是启动前台服务的准入，
# POST_NOTIFICATIONS 只为了让导入进度通知能被看见——都不是存储权限。
$permWhitelist = @(
    "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'android.permission.POST_NOTIFICATIONS'
)
$permNames = @($badging |
    Where-Object { $_ -match "^uses-permission:" } |
    ForEach-Object { ($_ -replace "^uses-permission:\s*", '') -replace "^name='", '' -replace "'$", '' })
$permBad = @($permNames | Where-Object { $permWhitelist -notcontains $_ })
if ($permBad.Count -gt 0) {
    $problems.Add("APK 声明了 $($permBad.Count) 条不该有的权限：`n      " + ($permBad -join "`n      "))
}

# ---------------------------------------------------------------- 4. 签名
# apksigner.bat 需要 java：没设 JAVA_HOME 时自己找一下，别让用户为此改环境变量
if (-not $env:JAVA_HOME) {
    $javaCandidates = @(
        'C:\Users\86139\Documents\deepseek-harness\default-workspace\_toolchain\jdk',
        (Join-Path $env:USERPROFILE '.jdks')
    )
    foreach ($c in $javaCandidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c 'bin\java.exe'))) { $env:JAVA_HOME = $c; break }
        if ($c -and (Test-Path -LiteralPath $c)) {
            $sub = Get-ChildItem -LiteralPath $c -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
            if ($sub) { $env:JAVA_HOME = $sub.FullName; break }
        }
    }
}
$certOut = & $apksigner verify --print-certs $Apk 2>&1
if ($LASTEXITCODE -ne 0) {
    $problems.Add("apksigner verify 失败（未签名或未对齐）：" + (($certOut | Select-Object -First 3) -join ' '))
} else {
    $sha = ($certOut | Where-Object { $_ -match 'SHA-256 digest' } | Select-Object -First 1)
    $sha = ($sha -replace '^.*SHA-256 digest:\s*', '').Trim().Replace(':', '').ToLowerInvariant()
    if ($ExpectCertSha256) {
        $want = $ExpectCertSha256.Replace(':', '').ToLowerInvariant()
        if ($sha -ne $want) {
            $problems.Add("签名证书不是发版那把：`n      实际 $sha`n      期望 $want")
        }
    }
}

# ---------------------------------------------------------------- 5. 大小
$sizeMb = [math]::Round((Get-Item -LiteralPath $Apk).Length / 1MB, 2)
if ($sizeMb -lt $MinSizeMb) { $problems.Add("APK 只有 $sizeMb MB，小于下限 $MinSizeMb MB（可能漏了资源）") }
if ($sizeMb -gt $MaxSizeMb) { $problems.Add("APK 有 $sizeMb MB，超过上限 $MaxSizeMb MB") }

# ---------------------------------------------------------------- 6. 报告
$hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
Write-Host ""
Write-Host "APK      : $Apk" -ForegroundColor Cyan
Write-Host ("{0,-11}: {1} ({2})" -f '包名', $pkg, $label)
Write-Host ("{0,-11}: {1}" -f '版本', "$vn (versionCode $vc)")
Write-Host ("{0,-11}: {1} / {2}" -f 'min/Target', $minSdk, $targetSdk)
Write-Host ("{0,-11}: {1} MB" -f '大小', $sizeMb)
Write-Host ("{0,-11}: {1}" -f 'SHA-256', $hash)
if ($sha) { Write-Host ("{0,-11}: {1}" -f '证书 SHA-256', $sha) }
Write-Host ("{0,-11}: {1}" -f '权限', $permNames.Count)
Write-Host ""

if ($problems.Count -gt 0) {
    Write-Host "[FAIL] $($problems.Count) 项不符合：" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    exit 1
}
Write-Host "[OK] 全部检查通过，可以发版" -ForegroundColor Green
exit 0
