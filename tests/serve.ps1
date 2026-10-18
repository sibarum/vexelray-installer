# Minimal static file server for tests (GitHub's raw host serves scripts/json as text/plain).
# It answers "Range: bytes=N-" with 206, as GitHub's release downloads do, so downloads can resume. A file
# <root>\_drop holding "<url path> <bytes>" makes the next request for that path stop after that many bytes
# (the file is then removed), and every request is appended to <root>\_requests.log as "<path> <range>".
param([string]$Root, [int]$Port)
$l = New-Object Net.HttpListener
$l.Prefixes.Add("http://127.0.0.1:$Port/")
$l.Start()
while ($l.IsListening) {
    $c = $l.GetContext()
    $rel = $c.Request.Url.AbsolutePath.TrimStart('/')
    $p = Join-Path $Root ($rel.Replace('/', '\'))
    $range = [string]$c.Request.Headers['Range']
    try { [IO.File]::AppendAllText((Join-Path $Root '_requests.log'), "$rel $range`r`n") } catch { }
    if (Test-Path -LiteralPath $p -PathType Leaf) {
        $b = [IO.File]::ReadAllBytes($p)
        $start = 0
        if ($range -match '^bytes=(\d+)-$') {
            $start = [int]$Matches[1]
            if ($start -ge $b.Length) {
                $c.Response.StatusCode = 416
                $c.Response.AddHeader('Content-Range', "bytes */$($b.Length)")
                $c.Response.Close()
                continue
            }
            $c.Response.StatusCode = 206
            $c.Response.AddHeader('Content-Range', "bytes $start-$($b.Length - 1)/$($b.Length)")
        }
        $n = $b.Length - $start
        $cut = -1
        $drop = Join-Path $Root '_drop'
        if (Test-Path -LiteralPath $drop) {
            $d = ([IO.File]::ReadAllText($drop)).Trim() -split ' '
            if ($d[0] -eq $rel) { $cut = [int]$d[1]; Remove-Item -Force -LiteralPath $drop }
        }
        if ($p -match '\.(ps1|json|md)$') { $c.Response.ContentType = 'text/plain; charset=utf-8' }
        $c.Response.ContentLength64 = $n
        try {
            if ($cut -ge 0 -and $cut -lt $n) {
                $c.Response.OutputStream.Write($b, $start, $cut)
                $c.Response.OutputStream.Flush()
                $c.Response.Abort()
                continue
            }
            $c.Response.OutputStream.Write($b, $start, $n)
        } catch { }
    } else { $c.Response.StatusCode = 404 }
    try { $c.Response.Close() } catch { }
}
