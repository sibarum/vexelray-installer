#requires -Version 5.1
# Drives the installer window through UI Automation against a local web server: install with a changed
# folder and file types opted back in, Cancel on the options page, a tampered download, and unmet system
# requirements (asked about, then declined). It needs an unlocked desktop and moves the mouse. Shortcuts and
# PATH stay opted out, so nothing lands on the desktop; the file types are registered and uninstalled again.
# Run:  powershell -File tests\window.ps1   (-Mode install|cancel|fail|reqs for one, -Shots <dir> for screenshots)
param([string]$Mode, [string]$Shots)
if (-not $Mode) {
    $bad = 0
    foreach ($m in 'install', 'cancel', 'fail', 'reqs') {
        Write-Host "window: $m"
        $more = if ($Shots) { @('-Shots', $Shots) } else { @() }
        & powershell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Mode $m @more
        if ($LASTEXITCODE -ne 0) { $bad++ }
    }
    if ($bad) { exit 1 } else { exit 0 }
}
$Repo = Split-Path $PSScriptRoot -Parent
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing, System.Windows.Forms
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-win-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
New-Item -ItemType Directory -Force -Path "$root\src", "$root\site", "$root\assets" | Out-Null
$cli = Get-ChildItem "$Repo\installer-core\target\installer-core-*-cli.jar" | Select-Object -First 1
Copy-Item "$env:windir\System32\where.exe" "$root\src\app.exe"
$big = New-Object byte[] (40MB); (New-Object Random 1).NextBytes($big); [IO.File]::WriteAllBytes("$root\src\data.bin", $big)
$installDir = "$root\Window Demo"
@{
    id = 'vi-window'; name = 'Window Demo'; publisher = 'Example Inc'; description = 'A demo of the installer window.'; version = '1.0.0'
    repo = 'o/r'; tagFormat = '{version}'
    windows = @{ installDir = $installDir; system = $(if ($Mode -eq 'reqs') { @{ memoryMb = 99999999 } } else { @{ memoryMb = 256 } }); binaries = @(
        @{ name = 'demo'; source = 'src/app.exe'; addToPath = $true; shortcuts = @{ desktop = $true; startMenu = $true; label = 'Window Demo' }
           fileAssociations = @(@{ extension = '.videmo' }, @{ extension = '.videmox' }) },
        @{ name = 'data'; source = 'src/data.bin'; kind = 'file' }
    ) }
} | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\installer.json"
& java -jar $cli.FullName --config "$root\installer.json" --version 1.0.0 --out "$root\site" --assets "$root\assets" `
    --base-url "$base/site" --asset-base-url "$base/assets" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'generator failed' }
if ($Mode -eq 'fail') { [IO.File]::AppendAllText("$root\assets\1.0.0\app.exe", 'tampered') }
$server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$Repo\tests\serve.ps1", '-Root', "$root", '-Port', $port
$env:INSTALL_REGISTRY_DIR = "$root\registry"
$env:INSTALL_NO_SHORTCUTS = '1'; $env:INSTALL_NO_PATH = '1'; $env:INSTALL_NO_ASSOCIATIONS = '1'
Remove-Item Env:INSTALL_DIR, Env:INSTALL_QUIET -ErrorAction SilentlyContinue

Add-Type -Namespace ViT -Name Win -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, IntPtr e);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
'@
$AE = [Windows.Automation.AutomationElement]
function FindWin([int]$procId, [int]$secs = 30) {
    $cond = New-Object Windows.Automation.PropertyCondition($AE::ProcessIdProperty, $procId)
    $t = [DateTime]::Now.AddSeconds($secs)
    while ([DateTime]::Now -lt $t) {
        $w = $AE::RootElement.FindFirst('Children', $cond)
        if ($w -and $w.Current.ClassName -like 'WindowsForms*') { return $w }
        Start-Sleep -Milliseconds 200
    }
    throw "no window from $procId"
}
function Named($w, [string]$name) {
    $w.FindFirst('Descendants', (New-Object Windows.Automation.PropertyCondition($AE::NameProperty, $name)))
}
function WaitNamed($w, [string]$name, [int]$secs = 60) {
    $t = [DateTime]::Now.AddSeconds($secs)
    while ([DateTime]::Now -lt $t) { $e = Named $w $name; if ($e) { return $e }; Start-Sleep -Milliseconds 200 }
    throw "no '$name'"
}
# PowerShell's WinForms controls expose no UI Automation patterns, so buttons and boxes are clicked with the mouse
function Click($e) {
    $top = $e; $walker = [Windows.Automation.TreeWalker]::ControlViewWalker
    while ($walker.GetParent($top) -and $walker.GetParent($top) -ne $AE::RootElement) { $top = $walker.GetParent($top) }
    [void][ViT.Win]::SetForegroundWindow([IntPtr]$top.Current.NativeWindowHandle); Start-Sleep -Milliseconds 300
    $r = $e.Current.BoundingRectangle
    [void][ViT.Win]::SetCursorPos([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2))
    [ViT.Win]::mouse_event(2, 0, 0, 0, [IntPtr]::Zero); [ViT.Win]::mouse_event(4, 0, 0, 0, [IntPtr]::Zero)
}
function Shot($w, [string]$file) {
    if (-not $Shots) { return }
    $h = [IntPtr]$w.Current.NativeWindowHandle
    $r = New-Object ViT.Win+RECT
    [void][ViT.Win]::GetWindowRect($h, [ref]$r)
    $bmp = New-Object Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
    $g = [Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc(); [void][ViT.Win]::PrintWindow($h, $dc, 2); $g.ReleaseHdc($dc)
    $bmp.Save((Join-Path $Shots $file)); $g.Dispose(); $bmp.Dispose()
}
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
try {
    Start-Sleep -Seconds 2
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("irm $base/site/install.ps1 | iex"))
    $p = Start-Process powershell -PassThru -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $enc
    $w = FindWin $p.Id
    $btn = WaitNamed $w 'Install' 30
    Start-Sleep -Seconds 1
    Shot $w '1-options.png'
    Check ((Named $w 'Install Window Demo') -ne $null) 'title names the app'
    Check ((Named $w 'Create shortcuts on the desktop and in the Start menu') -ne $null) 'shortcuts box'
    Check ((Named $w 'Add to PATH, so app can be run from a terminal') -ne $null) 'PATH box names the command'
    $assocBox = Named $w 'Open .videmo, .videmox files with Window Demo'
    Check ($assocBox -ne $null) 'file types box lists the extensions'
    Check ((Named $w $installDir) -ne $null) 'folder prefilled'
    if ($Mode -eq 'cancel') {
        Click (Named $w 'Cancel')
        $p.WaitForExit(30000) | Out-Null
        Check ($p.HasExited) 'Cancel on the options page ends the installer'
        Check (-not (Test-Path "$installDir\app.exe") -and -not (Test-Path "$root\registry\vi-window.json")) 'nothing installed after Cancel'
        return
    }
    if ($Mode -eq 'fail' -or $Mode -eq 'reqs') {
        Click $btn
        if ($Mode -eq 'reqs') {
            $box = $null; $t = [DateTime]::Now.AddSeconds(60)
            $dlg = New-Object Windows.Automation.PropertyCondition($AE::ClassNameProperty, '#32770')
            while (-not $box -and [DateTime]::Now -lt $t) {
                $box = $AE::RootElement.FindFirst('Children', $dlg)
                if ($box -and $box.Current.ProcessId -ne $p.Id) { $box = $null }
                if (-not $box) { $box = $w.FindFirst('Children', $dlg) }
                Start-Sleep -Milliseconds 200
            }
            Check ($box -ne $null) 'unmet requirements are asked about'
            if ($box) {
                Start-Sleep -Milliseconds 300
                Shot $box '4-requirements.png'
                Check ([bool](@($box.FindAll('Descendants', [Windows.Automation.Condition]::TrueCondition)) | Where-Object { $_.Current.Name -match '99999999 MB' })) 'the question names what is not met'
                Click (Named $box 'No')
            }
        }
        $close = WaitNamed $w 'Close' 120
        Start-Sleep -Milliseconds 400
        Shot $w "5-$Mode.png"
        Check ((Named $w 'Window Demo was not installed') -ne $null) 'failure page'
        Check ((Named $w 'Open report') -ne $null) 'Open report offered'
        Check ((Named $w 'Report the problem') -ne $null) 'issues link offered'
        Check (-not (Test-Path "$installDir\app.exe")) 'nothing installed'
        Click $close
        $p.WaitForExit(15000) | Out-Null
        Check ($p.HasExited) 'Close ends the installer'
        return
    }
    # type a different folder, and opt back in to file types
    Click (Named $w $installDir); Start-Sleep -Milliseconds 500; Click (Named $w $installDir); Start-Sleep -Milliseconds 300
    [Windows.Forms.SendKeys]::SendWait('{END}'); [Windows.Forms.SendKeys]::SendWait(' 2'); Start-Sleep -Milliseconds 300
    Click $assocBox; Start-Sleep -Milliseconds 300
    Shot $w '1b-changed.png'
    Click $btn
    Start-Sleep -Milliseconds 700
    Shot $w '2-progress.png'
    $close = WaitNamed $w 'Close' 120
    Start-Sleep -Seconds 1
    Shot $w '3-done.png'
    Check ((Named $w 'Window Demo is installed') -ne $null) 'done page'
    Check (Test-Path "$installDir 2\app.exe") 'installed into the folder typed in the window'
    $rec = Get-Content -Raw "$root\registry\vi-window.json" | ConvertFrom-Json
    Check ($rec.options.noShortcuts -and $rec.options.noPath -and -not $rec.options.noAssociations) 'record keeps the boxes as opt-outs'
    Check (@($rec.shortcuts).Count -eq 0 -and @($rec.pathEntries).Count -eq 0 -and @($rec.fileAssociations).Count -eq 2) 'file types registered, no shortcuts or PATH'
    Click $close
    $p.WaitForExit(15000) | Out-Null
    Check ($p.HasExited -and $p.ExitCode -eq 0) "Close ends the installer (exit $($p.ExitCode))"
} finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    if (Test-Path "$installDir 2\uninstall.ps1") { & powershell -NoProfile -ExecutionPolicy Bypass -File "$installDir 2\uninstall.ps1" -Yes | Out-Null }
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    if ($fails) { Write-Host "  $fails failed" -ForegroundColor Red; exit 1 }
}
