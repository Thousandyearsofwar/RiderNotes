param(
    [Parameter(Mandatory = $true)][string]$Version,   # 例如 0.86.0
    [switch]$SkipBuild,
    [switch]$SkipTag
)

# 发布辅助：构建产物 + 打 tag + 推送 tag，并打印 GitHub Release 的下一步。
# 不直接创建 Release（避免误发布）；若装了 gh CLI 会顺带提示可用的命令。

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$zip = Join-Path $repo "build\distributions\RiderNotes-$Version.zip"
$tag = "v$Version"

Push-Location $repo
try {
    if (-not $SkipTag) {
        $dirty = git status --porcelain
        if ($dirty) { throw "工作区有未提交改动，先提交再发布：`n$dirty" }
    }

    if (-not $SkipBuild) {
        $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
        & .\gradlew.bat buildPlugin --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'buildPlugin 失败' }
    }

    if (-not (Test-Path $zip)) { throw "找不到构建产物：$zip" }

    if (-not $SkipTag) {
        git tag -a $tag -m "Sidecar Notes $Version"
        git push origin $tag
        if ($LASTEXITCODE -ne 0) { throw '推送 tag 失败' }
    }

    $notesPath = Join-Path $repo "docs\releases\$Version.md"
    Write-Host ''
    Write-Host "== 下一步：创建 GitHub Release ==" -ForegroundColor Cyan
    Write-Host "1) 打开 https://github.com/Thousandyearsofwar/RiderNotes/releases/new?tag=$tag"
    Write-Host "2) 标题填：Sidecar Notes $Version"
    Write-Host ("3) 说明贴这里：{0}" -f $notesPath)
    Write-Host ("4) 上传附件：{0}" -f $zip)
    Write-Host '5) 点 Publish release'
    if (Get-Command gh -ErrorAction SilentlyContinue) {
        Write-Host ''
        Write-Host '（检测到 gh CLI，也可以一条命令发布）' -ForegroundColor DarkGray
        Write-Host ("gh release create {0} `"{1}`" --title `"Sidecar Notes {2}`" --notes-file `"{3}`"" -f $tag, $zip, $Version, $notesPath)
    }
} finally {
    Pop-Location
}
