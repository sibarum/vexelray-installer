#requires -Version 5.1
# Prototype: install the three real native exes (vexplore, mainframe, text-editor) as independent installs,
# each signed with a snake-oil certificate that the manifest pins, then start each one from where it was
# installed, finding it the way a sibling app would (the install record's `commands`).
#
# Needs the exes built first (`mvn -Pnative package` in each app repo) and the generator jar built
# (`mvn -pl installer-core package`). Installs into a temp dir and a temp registry; the apps get a temp home
# (-D<app>.home) so your saved window/session state is not touched. Windows briefly open on screen.
# Shortcuts and PATH are written to the real user profile and removed again. Run:
#   powershell -File tests\suite-poc.ps1
$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
$gh = Split-Path $repo -Parent
$apps = @(
    @{ id = 'vexplore';    dir = "$gh\vexplore";                 prop = 'vexplore.home';    args = @() },
    @{ id = 'mainframe';   dir = "$gh\mainframe";                prop = 'mainframe.home';   args = @() },
    @{ id = 'text-editor'; dir = "$gh\text-editor-vexel-demo";   prop = 'text-editor.home'; args = @('SAMPLE') }
)
$root = Join-Path ([IO.Path]::GetTempPath()) ("vi-suite-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$port = Get-Random -Minimum 20000 -Maximum 40000
$base = "http://127.0.0.1:$port"
$regDir = "$root\registry"
New-Item -ItemType Directory -Force -Path "$root\src" | Out-Null
$fails = 0
function Check([bool]$ok, [string]$what) {
    if ($ok) { Write-Host "  ok   $what" -ForegroundColor Green } else { Write-Host "  FAIL $what" -ForegroundColor Red; $script:fails++ }
}
$cli = Get-ChildItem "$repo\installer-core\target\installer-core-*-cli.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $cli) { Write-Host "build the generator first: mvn -pl installer-core package" -ForegroundColor Red; exit 2 }
foreach ($a in $apps) {
    $a.exe = (Get-Content -Raw "$($a.dir)\installer.json" | ConvertFrom-Json).windows.binaries[0].source
    $a.exe = Join-Path $a.dir $a.exe
    if (-not (Test-Path -LiteralPath $a.exe)) { Write-Host "missing $($a.exe): build it with mvn -Pnative package" -ForegroundColor Red; exit 2 }
}

$pathBefore = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
$server = $null; $cert = $null
$env:INSTALL_REGISTRY_DIR = $regDir
try {
    $cert = New-SelfSignedCertificate -Subject 'CN=Snake Oil Software' -Type CodeSigningCert -CertStoreLocation Cert:\CurrentUser\My
    $server = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "$repo\tests\serve.ps1", '-Root', "$root", '-Port', $port
    Start-Sleep -Seconds 2
    [IO.File]::WriteAllText("$root\sample.txt", "hello from the suite prototype`n")

    Write-Host "sign copies, generate, install"
    foreach ($a in $apps) {
        $id = $a.id
        $signed = "$root\src\$id.exe"
        Copy-Item -LiteralPath $a.exe $signed
        $s = Set-AuthenticodeSignature -FilePath $signed -Certificate $cert
        Check ($s.SignerCertificate -and $s.SignerCertificate.Thumbprint -eq $cert.Thumbprint) "$id signed (copy; the build output is untouched)"
        $cfg = Get-Content -Raw "$($a.dir)\installer.json" | ConvertFrom-Json
        $bin = $cfg.windows.binaries[0]
        if ($bin.icon) { $bin.icon = Join-Path $a.dir $bin.icon }
        $bin.source = $signed
        $bin | Add-Member -NotePropertyName signedBy -NotePropertyValue 'CN=Snake Oil Software' -Force
        $bin | Add-Member -NotePropertyName signerThumbprint -NotePropertyValue $cert.Thumbprint -Force
        $cfg.tagFormat = '{version}'
        $cfg.windows | Add-Member -NotePropertyName installDir -NotePropertyValue "$root\inst\$id" -Force
        $cfg | ConvertTo-Json -Depth 10 | Set-Content -Encoding ASCII "$root\$id.json"
        & java -jar $cli.FullName --config "$root\$id.json" --version 0.1.0 --out "$root\site-$id" --assets "$root\assets-$id" `
            --base-url "$base/site-$id" --asset-base-url "$base/assets-$id" | Out-Null
        Check ($LASTEXITCODE -eq 0) "$id installer generated"
        # Encoded: Windows PowerShell hands `-Command "irm ... | iex"` to iex a line at a time.
        $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("irm $base/site-$id/install.ps1 | iex"))
        $out = powershell -NoProfile -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $enc 2>&1 | Out-String
        $out -split "`n" | ForEach-Object { if ($_.Trim()) { Write-Host "    | $($_.TrimEnd())" } }
        $installed = "$root\inst\$id\$id.exe"
        Check (Test-Path -LiteralPath $installed) "$id installed"
        $sig = Get-AuthenticodeSignature -LiteralPath $installed
        Check ($sig.SignerCertificate -and $sig.SignerCertificate.Thumbprint -eq $cert.Thumbprint) "$id installed file still carries the signature"
    }

    Write-Host "installs are independent and find each other"
    $recs = @{}
    foreach ($a in $apps) { $recs[$a.id] = Get-Content -Raw "$regDir\$($a.id).json" | ConvertFrom-Json }
    Check ($recs.Count -eq 3 -and @(Get-ChildItem "$regDir\*.json").Count -eq 3) 'three records in one registry'
    $userPath = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
    foreach ($a in $apps) {
        $p = $recs[$a.id].commands.PSObject.Properties[$a.id].Value.path
        Check ($p -eq "$root\inst\$($a.id)\$($a.id).exe") "$($a.id): commands lists $p"
        Check ($userPath -like "*$root\inst\$($a.id)*") "$($a.id): on the user PATH"
    }

    Write-Host "start each from its installed location, as a sibling would (temp app homes)"
    foreach ($a in $apps) {
        $exe = $recs[$a.id].commands.PSObject.Properties[$a.id].Value.path
        $h = "$root\home-$($a.id)"
        $argv = @("-D$($a.prop)=$h") + @($a.args | ForEach-Object { if ($_ -eq 'SAMPLE') { "$root\sample.txt" } else { $_ } })
        $proc = Start-Process -FilePath $exe -ArgumentList $argv -PassThru
        Start-Sleep -Seconds 7
        Check (-not $proc.HasExited) "$($a.id) is running from $exe"
        & taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 1
    foreach ($a in $apps) { Check (@(Get-Process -Name $a.id -ErrorAction SilentlyContinue).Count -eq 0) "$($a.id) stopped" }

    Write-Host "uninstall"
    foreach ($a in $apps) {
        $o = & "$root\inst\$($a.id)\uninstall.cmd" -Id $a.id -Yes 2>&1 | Out-String
    }
    Start-Sleep -Seconds 6
    foreach ($a in $apps) {
        Check (-not (Test-Path "$root\inst\$($a.id)")) "$($a.id) install folder gone"
        Check (-not (Test-Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$($a.id)")) "$($a.id) Apps entry gone"
    }
    $pathAfter = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment').GetValue('Path', '', 'DoNotExpandEnvironmentNames')
    Check ($pathAfter -eq $pathBefore) 'user PATH restored exactly'
} finally {
    foreach ($a in $apps) { Get-Process -Name $a.id -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
    if ($cert) { Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($cert.Thumbprint)" -ErrorAction SilentlyContinue }
    if ($server) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item Env:INSTALL_REGISTRY_DIR -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
}
if ($fails -gt 0) { Write-Host "$fails check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "all checks passed" -ForegroundColor Green
