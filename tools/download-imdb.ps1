$ErrorActionPreference = 'Stop'
$directory = Join-Path (Split-Path -Parent $PSScriptRoot) '导出的文件'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$target = Join-Path $directory 'title.basics.tsv'
if (Test-Path -LiteralPath $target) {
    if ((Get-Item -LiteralPath $target).Length -gt 0) {
        Write-Host 'title.basics.tsv 已存在，直接使用。'
        return
    }
    throw 'title.basics.tsv 是空文件，请删除后重新下载。'
}
$archive = Join-Path $directory 'title.basics.tsv.gz.part'
$temporary = Join-Path $directory 'title.basics.tsv.part'
try {
    Write-Host '正在从IMDb官方下载并解压，首次需要联网；请留出约2GB磁盘空间。'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $oldProgressPreference = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        Invoke-WebRequest -Uri 'https://datasets.imdbws.com/title.basics.tsv.gz' -OutFile $archive -UseBasicParsing
    } finally { $ProgressPreference = $oldProgressPreference }
    $inputStream = [IO.File]::OpenRead($archive)
    try {
        $gzip = [IO.Compression.GZipStream]::new($inputStream, [IO.Compression.CompressionMode]::Decompress)
        try {
            $outputStream = [IO.File]::Create($temporary)
            try { $gzip.CopyTo($outputStream) } finally { $outputStream.Dispose() }
        } finally { $gzip.Dispose() }
    } finally { $inputStream.Dispose() }
    $header = Get-Content -LiteralPath $temporary -Encoding UTF8 -TotalCount 1
    if ($header -notlike "tconst`t*") { throw '下载文件的TSV表头不正确。' }
    Move-Item -LiteralPath $temporary -Destination $target
    Write-Host "下载完成：$target"
} finally {
    foreach ($partial in @($archive, $temporary)) {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
    }
}
