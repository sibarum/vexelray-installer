#requires -Version 5.1
# End-to-end test against a local web server: build 1.0.0, install it through `irm | iex`,
# build 1.1.0, update, uninstall. Touches only a temp dir, a temp registry dir, the current
# user's Desktop/Start menu (shortcuts are removed again) and HKCU (Uninstall key, PATH and
# .vismoke* file types, all restored). Run:  powershell -File tests\smoke.ps1
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-smoke-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
New-Item -ItemType Directory -Force -Path "$root\src", "$root\site", "$root\assets" | Out-Null
$fails = 0
function ClassKey([string]$sub) {
    $k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("Software\Classes\$sub")
    if ($k) { $k.Close(); return $true }
    return $false
}
function ClassValue([string]$sub, [string]$name = '') {
    $k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("Software\Classes\$sub")
    if (-not $k) { return $null }
    try { $k.GetValue($name) } finally { $k.Close() }
}
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
$cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $cli) {
    Write-Host "building the generator (mvn package)"
    & mvn -q -f "$repo\pom.xml" -pl installer-core -DskipTests package
    $cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" | Select-Object -First 1
}
function Build([string]$ver) {
    & java -jar $cli.FullName --config "$root\installer.json" --version $ver --out "$root\site" --assets "$root\assets" `
        --base-url "$base/site" --asset-base-url "$base/assets" --notes "smoke $ver" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generator failed" }
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
    Add-Type -AssemblyName System.Drawing
    [IO.File]::WriteAllText("$root\src\NOTICES.txt", "third-party notices")
    $bmp = New-Object System.Drawing.Bitmap 32, 32; $bmp.Save("$root\src\doc.png", [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
    function SmokeConfig([bool]$toolAssoc, $system = @{ windows = '10.0.10240'; memoryMb = 256 }) {
        $tool = @{ name = 'tool'; source = 'src/tool.jar'; javaMin = 17; shortcuts = @{ desktop = $true; label = 'ViSmoke Tool' } }
        if ($toolAssoc) { $tool.fileAssociations = @(@{ extension = 'vismokej' }) }
        $taken = @{ extension = '.vismoketaken' }
        if ($toolAssoc) { $taken.icon = 'src/doc.png' }
        @{
            id = 'vi-smoke'; name = 'ViSmoke'; publisher = 'Test'; description = 'smoke'; version = '1.0.0'
            repo = 'o/r'; tagFormat = '{version}'
            windows = @{ installDir = "$installDir"; system = $system; binaries = @(
                @{ name = 'app'; source = 'src/app.exe'; addToPath = $true; shortcuts = @{ desktop = $true; startMenu = $true; label = 'ViSmoke' }
                   fileAssociations = @(@{ extension = '.vismoke'; description = 'ViSmoke Project'; contentType = 'text/x-vismoke' }, $taken) },
                $tool,
                @{ name = 'notices'; source = 'src/NOTICES.txt' }
            ) }
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\installer.json"
    }
    # ---- system requirements ---------------------------------------------------------------
    Write-Host "requirements not met: nothing is installed"
    SmokeConfig $true @{ memoryMb = 99999999 }
    Build '1.0.0'
    $out = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-String
    $out -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
    Check ($out -match 'does not meet the system requirements') 'install refuses when a requirement is not met'
    Check (-not (Test-Path "$installDir\app.exe") -and -not (Test-Path "$regDir\vi-smoke.json")) 'nothing was installed by the refused install'
    $rep = Get-ChildItem "$root\diagnostics" -Filter 'vi-smoke-*.txt' -ErrorAction SilentlyContinue | Select-Object -First 1
    Check ($rep -and (Get-Content -Raw $rep.FullName) -match '\[FAIL\] 99999999 MB of memory') 'the refusal leaves a report naming what is not met'
    Check ($rep -and $out -match [regex]::Escape($rep.FullName)) 'and says where the report is'

    Write-Host "a failed install writes a report"
    SmokeConfig $true
    Build '1.0.0'
    [IO.File]::AppendAllText("$root\assets\1.0.0\app.exe", 'tampered')
    $out = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-String
    Check ($out -match 'Checksum mismatch') 'a tampered download fails the install'
    $rp = if ($out -match 'Diagnostic report: (\S[^\r\n]*\.txt)') { $Matches[1].Trim() } else { $null }
    Check ($rp -and (Get-Content -Raw $rp) -match '== Install error\s+Checksum mismatch') 'the report written for the failure holds the error'

    SmokeConfig $true
    # an extension another program already owns: we may only join its Open with list
    New-Item -Path 'HKCU:\Software\Classes\.vismoketaken' -Force | Out-Null
    Set-Item -Path 'HKCU:\Software\Classes\.vismoketaken' -Value 'ViSmoke.Other'

    Write-Host "build 1.0.0"; Build '1.0.0'
    $m = Get-Content -Raw "$root\site\manifest.json" | ConvertFrom-Json
    Check ($m.latest -eq '1.0.0') 'manifest latest = 1.0.0'
    Check ((Test-Path "$root\site\README.md") -and (Get-Content -Raw "$root\site\README.md") -match [regex]::Escape("irm $base/site/install.ps1 | iex")) 'README embeds the one-liner'

    Write-Host "install via irm | iex"
    # Encoded, because Windows PowerShell hands `-Command "irm ... | iex"` to iex a line at a time, which a
    # person typing the line never sees.
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("irm $base/site/install.ps1 | iex"))
    $out = powershell -NoProfile -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $enc 2>&1
    $out | ForEach-Object { Write-Host "    | $_" }
    Check (Test-Path "$installDir\app.exe") 'app.exe installed'
    Check (Test-Path "$installDir\tool.jar") 'tool.jar installed'
    Check ((Get-Content -Raw "$installDir\tool.cmd") -match 'java -jar') 'jar launcher written'
    Check ((Test-Path "$installDir\uninstall.cmd") -and (Test-Path "$installDir\update.cmd")) 'uninstall/update present'
    Check ($out -match 'Checking system requirements' -and (Test-Path "$installDir\diagnose.cmd")) 'requirements checked, diagnose.cmd present'
    $dout = & "$installDir\diagnose.cmd" -Out "$root\report.txt" 2>&1 | Out-String
    $rtxt = Get-Content -Raw "$root\report.txt" -ErrorAction SilentlyContinue
    Check ($rtxt -match 'ViSmoke - diagnostic report' -and $rtxt -match '== DXGI / Direct3D 12' -and $rtxt -match '== Vulkan') 'diagnose.cmd writes a full report'
    Check ($rtxt -match 'vi-smoke\s+1\.0\.0' -and $rtxt -match '\[ok  \] 256 MB of memory') 'the report lists the install and its requirements'
    Check ($rtxt -and -not $rtxt.Contains($env:USERPROFILE)) 'the report leaves out the user profile path'
    Check (Test-Path "$regDir\vi-smoke.json") 'registry manifest written'
    Check (Test-Path "$installDir\install-manifest.json") 'install-manifest.json written'
    $rec0 = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($rec0.commands.app.path -eq "$installDir\app.exe") 'record lists native command path'
    Check ((Get-Content -Raw "$installDir\NOTICES.txt") -eq 'third-party notices') 'a file is placed beside the binaries'
    Check (-not $rec0.commands.notices -and @($rec0.files | Where-Object { $_.path -eq "$installDir\NOTICES.txt" }).Count -eq 1) 'a file is recorded but is not a command'
    Check ($rec0.commands.tool.path -eq "$installDir\tool.cmd") 'record lists jar launcher as command path'
    $desktop = [Environment]::GetFolderPath('Desktop')
    Check ((Test-Path "$desktop\ViSmoke.lnk") -and (Test-Path "$desktop\ViSmoke Tool.lnk")) 'desktop shortcuts'
    Check (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\vi-smoke") 'Apps & features entry'
    Check (([Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')) -like "*$installDir*") 'PATH entry added'
    Check ((ClassValue 'vi-smoke.vismoke\shell\open\command') -eq "`"$installDir\app.exe`" `"%1`"") 'ProgID open command runs the exe with the file'
    Check ((ClassValue 'vi-smoke.vismoke\DefaultIcon') -eq "$installDir\app.exe,0") 'ProgID uses the exe icon'
    Check ((ClassValue 'vi-smoke.vismoketaken\DefaultIcon') -eq "$installDir\app-doc.ico,0" -and (Test-Path "$installDir\app-doc.ico")) 'a file type with an icon of its own uses it'
    Check ((ClassValue '.vismoke') -eq 'vi-smoke.vismoke' -and (ClassValue '.vismoke' 'Content Type') -eq 'text/x-vismoke') 'unclaimed extension becomes ours, with its content type'
    Check ($null -ne (ClassValue '.vismoke\OpenWithProgids' 'vi-smoke.vismoke')) 'listed under Open with'
    Check ((ClassValue '.vismoketaken') -eq 'ViSmoke.Other' -and $null -ne (ClassValue '.vismoketaken\OpenWithProgids' 'vi-smoke.vismoketaken')) 'claimed extension keeps its default, we only join Open with'
    Check ((ClassValue 'vi-smoke.vismokej\shell\open\command') -match 'tool\.(cmd|jar)" "%1"$') 'jar association opens through javaw or the launcher'
    $fa = @($rec0.fileAssociations)
    Check ($fa.Count -eq 3 -and ($fa | Where-Object { $_.extension -eq '.vismoke' }).isDefault -and -not ($fa | Where-Object { $_.extension -eq '.vismoketaken' }).isDefault) 'record lists associations and which are defaults'

    Write-Host "reinstall is a no-op"
    $out = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-String
    Check ($out -match 'already installed') 'second install detects existing version'

    Write-Host "build 1.1.0, update"
    Copy-Item -Force "$env:windir\System32\where.exe" "$root\src\app.exe"
    [IO.File]::AppendAllText("$root\src\app.exe", "x")
    SmokeConfig $false
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
    Check (-not (ClassKey 'vi-smoke.vismokej') -and -not (ClassKey '.vismokej')) 'an association dropped by the update is unregistered'
    Check ((ClassValue '.vismoke') -eq 'vi-smoke.vismoke') 'kept association is still the default after the update'
    Check ((ClassValue 'vi-smoke.vismoketaken\DefaultIcon') -eq "$installDir\app.exe,0" -and -not (Test-Path "$installDir\app-doc.ico")) 'a file-type icon dropped by the update is removed'
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
    Check (-not (ClassKey 'vi-smoke.vismoke') -and -not (ClassKey '.vismoke') -and -not (ClassKey 'vi-smoke.vismoketaken')) 'our file types are gone'
    Check ((ClassValue '.vismoketaken') -eq 'ViSmoke.Other' -and -not (ClassKey '.vismoketaken\OpenWithProgids')) "another program's extension is left as it was"

    # ---- opt-outs outlive the environment that set them --------------------------------------
    Write-Host "opt-outs kept across an update"
    $env:INSTALL_VERSION = '1.0.0'; $env:INSTALL_NO_ASSOCIATIONS = '1'; $env:INSTALL_NO_SHORTCUTS = '1'
    powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-Null
    Remove-Item Env:INSTALL_VERSION, Env:INSTALL_NO_ASSOCIATIONS, Env:INSTALL_NO_SHORTCUTS
    $recO = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($recO.options.noAssociations -and $recO.options.noShortcuts -and -not $recO.options.noPath) 'record stores the opt-outs'
    & "$installDir\update.cmd" -Yes 2>&1 | Out-Null
    $recO = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($recO.version -eq '1.1.0') 'opted-out install updated'
    Check (-not (ClassKey 'vi-smoke.vismoke') -and -not (ClassKey '.vismoke') -and @($recO.fileAssociations | Where-Object { $_ }).Count -eq 0) 'update registers no file types the user declined'
    Check (-not (Test-Path "$desktop\ViSmoke.lnk") -and @($recO.shortcuts | Where-Object { $_ }).Count -eq 0) 'update creates no shortcuts the user declined'
    Check ((([Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')) -like "*$installDir*")) 'what was not declined is still done'
    $env:INSTALL_NO_ASSOCIATIONS = '0'; $env:INSTALL_FORCE = '1'
    powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-Null
    Remove-Item Env:INSTALL_NO_ASSOCIATIONS, Env:INSTALL_FORCE
    $recO = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ((ClassValue '.vismoke') -eq 'vi-smoke.vismoke' -and -not $recO.options.noAssociations) 'INSTALL_NO_ASSOCIATIONS=0 opts back in'
    Check (-not (Test-Path "$desktop\ViSmoke.lnk") -and $recO.options.noShortcuts) 'the other opt-out still stands'
    & "$installDir\uninstall.cmd" -Yes 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Check (-not (Test-Path $installDir) -and -not (ClassKey '.vismoke')) 'opted-out install uninstalled'
    Check (([Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')) -eq $pathBefore) 'user PATH restored again'

    # ---- the install is a transaction ------------------------------------------------------
    function Inst([hashtable]$envs) {
        foreach ($k in $envs.Keys) { [Environment]::SetEnvironmentVariable($k, $envs[$k], 'Process') }
        $o = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site\install.ps1" 2>&1 | Out-String
        $script:instExit = $LASTEXITCODE
        foreach ($k in $envs.Keys) { [Environment]::SetEnvironmentVariable($k, $null, 'Process') }
        $o -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
        $o
    }
    function NoLeftovers { -not (Test-Path "$regDir\vi-smoke.journal") -and @(Get-ChildItem $installDir -Force -Filter '.vi-backup-*' -ErrorAction SilentlyContinue).Count -eq 0 }
    $where = (Get-Item "$env:windir\System32\where.exe").Length

    Write-Host "a fresh install that fails before its commit leaves nothing behind"
    $out = Inst @{ INSTALL_VERSION = '1.0.0'; INSTALL_TEST_FAULT = 'throw:commit' }
    Check ($out -match 'Test fault' -and $out -match 'Nothing was left installed') 'the failure says nothing was left'
    Check (-not (Test-Path $installDir) -and -not (Test-Path "$regDir\vi-smoke.json") -and -not (Test-Path "$regDir\vi-smoke.home")) 'no files, folder or record'
    Check (-not (Test-Path "$desktop\ViSmoke.lnk") -and -not (ClassKey '.vismoke') -and -not (ClassKey 'vi-smoke.vismoke')) 'no shortcuts or file types'
    Check (([Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')) -eq $pathBefore) 'PATH as it was'
    Check (-not (Test-Path "$regDir\vi-smoke.journal")) 'journal gone'

    Write-Host "an update that fails before its commit puts the last version back"
    $out = Inst @{ INSTALL_VERSION = '1.0.0' }
    $sha0 = (Get-FileHash "$installDir\app.exe").Hash
    $out = Inst @{ INSTALL_TEST_FAULT = 'throw:commit' }
    $recT = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($out -match '1\.0\.0 was put back') 'the failure says 1.0.0 was put back'
    Check ($recT.version -eq '1.0.0' -and (Get-FileHash "$installDir\app.exe").Hash -eq $sha0) 'record and app.exe are 1.0.0 again'
    Check ((Test-Path "$installDir\app-doc.ico") -and (ClassKey 'vi-smoke.vismokej')) 'what 1.1.0 drops is still there'
    Check ((Test-Path "$desktop\ViSmoke.lnk") -and (NoLeftovers)) 'shortcuts kept, no journal or backup left'

    Write-Host "an update killed halfway is rolled back by the next run, which then installs"
    $out = Inst @{ INSTALL_TEST_FAULT = 'exit:4' }
    Check ($instExit -eq 9 -and (Test-Path "$regDir\vi-smoke.journal")) 'the killed run left its journal'
    $out = Inst @{}
    $recT = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($out -match 'did not finish; putting back') 'the next run says it is putting things back'
    Check ($recT.version -eq '1.1.0' -and (Get-Item "$installDir\app.exe").Length -eq $where + 1 -and (NoLeftovers)) 'and then installs 1.1.0 cleanly'

    Write-Host "a file in use: refused and rolled back, or closed with INSTALL_CLOSE_APPS"
    Copy-Item -Force "$env:windir\System32\PING.EXE" "$root\src\app.exe"
    [IO.File]::WriteAllText("$root\src\tool.jar", "not really a jar v3")
    SmokeConfig $false
    Build '1.2.0'
    $out = Inst @{}
    Check ((Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json).version -eq '1.2.0') '1.2.0 installed'
    [IO.File]::WriteAllText("$root\src\tool.jar", "not really a jar v4")
    Build '1.3.0'
    $held = [IO.File]::Open("$installDir\tool.jar", 'Open', 'Read', 'Read')
    try { $out = Inst @{} } finally { $held.Dispose() }
    Check ($out -match 'Could not replace .*tool\.jar' -and $out -match '1\.2\.0 was put back') 'a held jar fails the update, which is rolled back'
    Check ((Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json).version -eq '1.2.0' -and (Get-Content -Raw "$installDir\tool.jar") -eq 'not really a jar v3' -and (NoLeftovers)) 'still 1.2.0, nothing left over'
    $holder = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', "`$f = [IO.File]::Open('$installDir\tool.jar', 'Open', 'Read', 'Read'); Start-Sleep 120"
    Start-Sleep -Seconds 2
    $out = Inst @{ INSTALL_CLOSE_APPS = '1' }
    Check ($out -match 'Closing powershell\.exe' -and $holder.HasExited) 'INSTALL_CLOSE_APPS closes the process holding it'
    Check ((Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json).version -eq '1.3.0' -and (Get-Content -Raw "$installDir\tool.jar") -eq 'not really a jar v4') 'and 1.3.0 is installed'

    Write-Host "a running .exe is replaced without closing it"
    $ping = Start-Process "$installDir\app.exe" -PassThru -WindowStyle Hidden -ArgumentList '-n', '120', '127.0.0.1'
    Start-Sleep -Seconds 1
    [IO.File]::AppendAllText("$root\src\app.exe", 'x')
    Build '1.4.0'
    $out = Inst @{}
    Check ((Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json).version -eq '1.4.0' -and -not $ping.HasExited) '1.4.0 installed while app.exe runs'
    Check ((Get-Item "$installDir\app.exe").Length -eq (Get-Item "$root\src\app.exe").Length) 'app.exe is the new one'
    Check (@(Get-ChildItem $installDir -Force -Filter '.vi-backup-*').Count -eq 1) 'the running old one waits in the backup'
    Stop-Process -Id $ping.Id -Force; Start-Sleep -Milliseconds 500
    $out = Inst @{}
    Check ($out -match 'already installed' -and (NoLeftovers)) 'the next run clears the backup'
    & "$installDir\uninstall.cmd" -Yes 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Check (-not (Test-Path $installDir) -and -not (Test-Path "$regDir\vi-smoke.lock")) 'uninstalled, lock file gone'

    # ---- the app manager -------------------------------------------------------------------
    Write-Host "the app manager lists, updates and removes"
    $out = Inst @{ INSTALL_VERSION = '1.0.0' }
    $mgrLnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'Test apps.lnk'
    Check ((Test-Path "$root\manager\manager.ps1") -and (Test-Path "$root\manager\manager.cmd")) 'the install sets up the shared manager beside the registry'
    Check ((Test-Path $mgrLnk) -and (Get-Content -Raw "$root\manager\manager.json" | ConvertFrom-Json).shortcut -eq $mgrLnk) 'with a Start menu shortcut it records'
    $out = & "$root\manager\manager.cmd" -List 2>&1 | Out-String
    Write-Host ($out.TrimEnd() -replace '(?m)^', '    | ')
    Check ($out -match 'vi-smoke\s+1\.0\.0\s+1\.4\.0\s+1\.4\.0 is available') 'manager -List shows the newer version'
    $out = & "$root\manager\manager.cmd" -Update vi-smoke 2>&1 | Out-String
    Write-Host ($out.TrimEnd() -replace '(?m)^', '    | ')
    Check ($out -match 'Updating ViSmoke' -and $out -match 'Updated ViSmoke') 'manager -Update runs the installer and follows it'
    $recM = Get-Content -Raw "$regDir\vi-smoke.json" | ConvertFrom-Json
    Check ($recM.version -eq '1.4.0' -and $recM.installDir -eq $installDir -and (NoLeftovers)) 'the record is at 1.4.0, in the same folder'
    $out = & "$root\manager\manager.cmd" -UpdateAll 2>&1 | Out-String
    Check ($out -match 'up to date') 'manager -UpdateAll with nothing behind says so'
    $out = & "$root\manager\manager.cmd" -Remove vi-smoke 2>&1 | Out-String
    Write-Host ($out.TrimEnd() -replace '(?m)^', '    | ')
    Start-Sleep -Seconds 5
    Check ($out -match 'Removed ViSmoke' -and -not (Test-Path $installDir) -and -not (Test-Path "$regDir\vi-smoke.json")) 'manager -Remove uninstalls it'
    Check (-not (Test-Path "$root\manager") -and -not (Test-Path $mgrLnk)) 'the last uninstall takes the manager and its shortcut'

    # ---- two installs: a runtime, and an app that needs it ---------------------------------
    Write-Host "runtime + dependent app"
    function BuildTo([string]$cfg, [string]$name, [string]$ver) {
        & java -jar $cli.FullName --config "$root\$cfg" --version $ver --out "$root\site-$name" --assets "$root\assets-$name" `
            --base-url "$base/site-$name" --asset-base-url "$base/assets-$name" --notes "smoke $name $ver" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "generator failed for $name" }
    }
    function InstallFrom([string]$name, [string]$dir) {
        $env:INSTALL_DIR = $dir
        $o = powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site-$name\install.ps1" 2>&1 | Out-String
        $o -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
        return $o
    }
    $rtDir = "$root\rt dir"; $appDir = "$root\app2 dir"
    # a fake jlink-style runtime image: bin\java.exe stands in for the JVM (where.exe), plus a few other files
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function MakeRuntimeZip([string[]]$extra) {
        $stage = "$root\rt-stage"
        Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path "$stage\bin", "$stage\lib" | Out-Null
        Copy-Item "$env:windir\System32\where.exe" "$stage\bin\java.exe"
        Copy-Item "$env:windir\System32\where.exe" "$stage\bin\javaw.exe"
        [IO.File]::WriteAllText("$stage\lib\modules", "modules")
        foreach ($x in $extra) { New-Item -ItemType Directory -Force -Path (Split-Path "$stage\$x" -Parent) | Out-Null; [IO.File]::WriteAllText("$stage\$x", $x) }
        Remove-Item -Force "$root\src\rt.zip" -ErrorAction SilentlyContinue
        [IO.Compression.ZipFile]::CreateFromDirectory($stage, "$root\src\rt.zip")
    }
    MakeRuntimeZip @('lib\old\gone.txt')
    [IO.File]::WriteAllText("$root\src\app2.jar", "not really a jar")
    @{ id = 'vi-rt'; name = 'ViRuntime'; version = '1.0.0'; repo = 'o/r'; tagFormat = '{version}'
       windows = @{ installDir = $rtDir; binaries = @(@{ name = 'runtime'; source = 'src/rt.zip'; exposes = @{ java = 'bin/java.exe'; javaw = 'bin/javaw.exe' } }) }
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\rt.json"
    function App2Config([string]$min) {
        @{ id = 'vi-app2'; name = 'ViApp2'; version = '1.0.0'; repo = 'o/r'; tagFormat = '{version}'
           windows = @{ installDir = $appDir
                        requires = @(@{ id = 'vi-rt'; min = $min })
                        binaries = @(@{ name = 'app2'; source = 'src/app2.jar'; runtime = 'vi-rt'; jvmArgs = @('-Xmx64m', '-Dx=a b') }) }
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\app2.json"
    }
    App2Config '2.0'
    BuildTo 'rt.json' 'rt' '1.0.0'; BuildTo 'app2.json' 'app2' '1.0.0'

    $out = InstallFrom 'app2' $appDir
    Check ($out -match "needs 'vi-rt' to be installed first") 'dependent refuses to install without its runtime'
    Check (-not (Test-Path "$appDir\app2.jar")) 'nothing was installed by the refused install'
    $out = InstallFrom 'rt' $rtDir
    Check ((Test-Path "$rtDir\bin\java.exe") -and (Test-Path "$regDir\vi-rt.home")) 'runtime installed with its pointer file'
    Check ((Get-Content -Raw "$regDir\vi-rt.home").Trim() -eq $rtDir) 'pointer file holds the runtime folder'
    Check ((Test-Path "$rtDir\lib\modules") -and (Test-Path "$rtDir\lib\old\gone.txt")) 'archive unpacked with its folder structure'
    $recRt = Get-Content -Raw "$regDir\vi-rt.json" | ConvertFrom-Json
    Check ($recRt.commands.java.path -eq "$rtDir\bin\java.exe" -and $recRt.commands.javaw.path -eq "$rtDir\bin\javaw.exe") 'archive exposes its commands in the record'
    Check (@($recRt.files | Where-Object { $_.kind -eq 'archive-file' }).Count -eq 4 -and @($recRt.archives).Count -eq 1) 'record lists every unpacked file and the archive'
    $out = InstallFrom 'app2' $appDir
    Check ($out -match '2\.0 or newer') 'dependent refuses a runtime that is too old'
    App2Config '1.0'; BuildTo 'app2.json' 'app2' '1.0.0'
    $out = InstallFrom 'app2' $appDir
    Check (Test-Path "$appDir\app2.jar") 'dependent installs once the runtime satisfies it'
    $launcher = Get-Content -Raw "$appDir\app2.cmd"
    Check ($launcher -match 'set /p VI_RT=<' -and $launcher -match '-Xmx64m "-Dx=a b" -jar') 'launcher resolves the runtime and carries jvmArgs'
    $rec2 = Get-Content -Raw "$regDir\vi-app2.json" | ConvertFrom-Json
    Check (@($rec2.requires) -contains 'vi-rt') 'record lists what the app requires'
    # the stand-in java.exe is where.exe, which rejects "-Xmx64m" and exits 2 (a missing runtime exits 1)
    & cmd.exe /c "`"$appDir\app2.cmd`" one two" 2>&1 | Out-Null
    Check ($LASTEXITCODE -eq 2) 'launcher ran the runtime java.exe with the arguments'

    # update the runtime: one file dropped, one added; the folder left empty must go too
    MakeRuntimeZip @('lib\added\new.txt')
    BuildTo 'rt.json' 'rt' '1.1.0'
    $out = & "$rtDir\update.cmd" -Yes 2>&1 | Out-String
    $out -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
    Check ((Test-Path "$rtDir\lib\added\new.txt") -and -not (Test-Path "$rtDir\lib\old")) 'archive update adds new files and removes dropped files and folders'
    Check ((Get-Content -Raw "$regDir\vi-rt.json" | ConvertFrom-Json).version -eq '1.1.0') 'runtime record at 1.1.0'
    & cmd.exe /c "`"$appDir\app2.cmd`" one" 2>&1 | Out-Null
    Check ($LASTEXITCODE -eq 2) 'dependent app still starts after the runtime update'
    $env:INSTALL_FORCE = '1'
    $out = InstallFrom 'rt' $rtDir
    Remove-Item Env:INSTALL_FORCE
    Check ($out -match 'is already current') 'reinstalling an unchanged archive does not download it again'
    Check ((Test-Path "$rtDir\lib\added\new.txt") -and @((Get-Content -Raw "$regDir\vi-rt.json" | ConvertFrom-Json).files | Where-Object { $_.kind -eq 'archive-file' }).Count -eq 4) 'unchanged archive keeps its file list'

    $out = & "$rtDir\uninstall.cmd" -Id vi-rt -Yes 2>&1 | Out-String
    Check ($out -match 'ViApp2 still needs ViRuntime') 'uninstalling the runtime warns about its dependents'
    Start-Sleep -Seconds 5
    $out = (& cmd.exe /c "`"$appDir\app2.cmd`" one 2>&1") | Out-String
    Check (($out -match "runtime 'vi-rt' is not installed") -and $LASTEXITCODE -eq 1) 'launcher reports a missing runtime clearly'
    Check (-not (Test-Path "$regDir\vi-rt.home")) 'pointer file removed with the runtime'
    Check (-not (Test-Path $rtDir)) 'every unpacked file and folder removed with the runtime'
    & "$appDir\uninstall.cmd" -Id vi-app2 -Yes 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Check (-not (Test-Path $appDir)) 'dependent uninstalled'

    # ---- a signed native executable, with a snake-oil (self-signed) certificate -------------
    Write-Host "signed native executable"
    $cert = New-SelfSignedCertificate -Subject 'CN=ViSmoke Snake Oil' -Type CodeSigningCert -CertStoreLocation Cert:\CurrentUser\My
    $sigDir = "$root\sig dir"
    # a byte appended to each copy so neither matches the system catalog that vouches for the original
    foreach ($n in 'signed', 'unsigned') {
        Copy-Item "$env:windir\System32\where.exe" "$root\src\$n.exe"
        [IO.File]::AppendAllText("$root\src\$n.exe", "x")
    }
    $signRes = Set-AuthenticodeSignature -FilePath "$root\src\signed.exe" -Certificate $cert
    Check ($signRes.SignerCertificate -and $signRes.SignerCertificate.Thumbprint -eq $cert.Thumbprint) 'test exe signed with the snake-oil certificate'
    function SigConfig([string]$exe, [string]$thumb, [string]$subject = 'CN=ViSmoke Snake Oil') {
        $bin = @{ name = 'sapp'; source = "src/$exe"; signedBy = $subject }
        if ($thumb) { $bin.signerThumbprint = $thumb }
        @{ id = 'vi-sig'; name = 'ViSig'; version = '1.0.0'; repo = 'o/r'; tagFormat = '{version}'
           windows = @{ installDir = $sigDir; binaries = @($bin) }
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\sig.json"
        BuildTo 'sig.json' 'sig' '1.0.0'
    }
    SigConfig 'unsigned.exe' $cert.Thumbprint
    $out = InstallFrom 'sig' $sigDir
    Check (($out -match 'is not signed') -and -not (Test-Path $sigDir)) 'an unsigned file is refused when the manifest says it is signed'
    SigConfig 'signed.exe' ('A' * 40)
    $out = InstallFrom 'sig' $sigDir
    Check (($out -match 'different certificate') -and -not (Test-Path $sigDir)) 'a different certificate than the pinned one is refused'
    SigConfig 'signed.exe' $cert.Thumbprint 'CN=Somebody Else'
    $out = InstallFrom 'sig' $sigDir
    Check (($out -match 'not CN=Somebody Else') -and -not (Test-Path $sigDir)) 'a different signer subject is refused'
    SigConfig 'signed.exe' $null
    $out = InstallFrom 'sig' $sigDir
    Check (($out -match 'not trusted') -and -not (Test-Path $sigDir)) 'without a pin a self-signed certificate is refused'
    SigConfig 'signed.exe' $cert.Thumbprint
    $out = InstallFrom 'sig' $sigDir
    Check ((Test-Path "$sigDir\signed.exe") -and ($out -match 'pinned certificate')) 'the pinned snake-oil certificate installs, with a warning that it is untrusted'
    $sm = Get-Content -Raw "$root\site-sig\manifest.json" | ConvertFrom-Json
    Check ($sm.versions[0].platforms.windows.binaries[0].signature.thumbprint -eq $cert.Thumbprint) 'manifest records the signer'
    & "$sigDir\uninstall.cmd" -Id vi-sig -Yes 2>&1 | Out-Null
    Start-Sleep -Seconds 5
    Check (-not (Test-Path $sigDir)) 'signed app uninstalled'
} finally {
    if ($cert) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($cert.Thumbprint)" -ErrorAction SilentlyContinue }
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item Env:INSTALL_DIR, Env:INSTALL_REGISTRY_DIR -ErrorAction SilentlyContinue
    foreach ($k in '.vismoke', '.vismoketaken', '.vismokej', 'vi-smoke.vismoke', 'vi-smoke.vismoketaken', 'vi-smoke.vismokej') {
        Remove-Item -Recurse -Force -LiteralPath "HKCU:\Software\Classes\$k" -ErrorAction SilentlyContinue
    }
    Remove-Item -Force -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) 'Test apps.lnk') -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
if ($fails -gt 0) { Write-Host "$fails check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "all checks passed" -ForegroundColor Green

