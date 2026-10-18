#requires -Version 5.1
# Drives the suite installer's window through UI Automation against a local web server, with a suite of
# three: VI-WA (shortcuts, PATH, a file type and a 40 MB file), VI-WB, and VI-WC, which is never published.
# Install with VI-WB unticked and file types opted back in, Cancel on the options page, and a member installer
# that does not match its manifest. It needs an unlocked desktop and moves the mouse. Shortcuts and PATH stay
# opted out, so nothing lands on the desktop; what is installed is uninstalled again.
# Run:  powershell -File tests\suite-window.ps1   (-Mode install|cancel|fail|stop for one, -Shots <dir> for screenshots)
param([string]$Mode, [string]$Shots)
if (-not $Mode) {
    $bad = 0
    foreach ($m in 'install', 'cancel', 'fail', 'stop') {
        Write-Host "suite window: $m"
        $more = if ($Shots) { @('-Shots', $Shots) } else { @() }
        & powershell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Mode $m @more
        if ($LASTEXITCODE -ne 0) { $bad++ }
    }
    if ($bad) { exit 1 } else { exit 0 }
}
$Repo = Split-Path $PSScriptRoot -Parent
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing, System.Windows.Forms
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-swin-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
New-Item -ItemType Directory -Force -Path "$root\src" | Out-Null
$cli = Get-ChildItem "$Repo\installer-core\target\installer-core-*-cli.jar" | Select-Object -First 1
Copy-Item "$env:windir\System32\where.exe" "$root\src\app.exe"
$big = New-Object byte[] $(if ($Mode -eq 'stop') { 400MB } else { 40MB }); (New-Object Random 1).NextBytes($big); [IO.File]::WriteAllBytes("$root\src\data.bin", $big)
$members = 'vi-wa', 'vi-wb', 'vi-wc'
function Gen([string[]]$genArgs) {
    & java -jar $cli.FullName @genArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generator failed: $genArgs" }
}
$suiteMembers = @()
foreach ($id in $members) {
    $bins = if ($id -eq 'vi-wa') {
        @(@{ name = $id; source = 'src/app.exe'; file = "$id.exe"; addToPath = $true; shortcuts = @{ desktop = $true; startMenu = $true; label = 'VI-WA' }
             fileAssociations = @(@{ extension = '.viwa' }) },
          @{ name = 'data'; source = 'src/data.bin'; kind = 'file' })
    } else { @(@{ name = $id; source = 'src/app.exe'; file = "$id.exe" }) }
    @{
        id = $id; name = $id.ToUpperInvariant(); publisher = 'Example Inc'; description = "member $id"; version = '1.0.0'; repo = "o/$id"; tagFormat = '{version}'
        windows = @{ installDir = "$root\inst\$id"; binaries = @($bins) }
    } | ConvertTo-Json -Depth 8 | Set-Content -Encoding ASCII "$root\$id.json"
    $suiteMembers += @{ config = "$id.json"; manifestUrl = "$base/site-$id/manifest.json" }
}
@{ id = 'vi-wsuite'; name = 'ViSuite'; description = 'A demo of the suite window.'; repo = 'o/suite'; members = $suiteMembers } |
    ConvertTo-Json -Depth 6 | Set-Content -Encoding ASCII "$root\suite.json"
