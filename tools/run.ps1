param(
    [ValidateSet('', 'title', 'update', 'key', 'imdb', 'import')][string]$Experiment = '',
    [ValidateSet('file', 'postgresql', 'opengauss')][string]$System = 'file',
    [ValidateSet('1x', '10x', '50x')][string]$Scale = '50x',
    [string]$Keyword = 'man'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$root = Split-Path -Parent $PSScriptRoot
try {
    if (!$Experiment) {
        Write-Host '1 标题检索   2 姓名更新   3 主键查找   4 IMDb检索   5 导入CSV到数据库'
        $choice = Read-Host '选择实验（回车默认1）'
        $Experiment = switch ($choice) {
            '' { 'title' }; '1' { 'title' }; '2' { 'update' }; '3' { 'key' }
            '4' { 'imdb' }; '5' { 'import' }; default { throw '请选择1到5。' }
        }
        $selectedSystem = Read-Host '选择 file / postgresql / opengauss（回车默认file）'
        if ($selectedSystem) { $System = $selectedSystem.ToLowerInvariant() }
        if ($System -notin @('file', 'postgresql', 'opengauss')) { throw '系统名称不正确。' }
        if ($Experiment -in @('title', 'update', 'import')) {
            $selectedScale = Read-Host '规模 1x / 10x / 50x（回车默认50x）'
            if ($selectedScale) { $Scale = $selectedScale.ToLowerInvariant() }
            if ($Scale -notin @('1x', '10x', '50x')) { throw '规模名称不正确。' }
        }
    }
    if ($Experiment -eq 'import' -and $System -eq 'file') {
        throw '导入CSV请选择 postgresql 或 opengauss；文件实验直接使用包内数据。'
    }

    # 先找当前环境的 JDK，再找 Windows 常见安装位置。
    $javac = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\javac.exe'
        if (Test-Path -LiteralPath $candidate) { $javac = $candidate }
    }
    if (!$javac) {
        $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($compiler) { $javac = $compiler.Source }
    }
    if (!$javac) {
        $candidate = Get-ChildItem -Path "$env:ProgramFiles\Java\jdk-*\bin\javac.exe" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { $javac = $candidate.FullName }
    }
    if (!$javac) { throw '没有找到JDK，请安装JDK 21并设置JAVA_HOME或PATH。' }
    $version = (& $javac -version 2>&1 | Out-String).Trim()
    if ($version -notmatch 'javac (\d+)' -or [int]$Matches[1] -lt 21) {
        throw "需要JDK 21或更新版本，当前是 $version。"
    }
    $java = Join-Path (Split-Path -Parent $javac) 'java.exe'
    $build = Join-Path $root 'build'
    New-Item -ItemType Directory -Force -Path $build | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'java') -Filter '*.java' | ForEach-Object FullName)
    & $javac -encoding UTF-8 -d $build @sources
    if ($LASTEXITCODE -ne 0) { throw '编译失败，请检查上面的提示。' }

    if ($Experiment -eq 'imdb' -and $System -eq 'file') {
        & (Join-Path $PSScriptRoot 'download-imdb.ps1')
    }
    $main = switch ($Experiment) {
        'title' { 'Benchmark' }; 'update' { 'UpdateBenchmark' }; 'key' { 'PrimaryKeyBenchmark' }
        'imdb' { 'ImdbBenchmark' }; 'import' { 'DatasetBuilder' }
    }
    $programArgs = @(switch ($Experiment) {
        'title' { @($System, $Keyword, $Scale) }; 'update' { @($System, $Scale) }
        'key' { @($System) }; 'imdb' { @($System, $Keyword) }; 'import' { @('import', $System, $Scale) }
    })
    $heap = if ($Experiment -eq 'imdb') { '-Xmx512m' } else { '-Xmx1024m' }
    Push-Location -LiteralPath (Join-Path $root 'java')
    try {
        & $java $heap '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
            -cp "$build;$(Join-Path $root 'lib')/*" $main @programArgs
        if ($LASTEXITCODE -ne 0) { throw '运行失败，请检查上面的提示及数据库配置。' }
    } finally { Pop-Location }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
