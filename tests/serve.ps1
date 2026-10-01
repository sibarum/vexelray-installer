# Minimal static file server for tests (GitHub's raw host serves scripts/json as text/plain).
param([string]$Root, [int]$Port)
$l = New-Object Net.HttpListener
$l.Prefixes.Add("http://127.0.0.1:$Port/")
$l.Start()
while ($l.IsListening) {
    $c = $l.GetContext()
    $p = Join-Path $Root ($c.Request.Url.AbsolutePath.TrimStart('/').Replace('/', '\'))
    if (Test-Path -LiteralPath $p -PathType Leaf) {
        $b = [IO.File]::ReadAllBytes($p)
        if ($p -match '\.(ps1|json|md)$') { $c.Response.ContentType = 'text/plain; charset=utf-8' }
        $c.Response.ContentLength64 = $b.Length
        $c.Response.OutputStream.Write($b, 0, $b.Length)
    } else { $c.Response.StatusCode = 404 }
    $c.Response.Close()
}
