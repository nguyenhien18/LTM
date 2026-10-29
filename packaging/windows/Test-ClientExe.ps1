param([string]$Executable)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $Executable) { $Executable = Join-Path $projectRoot 'dist\current\LANExamClient.exe' }
$Executable = [IO.Path]::GetFullPath($Executable)
if (-not (Test-Path -LiteralPath $Executable)) { throw "Khong tim thay $Executable" }
$savedJavaHome = $env:JAVA_HOME
$savedPath = $env:PATH
$windowProcess = $null
$bootstrap = $null
try {
    $env:JAVA_HOME = ''
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    $bootstrap = Start-Process -FilePath $Executable -WorkingDirectory (Split-Path $Executable -Parent) -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $descendants = [Collections.Generic.HashSet[int]]::new()
    $null = $descendants.Add($bootstrap.Id)
    while ([DateTime]::UtcNow -lt $deadline) {
        $images = @(Get-CimInstance Win32_Process -Filter "Name = 'LANExamClient.exe'" -ErrorAction SilentlyContinue)
        do {
            $previousCount = $descendants.Count
            foreach ($candidate in $images) {
                if ($descendants.Contains([int]$candidate.ParentProcessId)) { $null = $descendants.Add([int]$candidate.ProcessId) }
            }
        } while ($descendants.Count -ne $previousCount)
        foreach ($candidate in $images) {
            if (-not $descendants.Contains([int]$candidate.ProcessId) -or $candidate.ProcessId -eq $bootstrap.Id) { continue }
            $process = Get-Process -Id $candidate.ProcessId -ErrorAction SilentlyContinue
            if ($process -and $process.MainWindowHandle -ne 0 -and $process.MainWindowTitle -eq 'LAN Exam Client') {
                $windowProcess = $process
                $bundledPath = $candidate.ExecutablePath
                break
            }
        }
        if ($windowProcess) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $windowProcess) { throw 'Client portable chua hien cua so dang nhap dung han.' }
    $report = [pscustomobject]@{
        Executable = $Executable
        BootstrapPid = $bootstrap.Id
        ClientPid = $windowProcess.Id
        WindowTitle = $windowProcess.MainWindowTitle
        BundledExecutable = $bundledPath
        JavaHome = $env:JAVA_HOME
        SystemJavaOnPath = $false
    }
    $null = $windowProcess.CloseMainWindow()
    if (-not $bootstrap.WaitForExit(15000)) { throw 'Client chua thoat sau khi dong cua so.' }
    if ($bootstrap.ExitCode -ne 0) { throw "Client tra ma loi $($bootstrap.ExitCode)." }
    $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot 'target\portable-client-smoke.json') -Encoding UTF8
    $report | ConvertTo-Json
    Write-Host 'Portable smoke test: PASSED'
} finally {
    $env:JAVA_HOME = $savedJavaHome
    $env:PATH = $savedPath
    if ($windowProcess -and -not $windowProcess.HasExited) { $null = $windowProcess.CloseMainWindow() }
}
