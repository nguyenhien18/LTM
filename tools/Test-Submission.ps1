$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$required = @(
    'README.md', 'INSTRUCTION.md', 'TOPICS.md', 'pom.xml',
    'run-server.bat', 'run-client.bat', 'start-mysql.bat',
    'report/README.md', 'report/BAOCAOLAPTRINHMANG_10_14.docx',
    'statics/architecture.svg', 'statics/architecture.png',
    'statics/protocol.svg', 'statics/protocol.png', 'statics/PROTOCOL.md',
    'statics/protocol.puml', 'statics/DATA_MODEL.md', 'statics/REFERENCES.md',
    'statics/class-students.svg', 'statics/class-students.png',
    'statics/exam-data.svg', 'statics/exam-data.png',
    'statics/exam-states.svg', 'statics/exam-states.png',
    'statics/results/README.md', 'statics/results/EXPERIMENT_PLAN.md',
    'statics/results/functional-tests.txt', 'statics/results/benchmark-log.txt',
    'statics/results/latency.png', 'statics/results/throughput.png',
    'statics/results/benchmark/samples.csv', 'statics/results/benchmark/phases.csv',
    'statics/results/benchmark/summary.csv', 'statics/results/benchmark/summary.json',
    'statics/results/benchmark/environment.json', 'statics/results/benchmark/hardware.json',
    'statics/dataset/README.md', 'statics/dataset/question-bank-template.docx',
    'statics/dataset/Co_so_du_lieu_200_cau.docx',
    'statics/dataset/Lap_trinh_mang_200_cau.docx',
    'statics/dataset/Lap_trinh_web_200_cau.docx'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $relative) -PathType Leaf)) { throw "Missing: $relative" }
}
$markdownFiles = @((Join-Path $projectRoot 'README.md'), (Join-Path $projectRoot 'TOPICS.md'), (Join-Path $projectRoot 'report/README.md'))
$markdownFiles += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'statics') -Filter '*.md' -Recurse -File | Select-Object -ExpandProperty FullName)
foreach ($file in $markdownFiles) {
    foreach ($match in [regex]::Matches([IO.File]::ReadAllText($file), '!?\[[^\]]+\]\(([^)]+)\)')) {
        $target = $match.Groups[1].Value
        if ($target -match '^(https?://|#)') { continue }
        $relative = [Uri]::UnescapeDataString(($target -split '#', 2)[0])
        if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $file) $relative))) { throw "Broken local link in ${file}: $target" }
    }
}
foreach ($name in @('architecture', 'protocol', 'class-students', 'exam-data', 'exam-states')) {
    $null = [xml][IO.File]::ReadAllText((Join-Path $projectRoot "statics/$name.svg"))
    $signature = [IO.File]::ReadAllBytes((Join-Path $projectRoot "statics/$name.png"))[0..7]
    if ([BitConverter]::ToString($signature) -ne '89-50-4E-47-0D-0A-1A-0A') { throw "Invalid PNG: $name" }
}
$seenCodes = @{}
foreach ($name in @('sinh-vien-nhom-01-60.csv', 'sinh-vien-nhom-02-60.csv')) {
    $csvPath = Join-Path $projectRoot "statics/dataset/$name"
    $rows = @(Import-Csv -LiteralPath $csvPath -Encoding UTF8)
    if ($rows.Count -ne 60) { throw "Expected 60 records: $name" }
    $columns = @($rows[0].PSObject.Properties.Name)
    if ($columns.Count -ne 5 -or $columns[0] -ne 'STT' -or $columns[1] -ne 'MSV') { throw "Unexpected columns: $name" }
    $ordinals = @{}
    foreach ($row in $rows) {
        $ordinal = [int]$row.STT
        if ($ordinal -lt 1 -or $ordinal -gt 60 -or $ordinals.ContainsKey($ordinal)) { throw "Invalid ordinal: $name" }
        if ([string]::IsNullOrWhiteSpace($row.MSV) -or $seenCodes.ContainsKey($row.MSV)) { throw "Duplicate/missing student: $name" }
        $null = [DateTime]::ParseExact([string]$row.($columns[3]), 'dd/MM/yyyy', [Globalization.CultureInfo]::InvariantCulture)
        if ([string]::IsNullOrWhiteSpace($row.($columns[2])) -or [string]::IsNullOrWhiteSpace($row.($columns[4]))) { throw "Missing student fields: $name" }
        $ordinals[$ordinal] = $true; $seenCodes[$row.MSV] = $true
    }
    Write-Output "$name : 60 valid rows"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($name in @('Co_so_du_lieu_200_cau.docx', 'Lap_trinh_mang_200_cau.docx', 'Lap_trinh_web_200_cau.docx')) {
    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $projectRoot "statics/dataset/$name"))
    try {
        $reader = [IO.StreamReader]::new($zip.GetEntry('word/document.xml').Open())
        try { [xml]$document = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $ns = [Xml.XmlNamespaceManager]::new($document.NameTable)
        $ns.AddNamespace('w', 'http://schemas.openxmlformats.org/wordprocessingml/2006/main')
        $paragraphs = @($document.SelectNodes('//w:p', $ns) | ForEach-Object {
            ($_.SelectNodes('.//w:t', $ns) | ForEach-Object { $_.InnerText }) -join ''
        })
        # Unicode escape keeps this script safe under Windows PowerShell's default encoding.
        $questionPattern = '^C' + [char]0x00E2 + 'u\s+\d+\s*[:.]'
        $count = @($paragraphs | Where-Object { $_ -match $questionPattern }).Count
        if ($count -ne 200) { throw "Expected 200 question headings in $name; found $count" }
        Write-Output "$name : 200 question headings"
    } finally { $zip.Dispose() }
}
$log = [IO.File]::ReadAllText((Join-Path $projectRoot 'statics/results/functional-tests.txt'))
if ($log -notmatch 'Tests run: 142, Failures: 0, Errors: 0, Skipped: 1' -or $log -notmatch 'BUILD SUCCESS') { throw 'Unexpected saved functional test result' }
$benchmarkLog = [IO.File]::ReadAllText((Join-Path $projectRoot 'statics/results/benchmark-log.txt'))
if ($benchmarkLog -notmatch 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0' -or $benchmarkLog -notmatch 'BUILD SUCCESS') { throw 'Unexpected saved benchmark result' }
$samples = @(Import-Csv -LiteralPath (Join-Path $projectRoot 'statics/results/benchmark/samples.csv') -Encoding UTF8)
if ($samples.Count -ne 3624 -or @($samples | Where-Object { $_.success -ne 'true' }).Count -ne 0) { throw 'Unexpected benchmark sample count or errors' }
foreach ($phase in @('LOGIN', 'JOIN', 'UPDATE_BARRIER', 'SUBMIT')) {
    $expected = if ($phase -eq 'UPDATE_BARRIER') { 2265 } else { 453 }
    if (@($samples | Where-Object { $_.phase -eq $phase }).Count -ne $expected) { throw "Unexpected sample count: $phase" }
}
$phases = @(Import-Csv -LiteralPath (Join-Path $projectRoot 'statics/results/benchmark/phases.csv') -Encoding UTF8)
$summary = @(Import-Csv -LiteralPath (Join-Path $projectRoot 'statics/results/benchmark/summary.csv') -Encoding UTF8)
if ($phases.Count -ne 60 -or $summary.Count -ne 20 -or @($summary | Where-Object { [int]$_.errors -ne 0 }).Count -ne 0) { throw 'Unexpected benchmark aggregation' }
Write-Output 'Saved evidence: 141 functional tests passed; benchmark has 3624 successful samples'
Write-Output 'Submission assets: PASS (does not run benchmarks or validate report content)'
