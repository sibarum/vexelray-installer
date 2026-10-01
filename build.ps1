#requires -Version 5.1
<#
.SYNOPSIS
  Generates the installer, uninstaller, updater, manifest and README for a project.

.DESCRIPTION
  Reads installer.json, hashes the native-image / uberjar artifacts it names, and writes
  into -OutDir:  install.ps1  uninstall.ps1  update.ps1  manifest.json  README.md
  Artifacts are staged in build/release-assets/<version>/ for upload to a GitHub release
  (or uploaded for you with -Publish, which needs the gh CLI).

  manifest.json is merged, not overwritten: every version ever published stays in its
  history. Commit -OutDir's contents to the project's GitHub repo.

.EXAMPLE
  ./build.ps1 -Version 1.2.0 -Notes "Fixes the thing"
#>
[CmdletBinding()]
param(
    [string]$Config = (Join-Path $PSScriptRoot 'installer.json'),
    [string]$Version,
    [string]$OutDir = (Join-Path $PSScriptRoot 'dist'),
    [string]$Notes = '',
    [string]$AssetsDir = (Join-Path $PSScriptRoot 'build\release-assets'),
    [string]$BaseUrl,        # override the public URL of OutDir (local testing)
    [string]$AssetBaseUrl,   # override the release download base (local testing); URLs become <base>/<tag>/<file>
    [switch]$Publish         # create/update the GitHub release and upload the artifacts with gh
)
$ErrorActionPreference = 'Stop'

