param(
    [string]$OutputDirectory,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenRepository = (Join-Path $env:USERPROFILE '.m2\repository')
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'dist\current' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\jpackage.exe'))) {
    $packager = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if (-not $packager) { throw 'Can JDK 21+ co jpackage va jlink de dong goi.' }
    $JdkHome = Split-Path (Split-Path $packager.Source -Parent) -Parent
}
$javaTools = @{}
foreach ($tool in @('java', 'jar', 'jlink', 'jpackage')) {
    $javaTools[$tool] = Join-Path $JdkHome "bin\$tool.exe"
    if (-not (Test-Path -LiteralPath $javaTools[$tool])) { throw "Khong tim thay $tool trong $JdkHome" }
}
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$maven = if ($mavenCommand) { $mavenCommand.Source } else {
    $cached = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2\wrapper\dists') -Filter mvn.cmd -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cached) { $cached.FullName }
}
if (-not $maven) { throw 'Khong tim thay Maven 3.9+.' }
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Khong tim thay C# compiler cua .NET Framework tren Windows.' }

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 2>&1 | ForEach-Object { Write-Host $_.ToString() }
        $commandExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($commandExitCode -ne 0) { throw "Lenh that bai ($commandExitCode): $Executable" }
}

Push-Location $projectRoot
try {
    Write-Host '[1/6] Bien dich ung dung...'
    Invoke-Checked $maven @('-q', '-DskipTests', 'package')
    $buildRoot = Join-Path $projectRoot ('target\client-exe\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    $inputDirectory = Join-Path $buildRoot 'input'
    $runtimeDirectory = Join-Path $buildRoot 'runtime'
    $imageDirectory = Join-Path $buildRoot 'image'
    New-Item -ItemType Directory -Path $inputDirectory, $OutputDirectory -Force | Out-Null

    Write-Host '[2/6] Lay rieng ma Client va thu vien...'
    Invoke-Checked $javaTools.jar @('--create', '--file', (Join-Path $inputDirectory 'lan-exam-client.jar'),
        '--main-class', 'com.ptit.lanexam.client.ClientLauncher',
        '-C', (Join-Path $projectRoot 'target\classes'), 'com/ptit/lanexam/client',
        '-C', (Join-Path $projectRoot 'target\classes'), 'com/ptit/lanexam/common',
        '-C', (Join-Path $projectRoot 'target\classes'), 'styles')
    [xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
    $javafxVersion = [string]$pom.project.properties.'javafx.version'
    $jacksonVersion = [string]$pom.project.properties.'jackson.version'
    $dependencies = @(
        @('org\openjfx', 'javafx-base', $javafxVersion, '-win'),
        @('org\openjfx', 'javafx-graphics', $javafxVersion, '-win'),
        @('org\openjfx', 'javafx-controls', $javafxVersion, '-win'),
        @('com\fasterxml\jackson\core', 'jackson-annotations', $jacksonVersion, ''),
        @('com\fasterxml\jackson\core', 'jackson-core', $jacksonVersion, ''),
        @('com\fasterxml\jackson\core', 'jackson-databind', $jacksonVersion, ''),
        @('com\fasterxml\jackson\datatype', 'jackson-datatype-jsr310', $jacksonVersion, '')
    )
    foreach ($dependency in $dependencies) {
        $jarName = '{0}-{1}{2}.jar' -f $dependency[1], $dependency[2], $dependency[3]
        $source = Join-Path $MavenRepository (Join-Path $dependency[0] (Join-Path $dependency[1] (Join-Path $dependency[2] $jarName)))
        if (-not (Test-Path -LiteralPath $source)) { throw "Thieu thu vien: $source" }
        Copy-Item -LiteralPath $source -Destination $inputDirectory
    }

    Write-Host '[3/6] Tao Java runtime kem san...'
    $modules = 'java.base,java.desktop,java.logging,java.naming,java.prefs,java.scripting,java.sql,java.xml,jdk.unsupported,jdk.crypto.ec'
    Invoke-Checked $javaTools.jlink @('--add-modules', $modules, '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', $runtimeDirectory)

    Write-Host '[4/6] Tao ban Client khong can cai dat...'
    Invoke-Checked $javaTools.jpackage @('--type', 'app-image', '--name', 'LANExamClient', '--app-version', '1.0.0',
        '--vendor', 'LAN Exam System', '--description', 'LAN Exam Client',
        '--input', $inputDirectory, '--main-jar', 'lan-exam-client.jar', '--main-class', 'com.ptit.lanexam.client.ClientLauncher',
        '--runtime-image', $runtimeDirectory, '--dest', $imageDirectory)
    $appImage = Join-Path $imageDirectory 'LANExamClient'

    Write-Host '[5/6] Nen runtime va ung dung...'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payload = Join-Path $buildRoot 'client-payload.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($appImage, $payload, [IO.Compression.CompressionLevel]::Optimal, $false)
    $payloadHash = (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant()

    Write-Host '[6/6] Gop thanh mot file EXE...'
    $bootstrapSource = Join-Path $buildRoot 'PortableClient.cs'
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'PortableClient.cs'), [Text.Encoding]::UTF8)
    [IO.File]::WriteAllText($bootstrapSource, $template.Replace('__PAYLOAD_HASH__', $payloadHash), [Text.UTF8Encoding]::new($true))
    $builtExe = Join-Path $buildRoot 'LANExamClient.exe'
    $framework = Split-Path $compiler -Parent
    Invoke-Checked $compiler @('/nologo', '/target:winexe', '/platform:x64', '/optimize+', '/codepage:65001',
        ('/out:' + $builtExe), ('/resource:' + $payload + ',ClientPayload'),
        '/reference:System.dll', '/reference:System.Core.dll', '/reference:System.Drawing.dll', '/reference:System.Windows.Forms.dll',
        ('/reference:' + (Join-Path $framework 'System.IO.Compression.dll')), $bootstrapSource)
    $destination = Join-Path $OutputDirectory 'LANExamClient.exe'
    Copy-Item -LiteralPath $builtExe -Destination $destination -Force
    $checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'LANExamClient.exe.sha256'), $checksum + '  LANExamClient.exe' + [Environment]::NewLine)
    [pscustomobject]@{
        Executable = $destination
        SizeMB = [math]::Round((Get-Item -LiteralPath $destination).Length / 1MB, 2)
        SHA256 = $checksum
        PayloadHash = $payloadHash
        AppImage = $appImage
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $buildRoot 'build-info.json') -Encoding UTF8
    Write-Host "Da tao: $destination"
    Write-Host ('Dung luong: {0:N1} MB' -f ((Get-Item -LiteralPath $destination).Length / 1MB))
    Write-Host 'Chi can gui file LANExamClient.exe cho hoc vien.'
} finally { Pop-Location }
