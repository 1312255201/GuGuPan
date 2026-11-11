param(
    [string]$JdkHome = $env:GUGUPAN_JAVA_HOME,
    [int]$Port = 8080
)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome -and (Test-Path -LiteralPath 'D:\Software\jdk21.0.12_9\bin\java.exe')) {
    $JdkHome = 'D:\Software\jdk21.0.12_9'
}
$javaExecutable = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { 'java' }
$jarPath = Join-Path $PSScriptRoot 'target\gugupan.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先运行 .\build.ps1 构建项目' }
Push-Location $PSScriptRoot
try {
    & $javaExecutable -jar $jarPath "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw "应用退出，状态码 $LASTEXITCODE" }
} finally { Pop-Location }