function Fail([string]$m) { throw "build: $m" }
function Opt($o, [string]$n, $d = $null) {
    $p = $o.PSObject.Properties[$n]
    if ($p -and $null -ne $p.Value -and "$($p.Value)" -ne '') { $p.Value } else { $d }
}
function VerKey([string]$v) {
    $core = ($v -replace '^[vV]', '') -replace '[-+].*$', ''
    if ($core -notmatch '\.') { $core = "$core.0" }
    try { $ver = [version]$core } catch { Fail "'$v' is not a version number (use e.g. 1.2.3 or 1.2.3-rc1)" }
    [pscustomobject]@{ Ver = $ver; Pre = [bool]($v -match '-') }
}
function CompareVer([string]$a, [string]$b) {
    $x = VerKey $a; $y = VerKey $b
    $c = $x.Ver.CompareTo($y.Ver)
    if ($c -ne 0) { return $c }
    if ($x.Pre -and -not $y.Pre) { return -1 }
    if (-not $x.Pre -and $y.Pre) { return 1 }
    0
}
function WriteLF([string]$path, [string]$text) {
    $text = $text -replace "`r`n", "`n"
    if ($path -like '*.ps1' -and $text -match '[^\x00-\x7F]') { Fail "non-ASCII characters in $path (the scripts must stay ASCII so they survive 'irm | iex')" }
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding $false))
}
function Sha([string]$p) { (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant() }
function Render([string]$text, [hashtable]$vars) {
    foreach ($k in $vars.Keys) { $text = $text.Replace("{{$k}}", [string]$vars[$k]) }
    $left = [regex]::Match($text, '\{\{[A-Z_]+\}\}')
    if ($left.Success) { Fail "unreplaced template token $($left.Value)" }
    $text
}

# ---- config ----------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $Config)) { Fail "config not found: $Config (copy installer.example.json to installer.json)" }
$Config = (Resolve-Path -LiteralPath $Config).Path
$cfgDir = Split-Path $Config -Parent
$cfg = Get-Content -Raw -LiteralPath $Config | ConvertFrom-Json

$id = Opt $cfg 'id'
$name = Opt $cfg 'name'
if (-not $id -or $id -notmatch '^[a-z0-9][a-z0-9._-]*$') { Fail "'id' is required: lowercase letters, digits, . _ -" }
if (-not $name -or $name -match '[\\/:*?"<>|]') { Fail "'name' is required and must be usable as a folder name" }
$publisher = Opt $cfg 'publisher' $name
$description = Opt $cfg 'description' "$name."
$repo = Opt $cfg 'repo'
$branch = Opt $cfg 'branch' 'main'
$repoPath = (Opt $cfg 'repoPath' '').Trim('/', '\')
$relRepo = Opt $cfg 'releasesRepo' $repo
$tagFormat = Opt $cfg 'tagFormat' 'v{version}'
$registryDir = Opt $cfg 'registryDir' '%LOCALAPPDATA%\vexelray-installer\installs'
if (-not $Version) { $Version = Opt $cfg 'version' }
if (-not $Version) { Fail "no version: pass -Version or set 'version' in the config" }
$Version = $Version -replace '^[vV]', ''
VerKey $Version | Out-Null

if (-not $BaseUrl) {
    if (-not $repo -or $repo -notmatch '^[\w.-]+/[\w.-]+$') { Fail "'repo' must look like owner/name (the GitHub repo the generated files will be committed to)" }
    $BaseUrl = "https://raw.githubusercontent.com/$repo/$branch" + $(if ($repoPath) { "/$repoPath" } else { '' })
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$tag = $tagFormat.Replace('{version}', $Version)
function AssetUrl([string]$file) {
    if ($AssetBaseUrl) { return "$($AssetBaseUrl.TrimEnd('/'))/$tag/$file" }
    if (-not $relRepo) { Fail "'releasesRepo' or 'repo' is required" }
    "https://github.com/$relRepo/releases/download/$tag/$file"
}

$win = $cfg.windows
if (-not $win -or -not $win.binaries) { Fail "config needs windows.binaries" }
$installDirTemplate = (Opt $win 'installDir' '%LOCALAPPDATA%\Programs\{name}').Replace('{name}', $name)

# ---- artifacts -------------------------------------------------------------------------
$assetOut = Join-Path $AssetsDir $Version
New-Item -ItemType Directory -Force -Path $assetOut | Out-Null
$bins = @()
$seen = @{}
$javaMin = -1
foreach ($b in @($win.binaries)) {
    $src = Opt $b 'source'
    if (-not $src) { Fail "every binary needs a 'source' path" }
    if (-not [IO.Path]::IsPathRooted($src)) { $src = Join-Path $cfgDir $src }
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { Fail "artifact not found: $src" }
    $file = Opt $b 'file' (Split-Path $src -Leaf)
    if ($seen[$file]) { Fail "two binaries share the file name '$file'" }
    $seen[$file] = $true
    $kind = Opt $b 'kind' $(if ($file -like '*.jar') { 'jar' } else { 'native' })
    if ($kind -notin 'native', 'jar') { Fail "binary kind must be 'native' or 'jar'" }
    $bname = Opt $b 'name' ([IO.Path]::GetFileNameWithoutExtension($file))
    $sub = (Opt $b 'dir' '').Trim('\', '/')
    $rel = if ($sub) { "$sub\$file" } else { $file }

    Copy-Item -Force -LiteralPath $src -Destination (Join-Path $assetOut $file)
    $staged = Join-Path $assetOut $file
    $entry = [ordered]@{
        name        = $bname
        version     = (Opt $b 'version' $Version)
        kind        = $kind
        file        = $file
        url         = (AssetUrl $file)
        sha256      = (Sha $staged)
        size        = (Get-Item -LiteralPath $staged).Length
        installPath = "{installDir}\$rel"
        executable  = $true
        addToPath   = [bool](Opt $b 'addToPath' $false)
    }
    if ($kind -eq 'jar') {
        $entry.launcher = "{installDir}\$sub".TrimEnd('\') + "\$bname.cmd"
        $jm = [int](Opt $b 'javaMin' 0)
        if ($jm -gt $javaMin) { $javaMin = $jm }
    }
    $sc = $b.PSObject.Properties['shortcuts']
    if ($sc -and $sc.Value) {
        $entry.shortcuts = [ordered]@{
            desktop   = [bool](Opt $sc.Value 'desktop' $true)
            startMenu = [bool](Opt $sc.Value 'startMenu' $false)
            label     = (Opt $sc.Value 'label' $bname)
            gui       = [bool](Opt $sc.Value 'gui' ($kind -eq 'jar'))
        }
    }
    $bins += $entry
}
$platform = [ordered]@{ installDir = $installDirTemplate }
if ($javaMin -ge 0) { $platform.requires = [ordered]@{ java = $javaMin } }
$platform.binaries = $bins

# ---- scripts ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path
$manifestUrl = "$BaseUrl/manifest.json"
$scriptCfg = [ordered]@{
    id          = $id
    name        = $name
    publisher   = $publisher
    manifestUrl = $manifestUrl
    registryDir = $registryDir
}
$cfgJson = ($scriptCfg | ConvertTo-Json -Compress).Replace("'", "''")
$installUrl = "$BaseUrl/install.ps1"
$vars = @{
    NAME        = $name
    CONFIG_JSON = $cfgJson
    INSTALL_URL = $installUrl
}
$tools = [ordered]@{}
foreach ($s in 'install.ps1', 'uninstall.ps1', 'update.ps1') {
    $text = Render (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot "templates\$s")) $vars
    $dest = Join-Path $OutDir $s
    WriteLF $dest $text
    $tools[$s] = [ordered]@{ url = "$BaseUrl/$s"; sha256 = (Sha $dest) }
}

# ---- manifest (merged with history) ----------------------------------------------------
$manifestPath = Join-Path $OutDir 'manifest.json'
$now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$history = @()
if (Test-Path -LiteralPath $manifestPath) {
    $prev = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ($prev.id -ne $id) { Fail "existing manifest.json is for '$($prev.id)', not '$id'" }
    $history = @($prev.versions)
}
$publishedAt = $now
$existing = $history | Where-Object { $_.version -eq $Version } | Select-Object -First 1
if ($existing) { $publishedAt = $existing.publishedAt; Write-Warning "version $Version was already in the manifest; replacing its entry" }
$history = @($history | Where-Object { $_.version -ne $Version })

$verEntry = [ordered]@{
    version     = $Version
    publishedAt = $publishedAt
    notes       = $Notes
    installer   = [ordered]@{
        'install.ps1'   = $tools['install.ps1'].sha256
        'uninstall.ps1' = $tools['uninstall.ps1'].sha256
        'update.ps1'    = $tools['update.ps1'].sha256
    }
    platforms   = [ordered]@{ windows = $platform }
}
if ($existing) { $verEntry.updatedAt = $now }
$all = @($history) + @([pscustomobject]$verEntry)
$all = @($all | Sort-Object -Descending -Property @{ Expression = { (VerKey $_.version).Ver } }, @{ Expression = { -not (VerKey $_.version).Pre } })
$stable = @($all | Where-Object { -not (VerKey $_.version).Pre })
$latest = if ($stable.Count -gt 0) { $stable[0].version } else { $all[0].version }
if ($latest -ne $Version) { Write-Warning "latest stays at $latest (you published $Version)" }

$manifest = [ordered]@{
    schemaVersion = 1
    id            = $id
    name          = $name
    publisher     = $publisher
    description   = $description
    repo          = $repo
    latest        = $latest
    tools         = $tools
    versions      = $all
}
WriteLF $manifestPath ($manifest | ConvertTo-Json -Depth 12)

# ---- README ----------------------------------------------------------------------------
$lines = @()
$lines += '- creates the install folder and places the binaries there'
$lines += '- unblocks them and makes sure they are executable'
if (@($bins | Where-Object { $_.shortcuts }).Count -gt 0) { $lines += '- creates desktop / Start menu shortcuts' }
if (@($bins | Where-Object { $_.addToPath }).Count -gt 0) { $lines += '- adds the install folder to your user PATH' }
$lines += '- writes an install manifest listing everything it installed and where'
$lines += '- adds an uninstaller and an updater next to the binaries, and an entry in Settings > Apps'
$table = @('| File | Kind | Version | Size | Installed to |', '| --- | --- | --- | --- | --- |')
foreach ($b in $bins) {
    $table += "| ``$($b.file)`` | $(if ($b.kind -eq 'jar') { 'Java uberjar' } else { 'native executable' }) | $($b.version) | $('{0:N1}' -f ($b.size / 1MB)) MB | ``$($b.installPath.Replace('{installDir}', $installDirTemplate))`` |"
}
$javaNote = ''
if ($javaMin -ge 0) {
    $javaNote = "`nJava$(if ($javaMin -gt 0) { " $javaMin or newer" }) must be installed and on your PATH (or set ``JAVA_HOME``). A ``<name>.cmd`` launcher is created for each jar.`n"
}
$readme = Render (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'templates\README.md')) @{
    NAME            = $name
    DESCRIPTION     = $description
    INSTALL_COMMAND = "irm $installUrl | iex"
    INSTALL_URL     = $installUrl
    MANIFEST_URL    = $manifestUrl
    VERSION         = $latest
    WHAT_IT_DOES    = ($lines -join "`n")
    BINARY_TABLE    = ($table -join "`n")
    JAVA_NOTE       = $javaNote
    INSTALL_DIR     = $installDirTemplate
    REGISTRY_DIR    = $registryDir
    ID              = $id
}
$readmeName = 'README.md'
$readmePath = Join-Path $OutDir $readmeName
if ((Test-Path -LiteralPath $readmePath) -and -not (Select-String -LiteralPath $readmePath -SimpleMatch 'generated by vexelray-installer' -Quiet)) {
    $readmeName = 'INSTALL.md'
    Write-Warning "README.md in $OutDir was not generated by this tool; writing INSTALL.md instead. Paste its install section into your README."
}
WriteLF (Join-Path $OutDir $readmeName) $readme

# ---- publish ---------------------------------------------------------------------------
if ($Publish) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "-Publish needs the GitHub CLI (gh) on PATH" }
    $assets = @($bins | ForEach-Object { Join-Path $assetOut $_.file })
    & gh release view $tag --repo $relRepo *> $null
    if ($LASTEXITCODE -eq 0) {
        & gh release upload $tag @assets --repo $relRepo --clobber
    } else {
        & gh release create $tag @assets --repo $relRepo --title "$name $Version" --notes $(if ($Notes) { $Notes } else { "$name $Version" })
    }
    if ($LASTEXITCODE -ne 0) { Fail "gh failed" }
}

Write-Host ""
Write-Host "Generated $name $Version into $OutDir" -ForegroundColor Green
Write-Host "  one-liner:  irm $installUrl | iex"
if (-not $Publish) {
    Write-Host "  next:       upload the files in $assetOut to the release tagged '$tag' on $relRepo (or re-run with -Publish)"
}
Write-Host "              then commit install.ps1, uninstall.ps1, update.ps1, manifest.json and $readmeName in $OutDir to $repo"