foreach ($id in 'vi-wa', 'vi-wb') {
    Gen @('--config', "$root\$id.json", '--version', '1.0.0', '--out', "$root\site-$id", '--assets', "$root\assets-$id",
          '--base-url', "$base/site-$id", '--asset-base-url', "$base/assets-$id")
}
Gen @('--suite', "$root\suite.json", '--out', "$root\suite", '--base-url', "$base/suite")
if ($Mode -eq 'fail') { Add-Content -LiteralPath "$root\site-vi-wb\install.ps1" -Value '# tampered' }
$server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$Repo\tests\serve.ps1", '-Root', "$root", '-Port', $port
$env:INSTALL_REGISTRY_DIR = "$root\registry"
$env:INSTALL_NO_SHORTCUTS = '1'; $env:INSTALL_NO_PATH = '1'; $env:INSTALL_NO_ASSOCIATIONS = '1'
Remove-Item Env:INSTALL_ONLY, Env:INSTALL_QUIET, Env:INSTALL_FORCE -ErrorAction SilentlyContinue

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
# the pages share the window, so what the hidden page has is skipped
function Named($w, [string]$name) {
    $all = $w.FindAll('Descendants', (New-Object Windows.Automation.PropertyCondition($AE::NameProperty, $name)))
    foreach ($e in $all) { if (-not $e.Current.IsOffscreen) { return $e } }
    $null
}
function WaitNamed($w, [string]$name, [int]$secs = 60) {
    $t = [DateTime]::Now.AddSeconds($secs)
    while ([DateTime]::Now -lt $t) { $e = Named $w $name; if ($e) { return $e }; Start-Sleep -Milliseconds 200 }
    throw "no '$name'"
}
# The text beside a member's name: its row's other label, the one level with it and to its right
function Beside($w, [string]$name) {
    $n = Named $w $name
    if (-not $n) { return $null }
    $r = $n.Current.BoundingRectangle
    # (every control is a Pane to UI Automation, so it is found by where it is)
    foreach ($e in $w.FindAll('Descendants', [Windows.Automation.Condition]::TrueCondition)) {
        $b = $e.Current.BoundingRectangle
        if ($b.X -gt $r.X + 100 -and [Math]::Abs($b.Y - $r.Y) -lt 10 -and $b.Height -lt 60 -and -not $e.Current.IsOffscreen) { return $e.Current.Name }
    }
    $null
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
    $bmp.Save((Join-Path $Shots "suite-$file")); $g.Dispose(); $bmp.Dispose()
}
function Rec([string]$id) {
    $p = "$root\registry\$id.json"
    if (Test-Path -LiteralPath $p) { Get-Content -Raw -LiteralPath $p | ConvertFrom-Json } else { $null }
}
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
try {
    Start-Sleep -Seconds 2
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("irm $base/suite/install.ps1 | iex"))
    $p = Start-Process powershell -PassThru -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $enc
    $w = FindWin $p.Id
    $btn = WaitNamed $w 'Install' 30
    Start-Sleep -Seconds 1
    Shot $w '1-options.png'
    Check ((Named $w 'Install ViSuite') -ne $null) 'title names the suite'
    Check ((Named $w 'VI-WA') -and (Named $w 'VI-WB') -and (Named $w 'VI-WC')) 'a box for each application'
    Check ((Beside $w 'VI-WA') -eq '1.0.0' -and (Beside $w 'VI-WB') -eq '1.0.0') 'each published one shows its version'
    Check ((Beside $w 'VI-WC') -eq 'not published yet') 'the unpublished one says so'
    Check ((Named $w 'Create shortcuts on the desktop and in the Start menu') -ne $null) 'shortcuts box'
    Check ((Named $w 'Add to PATH, so vi-wa can be run from a terminal') -ne $null) 'PATH box names the command'
    $assocBox = Named $w 'Open .viwa files with VI-WA'
    Check ($assocBox -ne $null) 'file types box names the extension and the application'
    if ($Mode -eq 'cancel') {
        Click (Named $w 'Cancel')
        $p.WaitForExit(30000) | Out-Null
        Check ($p.HasExited) 'Cancel on the options page ends the installer'
        Check (-not (Rec 'vi-wa') -and -not (Rec 'vi-wb')) 'nothing installed after Cancel'
        return
    }
    if ($Mode -eq 'fail') {
        Click $btn
        $close = WaitNamed $w 'Close' 120
        Start-Sleep -Milliseconds 400
        Shot $w '4-fail.png'
        Check ((Named $w 'ViSuite was not fully installed') -ne $null) 'summary says not everything was installed'
        Check ((Beside $w 'VI-WA') -eq 'installed 1.0.0') 'VI-WA installed'
        Check ((Beside $w 'VI-WB') -eq 'failed; see the log') 'VI-WB failed'
        Check ((Beside $w 'VI-WC') -eq 'not published yet') 'VI-WC not published'
        Check ((Named $w 'Not installed: VI-WB. The log below says why.') -ne $null) 'the failure is named'
        Check ((Rec 'vi-wa') -and -not (Rec 'vi-wb')) 'records match'
        Click $close
        $p.WaitForExit(15000) | Out-Null
        Check ($p.HasExited) 'Close ends the installer'
        return
    }
    if ($Mode -eq 'stop') {
        Click $btn
        # VI-WA's own installer shows its download in this window, and Cancel stops it there
        $t = [DateTime]::Now.AddSeconds(120); $dl = $null
        while (-not $dl -and [DateTime]::Now -lt $t) {
            $dl = @($w.FindAll('Descendants', [Windows.Automation.Condition]::TrueCondition)) | Where-Object { $_.Current.Name -like 'Downloading data.bin (*' } | Select-Object -First 1
            if (-not $dl) { Start-Sleep -Milliseconds 100 }
        }
        Check ($dl -ne $null) "the member's download shows its progress in the suite window"
        Shot $w '5-downloading.png'
        Click (Named $w 'Cancel')
        $close = WaitNamed $w 'Close' 120
        Start-Sleep -Milliseconds 400
        Shot $w '6-stopped.png'
        Check ((Named $w 'ViSuite was not fully installed') -ne $null) 'summary says not everything was installed'
        Check ((Beside $w 'VI-WA') -eq 'cancelled' -and (Beside $w 'VI-WB') -eq 'cancelled') 'both rows cancelled'
        Check ((Named $w 'Cancelled. Nothing was installed.') -ne $null) 'and nothing was installed'
        Check (-not (Rec 'vi-wa') -and -not (Test-Path "$root\inst\vi-wa\data.bin") -and -not (Rec 'vi-wb')) 'no records, no files'
        Click $close
        $p.WaitForExit(15000) | Out-Null
        Check ($p.HasExited) 'Close ends the installer'
        return
    }
    # untick VI-WB, and opt back in to file types
    Click (Named $w 'VI-WB'); Start-Sleep -Milliseconds 300
    Click $assocBox; Start-Sleep -Milliseconds 300
    Shot $w '1b-changed.png'
    Click $btn
    Start-Sleep -Milliseconds 700
    Shot $w '2-progress.png'
    $close = WaitNamed $w 'Close' 120
    Start-Sleep -Seconds 1
    Shot $w '3-done.png'
    Check ((Named $w 'ViSuite is installed') -ne $null) 'done page'
    Check ((Beside $w 'VI-WA') -eq 'installed 1.0.0') 'VI-WA installed'
    Check ((Beside $w 'VI-WB') -eq 'not selected') 'VI-WB not selected'
    Check ((Beside $w 'VI-WC') -eq 'not published yet') 'VI-WC not published'
    $rec = Rec 'vi-wa'
    Check ($rec -and (Test-Path "$root\inst\vi-wa\data.bin")) 'VI-WA installed, with its 40 MB file'
    Check (-not (Rec 'vi-wb') -and -not (Test-Path "$root\inst\vi-wb")) 'VI-WB not installed'
    Check ($rec.options.noShortcuts -and $rec.options.noPath -and -not $rec.options.noAssociations) 'record keeps the boxes as opt-outs'
    Check (@($rec.shortcuts).Count -eq 0 -and @($rec.pathEntries).Count -eq 0 -and @($rec.fileAssociations).Count -eq 1) 'file type registered, no shortcuts or PATH'
    Check ((Get-Content "$root\inst\vi-wa\install-manifest.json" -Raw) -ne $null) 'the member wrote its own manifest'
    Click $close
    $p.WaitForExit(15000) | Out-Null
    Check ($p.HasExited -and $p.ExitCode -eq 0) "Close ends the installer (exit $($p.ExitCode))"
} finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    foreach ($id in $members) {
        if (Test-Path "$root\inst\$id\uninstall.ps1") { & powershell -NoProfile -ExecutionPolicy Bypass -File "$root\inst\$id\uninstall.ps1" -Id $id -Yes | Out-Null }
        Remove-Item -Recurse -Force "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$id" -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    if ($fails) { Write-Host "  $fails failed" -ForegroundColor Red; exit 1 }
}
