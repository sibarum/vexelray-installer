#requires -Version 5.1
# End-to-end test of a suite installer against a local web server: three members, one published twice
# over, one never published. Installs through `irm | iex` and from a file, checks INSTALL_ONLY, that
# INSTALL_DIR is not passed on, that a tampered member installer is refused, then uninstalls. Touches only
# a temp dir, a temp registry dir, and HKCU's Uninstall keys and PATH (both restored). Run:
#   powershell -File tests\suite-smoke.ps1
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-suite-smoke-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
$regDir = "$root\registry"
New-Item -ItemType Directory -Force -Path "$root\src" | Out-Null
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
$cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $cli) {
    Write-Host "building the generator (mvn package)"
    & mvn -q -f "$repo\pom.xml" -pl installer-core -DskipTests package
    $cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" | Select-Object -First 1
}
$members = 'vi-sa', 'vi-sb', 'vi-sc'   # vi-sc is never published
function Gen([string[]]$genArgs) {
    & java -jar $cli.FullName @genArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generator failed: $genArgs" }
}
# Encoded, because Windows PowerShell hands `-Command "irm ... | iex"` to iex a line at a time, which a
# person typing the line never sees.
function Run([string]$command) {
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("& { $command } *>&1"))
    $o = powershell -NoProfile -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $enc 2>&1 | Out-String
    $o -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
    $o
}
function Rec([string]$id) {
    $p = "$regDir\$id.json"
    if (Test-Path -LiteralPath $p) { Get-Content -Raw -LiteralPath $p | ConvertFrom-Json } else { $null }
}

$server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\serve.ps1",'-Root',"$root",'-Port',$port
$pathBefore = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
$env:INSTALL_REGISTRY_DIR = $regDir
try {
    Start-Sleep -Seconds 2
    Copy-Item "$env:windir\System32\where.exe" "$root\src\app.exe"
    $suiteMembers = @()
    foreach ($id in $members) {
        @{
            id = $id; name = $id.ToUpperInvariant(); description = "member $id"; version = '1.0.0'; repo = "o/$id"; tagFormat = '{version}'
            windows = @{ installDir = "$root\inst\$id"; binaries = @(@{ name = $id; source = 'src/app.exe'; file = "$id.exe" }) }
        } | ConvertTo-Json -Depth 6 | Set-Content -Encoding ASCII "$root\$id.json"
        $suiteMembers += @{ config = "$id.json"; manifestUrl = "$base/site-$id/manifest.json" }
    }
    @{ id = 'vi-suite'; name = 'ViSuite'; description = 'smoke'; repo = 'o/suite'; members = $suiteMembers } |
        ConvertTo-Json -Depth 6 | Set-Content -Encoding ASCII "$root\suite.json"

    Write-Host "generate: two members published, the suite"
    foreach ($id in 'vi-sa', 'vi-sb') {
        Gen @('--config', "$root\$id.json", '--version', '1.0.0', '--out', "$root\site-$id", '--assets', "$root\assets-$id",
              '--base-url', "$base/site-$id", '--asset-base-url', "$base/assets-$id")
    }
    Gen @('--suite', "$root\suite.json", '--out', "$root\suite", '--base-url', "$base/suite")
    Check ((Get-Content -Raw "$root\suite\README.md") -match [regex]::Escape("irm $base/suite/install.ps1 | iex")) 'suite README carries the one-liner'

    Write-Host "INSTALL_ONLY with an unknown id refuses before installing anything"
    $o = Run "`$env:INSTALL_ONLY = 'vi-sa,nope'; irm $base/suite/install.ps1 | iex"
    Check ($o -match 'nope' -and -not (Rec 'vi-sa')) 'refused, nothing installed'

    Write-Host "INSTALL_ONLY=vi-sb, with an INSTALL_DIR that must not be passed on"
    $o = Run "`$env:INSTALL_ONLY = 'vi-sb'; `$env:INSTALL_DIR = '$root\wrong'; irm $base/suite/install.ps1 | iex"
    Check ((Rec 'vi-sb') -and -not (Rec 'vi-sa')) 'only vi-sb installed'
    Check ((Test-Path "$root\inst\vi-sb\vi-sb.exe") -and -not (Test-Path "$root\wrong")) 'INSTALL_DIR was not passed on'
    Check (($o -replace '\s+', ' ') -match 'INSTALL_DIR applies to one application') 'and the user was told'

    Write-Host "the whole suite, from a file (so a member's failure must not end the run)"
    Invoke-WebRequest -UseBasicParsing "$base/suite/install.ps1" -OutFile "$root\suite-install.ps1"
    $o = Run "& '$root\suite-install.ps1'; 'EXIT=' + [int]`$LASTEXITCODE"
    Check ((Rec 'vi-sa').version -eq '1.0.0' -and (Rec 'vi-sb').version -eq '1.0.0') 'both published members installed'
    Check ($o -match 'VI-SC\s+not published yet') 'the unpublished member is skipped and reported'
    Check ($o -match 'already installed') 'the installed member was left as it was'
    Check ($o -match 'EXIT=0') 'an unpublished member is not a failure'

    Write-Host "an update to one member arrives through the same line"
    [IO.File]::AppendAllText("$root\src\app.exe", "x")
    Gen @('--config', "$root\vi-sa.json", '--version', '1.1.0', '--out', "$root\site-vi-sa", '--assets', "$root\assets-vi-sa",
          '--base-url', "$base/site-vi-sa", '--asset-base-url', "$base/assets-vi-sa")
    $o = Run "irm $base/suite/install.ps1 | iex"
    Check ((Rec 'vi-sa').version -eq '1.1.0') 'vi-sa updated to 1.1.0'

    Write-Host "a member installer that does not match its manifest is not run"
    Add-Content -LiteralPath "$root\site-vi-sb\install.ps1" -Value "# tampered"
    $env:INSTALL_FORCE = '1'
    $o = Run "& '$root\suite-install.ps1'; 'EXIT=' + [int]`$LASTEXITCODE"
    Remove-Item Env:INSTALL_FORCE
    Check ($o -match 'does not match the SHA-256' -and $o -match 'VI-SB\s+FAILED') 'refused and reported'
    Check ($o -match 'EXIT=1') 'and the run fails'

    Write-Host "uninstall"
    foreach ($id in 'vi-sa', 'vi-sb') { & "$root\inst\$id\uninstall.cmd" -Id $id -Yes 2>&1 | Out-Null }
    Start-Sleep -Seconds 6
    foreach ($id in 'vi-sa', 'vi-sb') {
        Check (-not (Test-Path "$root\inst\$id") -and -not (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$id")) "$id removed"
    }
    $pathAfter = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
    Check ($pathAfter -eq $pathBefore) 'user PATH unchanged'
} finally {
    foreach ($id in $members) { Remove-Item -Recurse -Force "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$id" -ErrorAction SilentlyContinue }
    if ($server) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item Env:INSTALL_REGISTRY_DIR -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
if ($fails -gt 0) { Write-Host "$fails check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "all checks passed" -ForegroundColor Green
