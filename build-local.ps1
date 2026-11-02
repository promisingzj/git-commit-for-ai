param(
    [Parameter(Mandatory = $true)][string]$IdeHome,
    [string]$JdkHome = "",
    [switch]$Test,
    [switch]$Preview
)
$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$IdeHome = (Resolve-Path -LiteralPath $IdeHome).Path
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $bundled = Join-Path $IdeHome "jbr"
    if (Test-Path -LiteralPath (Join-Path $bundled "bin/javac.exe")) { $JdkHome = $bundled }
    elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin/javac.exe"))) { $JdkHome = $env:JAVA_HOME }
    else { throw "A JDK 21+ is required. Supply -JdkHome." }
}
$javac = Join-Path $JdkHome "bin/javac.exe"
$java = Join-Path $JdkHome "bin/java.exe"
if (-not (Test-Path -LiteralPath $javac)) { throw "javac was not found in JdkHome." }
$build = Join-Path $projectRoot "build"
$classes = Join-Path $build "classes"
$tests = Join-Path $build "test-classes"
$dist = Join-Path $build "distributions"
foreach ($target in @($classes,$tests,(Join-Path $build "package"))) {
    $resolvedTarget = [IO.Path]::GetFullPath($target)
    $allowedPrefix = [IO.Path]::GetFullPath($build) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTarget.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to clean outside the build directory." }
    if (Test-Path -LiteralPath $resolvedTarget) { Remove-Item -LiteralPath $resolvedTarget -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $classes,$tests,$dist | Out-Null
$jars = @(Get-ChildItem -LiteralPath (Join-Path $IdeHome "lib") -Recurse -Filter "*.jar")
$gitLib = Join-Path $IdeHome "plugins/vcs-git/lib"
if (-not (Test-Path -LiteralPath $gitLib)) { $gitLib = Join-Path $IdeHome "plugins/git4idea/lib" }
if (Test-Path -LiteralPath $gitLib) { $jars += Get-ChildItem -LiteralPath $gitLib -Recurse -Filter "*.jar" }
$classpath = ($jars | ForEach-Object { $_.FullName.Replace([char]92, [char]47) }) -join ";"
function Quote-JavaArg([string]$value) { return '"' + $value.Replace([char]92, [char]47) + '"' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "src/main/java") -Recurse -Filter "*.java")
$argsFile = Join-Path $build "compile.args"
@("--release","21","-encoding","UTF-8","-classpath",(Quote-JavaArg $classpath),"-d",(Quote-JavaArg $classes)) + @($sources | ForEach-Object { Quote-JavaArg $_.FullName }) | Set-Content -LiteralPath $argsFile -Encoding utf8NoBOM
& $javac ("@" + $argsFile)
if ($LASTEXITCODE -ne 0) { throw "Plugin compilation failed." }
Copy-Item -Path (Join-Path $projectRoot "src/main/resources/*") -Destination $classes -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectRoot "LICENSE") -Destination (Join-Path $classes "META-INF/LICENSE") -Force
$staging = Join-Path $build "package/git-commit-for-ai/lib"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
$jar = Join-Path $staging "git-commit-for-ai-1.0.0.jar"
Add-Type -AssemblyName System.IO.Compression
if (Test-Path -LiteralPath $jar) { [IO.File]::Delete($jar) }
[IO.Compression.ZipFile]::CreateFromDirectory($classes, $jar)
if ($Test -or $Preview) {
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "src/test/java") -Recurse -Filter "*.java")
    if ($testSources.Count -gt 0) {
        $testArgs = Join-Path $build "test-compile.args"
        @("--release","21","-encoding","UTF-8","-classpath",(Quote-JavaArg $classpath),"-d",(Quote-JavaArg $tests)) + @(($sources + $testSources) | ForEach-Object { Quote-JavaArg $_.FullName }) | Set-Content -LiteralPath $testArgs -Encoding utf8NoBOM
        & $javac ("@" + $testArgs)
        if ($LASTEXITCODE -ne 0) { throw "Test compilation failed." }
        if ($Test) {
            $runArgs = Join-Path $build "test-run.args"
            @("--add-modules","jdk.httpserver","-Dfile.encoding=UTF-8","-classpath",(Quote-JavaArg ($tests + ";" + $classes + ";" + $classpath)),"dev.gitcommitforai.CoreTests") | Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
            & $java ("@" + $runArgs)
            if ($LASTEXITCODE -ne 0) { throw "Core tests failed." }
            foreach ($phase in @("write", "verify")) {
                @("-Dfile.encoding=UTF-8","-classpath",(Quote-JavaArg ($tests + ";" + $classes + ";" + $classpath)),"dev.gitcommitforai.PersistenceProbe",$phase,(Quote-JavaArg (Join-Path $build "persistence-test.xml"))) | Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
                & $java ("@" + $runArgs)
                if ($LASTEXITCODE -ne 0) { throw "Cross-process persistence test failed: $phase" }
            }
        }
        if ($Preview) {
            $runArgs = Join-Path $build "preview-run.args"
            @("-Djava.awt.headless=true","-Dfile.encoding=UTF-8","-classpath",(Quote-JavaArg ($tests + ";" + $classes + ";" + $classpath)),"dev.gitcommitforai.UiPreview",(Quote-JavaArg $build)) | Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
            & $java ("@" + $runArgs)
            if ($LASTEXITCODE -ne 0) { throw "UI preview failed." }
        }
    }
}
$zip = Join-Path $dist "git-commit-for-ai-1.0.0.zip"
if (Test-Path -LiteralPath $zip) { [IO.File]::Delete($zip) }
[IO.Compression.ZipFile]::CreateFromDirectory((Join-Path $build "package"), $zip)
Write-Output ("BUILT " + $zip)

