#requires -Version 5.1
# The app manager's Edge front end, end to end against a local web server: two installs (one behind),
# the page served from 127.0.0.1 with its token, the requests it refuses, an update and a removal through
# the same API the page uses, and the manager ending when its window closes. It opens an Edge window.
# Run:  powershell -File tests\manager.ps1   (-Shots <dir> saves a screenshot of the window)
param([string]$Shots)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-mgr-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
New-Item -ItemType Directory -Force -Path "$root\src" | Out-Null
$cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" | Select-Object -First 1
Copy-Item "$env:windir\System32\where.exe" "$root\src\app.exe"
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
function Build([string]$id, [string]$name, [string]$v) {
    @{ id = $id; name = $name; publisher = 'MgrTest'; version = $v; repo = 'o/r'; tagFormat = '{version}'
       windows = @{ installDir = "$root\$name"; binaries = @(@{ name = 'app'; source = 'src/app.exe' }) } } |
        ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\$id.json"
    & java -jar $cli.FullName --config "$root\$id.json" --version $v --out "$root\site-$id" --assets "$root\assets-$id" `
        --base-url "$base/site-$id" --asset-base-url "$base/assets-$id" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generator failed for $id" }
}
# a request with exactly the headers given, over a raw socket (HttpWebRequest will not send another Host)
function Raw([string]$req) {
    $t = New-Object Net.Sockets.TcpClient('127.0.0.1', $script:mport)
    try {
        $st = $t.GetStream()
        $b = [Text.Encoding]::ASCII.GetBytes($req)
        $st.Write($b, 0, $b.Length)
        $r = New-Object IO.StreamReader($st)
        $r.ReadLine()
    } finally { $t.Close() }
}
$server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$PSScriptRoot\serve.ps1", '-Root', "$root", '-Port', $port
$env:INSTALL_REGISTRY_DIR = "$root\registry"
$mgr = $null
try {
    Start-Sleep -Seconds 2
    Build 'vi-ma' 'Mgr Alpha' '1.0.0'
    Build 'vi-mb' 'Mgr Beta' '1.0.0'
    $env:INSTALL_QUIET = '1'; $env:INSTALL_NO_SHORTCUTS = '1'
    foreach ($id in 'vi-ma', 'vi-mb') { powershell -NoProfile -ExecutionPolicy Bypass -File "$root\site-$id\install.ps1" | Out-Null }
    Remove-Item Env:INSTALL_QUIET, Env:INSTALL_NO_SHORTCUTS
    Build 'vi-ma' 'Mgr Alpha' '1.1.0'

    Write-Host "the Edge window"
    $env:VI_MANAGER_URL_FILE = "$root\url.txt"
    $mgr = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$root\manager\manager.ps1`""
    Remove-Item Env:VI_MANAGER_URL_FILE
    $until = (Get-Date).AddSeconds(30)
    while (-not (Test-Path "$root\url.txt") -and (Get-Date) -lt $until) { Start-Sleep -Milliseconds 200 }
    $url = (Get-Content -Raw "$root\url.txt").Trim()
    $u = [uri]$url
    $script:mport = $u.Port
    Check ($u.Host -eq '127.0.0.1' -and $u.AbsolutePath.Length -ge 64) 'served on 127.0.0.1 behind a long token'
    $page = Invoke-WebRequest -UseBasicParsing $url
    Check ($page.Content -match '<h1 id="title">MgrTest apps</h1>' -and $page.Headers['Content-Security-Policy'] -match "default-src 'none'") 'the page, with a strict content security policy'
    $state = $null
    $until = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 300
        $state = Invoke-RestMethod "${url}api/state?rev=-1"
        $a = @($state.apps | Where-Object { $_.id -eq 'vi-ma' })[0]
    } while ((Get-Date) -lt $until -and $a.state -eq 'checking')
    Check (@($state.apps).Count -eq 2) 'both installs listed'
    Check ($a.state -eq 'update' -and $a.latest -eq '1.1.0' -and @($state.apps | Where-Object { $_.id -eq 'vi-mb' })[0].state -eq 'current') 'the one behind shows 1.1.0, the other is current'

    Write-Host "requests it refuses"
    Check ((Raw "GET /nothing/ HTTP/1.1`r`nHost: 127.0.0.1:$($u.Port)`r`nConnection: close`r`n`r`n") -match ' 404 ') 'a path without the token'
    Check ((Raw "GET $($u.AbsolutePath) HTTP/1.1`r`nHost: evil.example:$($u.Port)`r`nConnection: close`r`n`r`n") -match ' (400|403) ') 'another Host header (DNS rebinding)'
    $body = '{"kind":"check"}'
    $post = "POST $($u.AbsolutePath)api/act HTTP/1.1`r`nHost: 127.0.0.1:$($u.Port)`r`nContent-Type: application/json`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n"
    Check ((Raw ($post + "Origin: http://127.0.0.1:$($u.Port)`r`n`r`n$body")) -match ' 403 ') 'a change without the X-Vi header'
    Check ((Raw ($post + "Origin: https://evil.example`r`nX-Vi: 1`r`n`r`n$body")) -match ' 403 ') 'a change from another origin'
    Check ((Raw ($post + "Origin: http://127.0.0.1:$($u.Port)`r`nX-Vi: 1`r`n`r`n$body")) -match ' 200 ') 'and the same change from the page is accepted'

    if ($Shots) {
        Add-Type -AssemblyName System.Drawing
        Add-Type -Namespace ViT -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
'@
        Start-Sleep -Seconds 2
        $w = Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'MgrTest apps' } | Select-Object -First 1
        if ($w) {
            $r = New-Object ViT.Win+RECT; [void][ViT.Win]::GetWindowRect($w.MainWindowHandle, [ref]$r)
            $bmp = New-Object Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T); $g = [Drawing.Graphics]::FromImage($bmp)
            $dc = $g.GetHdc(); [void][ViT.Win]::PrintWindow($w.MainWindowHandle, $dc, 2); $g.ReleaseHdc($dc)
            $bmp.Save((Join-Path $Shots 'manager-edge.png')); $g.Dispose(); $bmp.Dispose()
        }
        Check ([bool]$w) 'the Edge window is titled after the publisher'
    }

    Write-Host "an update and a removal through the page's API"
    $hdr = @{ 'X-Vi' = '1'; Origin = "http://127.0.0.1:$($u.Port)" }
    $null = Invoke-RestMethod -Method Post -Uri "${url}api/act" -Headers $hdr -ContentType 'application/json' -Body '{"kind":"update","id":"vi-ma","closeApps":false}'
    $until = (Get-Date).AddSeconds(90)
    do { Start-Sleep -Milliseconds 500; $state = Invoke-RestMethod "${url}api/state?rev=-1"; $a = @($state.apps | Where-Object { $_.id -eq 'vi-ma' })[0] }
    while ((Get-Date) -lt $until -and ($state.job -or $a.state -in 'queued', 'busy', 'checking'))
    $rec = Get-Content -Raw "$root\registry\vi-ma.json" | ConvertFrom-Json
    Check ($rec.version -eq '1.1.0' -and $a.version -eq '1.1.0' -and $a.state -eq 'current') 'Update: installed 1.1.0, and the page shows it current'
    Check (($state.log -join "`n") -match 'Updated Mgr Alpha') 'the log tells what happened'
    $null = Invoke-RestMethod -Method Post -Uri "${url}api/act" -Headers $hdr -ContentType 'application/json' -Body '{"kind":"remove","id":"vi-mb","closeApps":false}'
    $until = (Get-Date).AddSeconds(60)
    do { Start-Sleep -Milliseconds 500; $state = Invoke-RestMethod "${url}api/state?rev=-1" } while ((Get-Date) -lt $until -and ($state.job -or @($state.apps).Count -ne 1))
    Check (@($state.apps).Count -eq 1 -and -not (Test-Path "$root\registry\vi-mb.json") -and -not (Test-Path "$root\Mgr Beta\app.exe")) 'Remove: uninstalled and gone from the list'

    Write-Host "closing the window ends the manager"
    Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq 'MgrTest apps' } | ForEach-Object { [void]$_.CloseMainWindow() }
    Check ($mgr.WaitForExit(20000)) 'the manager exits once its window is closed'
    $left = @(Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains("$root\manager\edge") })
    Check ($left.Count -eq 0) 'and no Edge process of its profile is left'
} finally {
    if ($mgr -and -not $mgr.HasExited) { Stop-Process -Id $mgr.Id -Force }
    Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($root) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    foreach ($id in 'vi-ma', 'vi-mb') { Remove-Item -Recurse -Force "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$id" -ErrorAction SilentlyContinue }
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    Remove-Item Env:INSTALL_REGISTRY_DIR -ErrorAction SilentlyContinue
}
if ($fails) { Write-Host "$fails check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "all checks passed" -ForegroundColor Green
