param(
    [string]$JdkHome = $env:GUGUPAN_JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome -and (Test-Path -LiteralPath 'D:\Software\jdk21.0.12_9\bin\java.exe')) {
    $JdkHome = 'D:\Software\jdk21.0.12_9'
}
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
try {
    if ($JdkHome) {
        $env:JAVA_HOME = $JdkHome
        $env:Path = "$JdkHome\bin;$env:Path"
    }
    Push-Location (Join-Path $PSScriptRoot 'frontend')
    try {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
    Push-Location $PSScriptRoot
    try {
        & mvn.cmd -B clean verify
        if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }
    } finally { Pop-Location }
    Write-Host "构建完成：$PSScriptRoot\target\gugupan.jar"
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}
