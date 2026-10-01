# Parses every template (with a dummy config) and reports syntax errors.
$bad = 0
foreach ($f in Get-ChildItem "$PSScriptRoot\..\installer-core\src\main\resources\templates\windows\*.ps1") {
    $t = (Get-Content -Raw $f.FullName).Replace('{{CONFIG_JSON}}', '{}').Replace('{{NAME}}', 'X').Replace('{{INSTALL_URL}}', 'u')
    $tok = $null; $err = $null
    [void][Management.Automation.Language.Parser]::ParseInput($t, [ref]$tok, [ref]$err)
    foreach ($e in $err) { "$($f.Name):$($e.Extent.StartLineNumber): $($e.Message)"; $bad++ }
}
if ($bad) { exit 1 } else { "templates parse cleanly" }
