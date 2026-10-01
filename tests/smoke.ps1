#requires -Version 5.1
# End-to-end test against a local web server: build 1.0.0, install it through `irm | iex`,
# build 1.1.0, update, uninstall. Touches only a temp dir, a temp registry dir, the current
# user's Desktop/Start menu (shortcuts are removed again) and HKCU (Uninstall key + PATH,
# both restored). Run:  powershell -File tests\smoke.ps1
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-smoke-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
New-Item -ItemType Directory -Force -Path "$root\src", "$root\site", "$root\assets" | Out-Null
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
function Build([string]$ver) {
    & "$repo\build.ps1" -Config "$root\installer.json" -Version $ver -OutDir "$root\site" -AssetsDir "$root\assets" `
        -BaseUrl "$base/site" -AssetBaseUrl "$base/assets" -Notes "smoke $ver" | Out-Null
}

# tiny static server (separate process so it can be killed cleanly)
$server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File',"$PSScriptRoot\serve.ps1",'-Root',"$root",'-Port',$port
$pathBefore = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
$installDir = "$root\inst dir"
$regDir = "$root\registry"
$env:INSTALL_DIR = $installDir
$env:INSTALL_REGISTRY_DIR = $regDir
try {
    Start-Sleep -Seconds 2
    Copy-Item "$env:windir\System32\where.exe" "$root\src\app.exe"
    [IO.File]::WriteAllText("$root\src\tool.jar", "not really a jar v1")
    @{
        id = 'vi-smoke'; name = 'ViSmoke'; publisher = 'Test'; description = 'smoke'; version = '1.0.0'
        repo = 'o/r'; tagFormat = '{version}'
        windows = @{ installDir = "$installDir"; binaries = @(
            @{ name = 'app'; source = 'src/app.exe'; addToPath = $true; shortcuts = @{ desktop = $true; startMenu = $true; label = 'ViSmoke' } },
            @{ name = 'tool'; source = 'src/tool.jar'; javaMin = 17; shortcuts = @{ desktop = $true; label = 'ViSmoke Tool' } }
        ) }
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\installer.json"

    Write-Host "build 1.0.0"; Build '1.0.0'
    $m = Get-Content -Raw "$root\site\manifest.json" | ConvertFrom-Json
    Check ($m.latest -eq '1.0.0') 'manifest latest = 1.0.0'
    Check ((Test-Path "$root\site\README.md") -and (Get-Content -Raw "$root\site\README.md") -match [regex]::Escape("irm $base/site/install.ps1 | iex")) 'README embeds the one-liner'

    Write-Host "install via irm | iex"
    $out = powershell -NoProfile -ExecutionPolicy Bypass -Command "irm $base/site/install.ps1 | iex" 2>&1
    $out | ForEach-Object { Write-Host "    | $_" }
    Check (Test-Path "$installDir\app.exe") 'app.exe installed'
    Check (Test-Path "$installDir\tool.jar") 'tool.jar installed'
    Check ((Get-Content -Raw "$installDir\tool.cmd") -match 'java -jar') 'jar launcher written'
    Check ((Test-Path "$installDir\uninstall.cmd") -and (Test-Path "$installDir\update.cmd")) 'uninstall/update present'
    Check (Test-Path "$regDir\vi-smoke.json") 'registry manifest written'
    Check (Test-Path "$installDir\install-manifest.json") 'install-manifest.json written'
    $desktop = [Environment]::GetFolderPath('Desktop')
    Check ((Test-Path "$desktop\ViSmoke.lnk") -and (Test-Path "$desktop\ViSmoke Tool.lnk")) 'desktop shortcuts'
    Check (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\vi-smoke") 'Apps & features entry'
    Check (([Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')) -like "*$installDir*") 'PATH entry added'

    Write-Host "reinstall is a no-op"
    $out = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-String
    Check ($out -match 'already installed') 'second install detects existing version'

    Write-Host "build 1.1.0, update"
    Copy-Item -Force "$env:windir\System32\where.exe" "$root\src\app.exe"
    [IO.File]::AppendAllText("$root\src\app.exe", "x")
    Build '1.1.0'
    $m = Get-Content -Raw "$root\site\manifest.json" | ConvertFrom-Json
    Check ($m.latest -eq '1.1.0' -and @($m.versions).Count -eq 2) 'manifest keeps both versions in history'
    $out = & "$installDir\update.cmd" -Check 2>&1 | Out-String
    Check ($out -match '1\.0\.0 -> 1\.1\.0') 'update -Check reports new version'
    $out = & "$installDir\update.cmd" -Yes 2>&1 | Out-String
    $out -split "`n" | ForEach-Object { Write-Host "    | $_" }
    $rec = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($rec.version -eq '1.1.0') 'record now at 1.1.0'
    Check ((Get-Item "$installDir\app.exe").Length -eq ((Get-Item "$env:windir\System32\where.exe").Length + 1)) 'app.exe replaced'
    $out = & "$installDir\update.cmd" -Yes 2>&1 | Out-String
    Check ($out -match 'up to date') 'update again says up to date'

    Write-Host "uninstall"
    $out = & "$installDir\uninstall.cmd" -List 2>&1 | Out-String
    Check ($out -match 'vi-smoke') 'uninstall -List finds the install'
    & "$installDir\uninstall.cmd" -Yes 2>&1 | ForEach-Object { Write-Host "    | $_" }
    Start-Sleep -Seconds 5
    Check (-not (Test-Path $installDir)) 'install dir gone'
    Check (-not (Test-Path "$regDir\vi-smoke.json")) 'registry manifest gone'
    Check (-not ((Test-Path "$desktop\ViSmoke.lnk") -or (Test-Path "$desktop\ViSmoke Tool.lnk"))) 'shortcuts gone'
    Check (-not (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\vi-smoke")) 'Apps entry gone'
    $pathAfter = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
    Check ($pathAfter -eq $pathBefore) 'user PATH restored exactly'
} finally {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item Env:INSTALL_DIR, Env:INSTALL_REGISTRY_DIR -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
if ($fails -gt 0) { Write-Host "$fails check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "all checks passed" -ForegroundColor Green
