param(
    [int]$Port = 47800,
    [string]$Token = '',
    [string]$File = 'Perf.cs',
    [int]$Count = 200,
    [int]$Step = 40,
    [int]$StartLine = 9,
    [switch]$Clear
)

$ErrorActionPreference = 'Stop'
$base = "http://127.0.0.1:$Port"
$headers = @{}
if ($Token) { $headers['X-Sidecar-Token'] = $Token }

function Post-Json([string]$path, $payload) {
    $json = $payload | ConvertTo-Json -Compress
    Invoke-RestMethod -Uri "$base$path" -Method Post -Body $json -ContentType 'application/json' -Headers $headers
}

try {
    $ping = Invoke-RestMethod -Uri "$base/ping" -TimeoutSec 3
} catch {
    Write-Error "plugin api not reachable on port $Port - is Rider running with the plugin installed?"
    exit 1
}
Write-Output "ping: $($ping.ok)"

if (-not $Token) {
    Write-Warning "no -Token supplied; if authorization is enabled you will get 401"
}

$status = Invoke-RestMethod -Uri "$base/status" -Headers $headers
Write-Output "status: project=$($status.project) version=$($status.version) anchors=$($status.anchors)"

if ($Clear) {
    $r = Post-Json '/clear' @{ file = $File }
    Write-Output "cleared: $($r.cleared)"
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$created = 0
for ($i = 0; $i -lt $Count; $i++) {
    $line = $StartLine + $i * $Step
    $body = @{
        file      = $File
        startLine = $line
        endLine   = $line
        title     = "perf $i"
        body      = "auto note #$i - filler text to make the block render a line"
    }
    $res = Post-Json '/anchors' $body
    if ($res.ok) { $created++ } else { Write-Warning "line $line failed: $($res.error)" }
}
$sw.Stop()

Write-Output ("created {0}/{1} anchors in {2:N0} ms ({3:N1} ms/anchor)" -f $created, $Count, $sw.ElapsedMilliseconds, ($sw.ElapsedMilliseconds / [Math]::Max(1, $created)))

$after = Invoke-RestMethod -Uri "$base/status" -Headers $headers
Write-Output "after: anchors=$($after.anchors)"

$list = Invoke-RestMethod -Uri "$base/anchors?file=$([uri]::EscapeDataString($File))" -Headers $headers
Write-Output "listed: $($list.anchors.Count) anchors for $File"
