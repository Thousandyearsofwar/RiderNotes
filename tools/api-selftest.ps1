param(
    [int]$Port = 0,
    [string]$Token = '',
    [string]$File = 'ConsoleApp1/Perf.cs',
    [string]$ProjectRoot = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# 找能力文件：优先 -ProjectRoot，其次当前目录，最后插件仓库
$candidates = @()
if ($ProjectRoot) { $candidates += (Join-Path $ProjectRoot '.ridenotes/api.json') }
$candidates += (Join-Path (Get-Location).Path '.ridenotes/api.json')
$candidates += (Join-Path $root '.ridenotes/api.json')
$apiFile = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if ($apiFile) {
    $api = Get-Content $apiFile -Raw | ConvertFrom-Json
    if (-not $Port) { $Port = [int]$api.port }
    if (-not $Token) { $Token = $api.token }
    Write-Host ("using capability file: " + $apiFile)
} else {
    # 兜底：从 IDE 配置里读令牌
    $opt = Join-Path $env:APPDATA 'JetBrains/Rider2026.2/options/riderNotes.xml'
    if (-not $Token -and (Test-Path $opt)) {
        $xml = [xml](Get-Content $opt -Raw)
        $node = $xml.application.component.option | Where-Object { $_.name -eq 'apiToken' } | Select-Object -First 1
        if ($node) { $Token = $node.value }
    }
}
if (-not $Port) { $Port = 47800 }
$base = "http://127.0.0.1:$Port"
$headers = @{}
if ($Token) { $headers['X-Sidecar-Token'] = $Token }

$script:pass = 0
$script:fail = 0

function Check([string]$name, [scriptblock]$block) {
    try {
        & $block
        Write-Host ("  [PASS] " + $name) -ForegroundColor Green
        $script:pass++
    } catch {
        Write-Host ("  [FAIL] " + $name + " :: " + $_.Exception.Message) -ForegroundColor Red
        $script:fail++
    }
}

function Get-Json([string]$path) { Invoke-RestMethod -Uri "$base$path" -Headers $headers }

function Post-Json([string]$path, $payload, [hashtable]$extraHeaders = @{}) {
    $h = @{}
    $headers.GetEnumerator() | ForEach-Object { $h[$_.Key] = $_.Value }
    $extraHeaders.GetEnumerator() | ForEach-Object { $h[$_.Key] = $_.Value }
    Invoke-RestMethod -Uri "$base$path" -Method Post -Body ($payload | ConvertTo-Json -Depth 8 -Compress) `
        -ContentType 'application/json' -Headers $h
}

Write-Host "== Sidecar Notes API selftest ==" -ForegroundColor Cyan
Write-Host ("port=" + $Port + "  token=" + $(if ($Token) { 'yes' } else { 'none' }))

$groupId = $null
$tagId = $null
$anchorId = $null
$noteId = $null

Check 'ping' { if (-not (Get-Json '/ping').ok) { throw 'ping failed' } }

Check 'status has version/port/notes' {
    $s = Get-Json '/status'
    if (-not $s.version) { throw 'no version' }
    if (-not $s.port) { throw 'no port' }
    if ($null -eq $s.notes) { throw 'no notes count' }
    Write-Host ("          version=" + $s.version + " port=" + $s.port + " anchors=" + $s.anchors + " notes=" + $s.notes)
}

Check 'create group' {
    $r = Post-Json '/groups' @{ name = 'selftest-group' }
    if (-not $r.ok) { throw $r.error }
    $script:groupId = $r.id
}

Check 'group listed' {
    $g = Get-Json '/groups'
    if (-not ($g.groups | Where-Object { $_.id -eq $groupId })) { throw 'group not listed' }
}

Check 'update group title/color' {
    $r = Post-Json '/groups/update' @{ id = $groupId; title = 'selftest-group-renamed'; color = 5 }
    if (-not $r.ok) { throw $r.error }
}

Check 'create tag' {
    $r = Post-Json '/tags' @{ name = 'selftest-tag' }
    if (-not $r.ok) { throw $r.error }
    $script:tagId = $r.id
}

Check 'tag listed' {
    $t = Get-Json '/tags'
    if (-not ($t.tags | Where-Object { $_.id -eq $tagId })) { throw 'tag not listed' }
}

Check 'create anchor with X-Sidecar-Client' {
    $r = Post-Json '/anchors' @{ file = $File; startLine = 5000; endLine = 5001; title = 'selftest'; body = 'from selftest' } `
        @{ 'X-Sidecar-Client' = 'selftest-agent/1.0' }
    if (-not $r.ok) { throw $r.error }
    $script:anchorId = $r.id
}

Check 'audit: createdBy recorded' {
    $list = Get-Json ("/anchors?file=" + [uri]::EscapeDataString($File))
    $a = $list.anchors | Where-Object { $_.id -eq $anchorId }
    if (-not $a) { throw 'anchor not listed' }
    if ($a.createdBy -ne 'selftest-agent/1.0') { throw ("createdBy=" + $a.createdBy) }
    Write-Host ("          createdBy=" + $a.createdBy)
}

Check 'append note' {
    $r = Post-Json '/notes' @{ anchorId = $anchorId; body = 'second note' } @{ 'X-Sidecar-Client' = 'selftest-agent/1.0' }
    if (-not $r.ok) { throw $r.error }
    $script:noteId = $r.noteId
}

Check 'update note body+tags' {
    $r = Post-Json '/notes/update' @{ anchorId = $anchorId; noteId = $noteId; body = 'edited by selftest'; tags = @('selftest-tag') }
    if (-not $r.ok) { throw $r.error }
}

Check 'search finds edited body' {
    $list = Get-Json '/anchors?q=edited%20by%20selftest'
    if (-not ($list.anchors | Where-Object { $_.id -eq $anchorId })) { throw 'search miss' }
}

Check 'update anchor title/color/locked' {
    $r = Post-Json '/anchors/update' @{ id = $anchorId; title = 'selftest-renamed'; color = 6; locked = $true }
    if (-not $r.ok) { throw $r.error }
}

Check 'move anchor into group' {
    $r = Post-Json '/anchors/move' @{ id = $anchorId; groupId = $groupId }
    if (-not $r.ok) { throw $r.error }
}

Check 'delta compare /changes' {
    $seq = (Get-Json '/changes?since=0').seq
    Post-Json '/anchors' @{ file = $File; startLine = 5100; endLine = 5100; title = 'selftest-change'; body = 'change probe' } | Out-Null
    $c = Get-Json ("/changes?since=" + $seq)
    if (-not $c.seq) { throw 'no seq' }
    if (-not $c.files -and -not $c.indexChanged -and -not $c.reset) { throw 'no delta reported' }
    Write-Host ("          reset=" + $c.reset + " files=" + ($c.files -join ',') + " indexChanged=" + $c.indexChanged)
}

Check 'mixed batch /batch (3 ops)' {
    $r = Post-Json '/batch' @{ ops = @(
            @{ op = 'anchor.create'; file = $File; startLine = 5200; endLine = 5200; title = 'batch-1'; body = 'batch body' },
            @{ op = 'note.add'; anchorId = $anchorId; body = 'batch added note' },
            @{ op = 'anchor.update'; id = $anchorId; title = 'selftest-after-batch' }
        ) }
    if (-not $r.ok) { throw $r.error }
    $bad = @($r.results | Where-Object { -not $_.ok })
    if ($bad.Count -gt 0) { throw 'some op failed' }
    Write-Host ("          results ok=" + $r.results.Count)
}

Check 'export markdown' {
    $md = Invoke-WebRequest -Uri "$base/export?format=md&file=$([uri]::EscapeDataString($File))" -Headers $headers -UseBasicParsing
    if ($md.StatusCode -ne 200) { throw "status $($md.StatusCode)" }
    if ($md.Content -notmatch 'selftest') { throw 'export missing anchor title' }
    Write-Host ("          md length=" + $md.Content.Length)
}

Check 'export pr format' {
    $pr = Invoke-WebRequest -Uri "$base/export?format=pr&file=$([uri]::EscapeDataString($File))" -Headers $headers -UseBasicParsing
    if ($pr.Content -notmatch '###') { throw 'unexpected pr format' }
    Write-Host ("          pr length=" + $pr.Content.Length)
}

Check 'delete single note' {
    $r = Post-Json '/notes/delete' @{ anchorId = $anchorId; noteId = $noteId }
    if (-not $r.ok) { throw $r.error }
}

Check 'delete tag (strips from notes)' {
    $r = Post-Json '/tags/delete' @{ id = $tagId }
    if (-not $r.ok) { throw $r.error }
}

Check 'cleanup: delete group and test anchors' {
    Post-Json '/groups/delete' @{ id = $groupId } | Out-Null
    Post-Json '/anchors/delete' @{ id = $anchorId } | Out-Null
    $list = Get-Json ("/anchors?file=" + [uri]::EscapeDataString($File))
    @($list.anchors | Where-Object { $_.title -like 'batch-*' -or $_.title -like 'selftest*' }) |
        ForEach-Object { Post-Json '/anchors/delete' @{ id = $_.id } | Out-Null }
}

Write-Host ""
Write-Host ("RESULT: pass=" + $script:pass + " fail=" + $script:fail) -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -gt 0) { exit 1 }
