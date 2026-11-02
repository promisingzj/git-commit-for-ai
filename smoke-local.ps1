param([Parameter(Mandatory = $true)][string]$IdeHome)
$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
$IdeHome = (Resolve-Path -LiteralPath $IdeHome).Path
$sandbox = Join-Path $projectRoot "build/ide-smoke"
$classes = Join-Path $sandbox "smoke-classes"
$plugins = Join-Path $sandbox "plugins"
New-Item -ItemType Directory -Force -Path $classes,(Join-Path $classes "META-INF"),$plugins | Out-Null
$sdkJars = @(Get-ChildItem -LiteralPath (Join-Path $IdeHome "lib") -Recurse -Filter "*.jar") + @(Get-ChildItem -LiteralPath (Join-Path $IdeHome "plugins/vcs-git/lib") -Recurse -Filter "*.jar")
$classpath = ($sdkJars.FullName -join ";").Replace([char]92,[char]47)
function Quote-JavaArg([string]$value) { return '"' + $value.Replace([char]92,[char]47) + '"' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "src/main/java") -Recurse -Filter "*.java") + @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "src/smoke/java") -Recurse -Filter "*.java")
$compileArgs = Join-Path $sandbox "compile.args"
@("--release","21","-encoding","UTF-8","-classpath",(Quote-JavaArg $classpath),"-d",(Quote-JavaArg $classes)) + @($sources | ForEach-Object { Quote-JavaArg $_.FullName }) | Set-Content -LiteralPath $compileArgs -Encoding utf8NoBOM
& (Join-Path $IdeHome "jbr/bin/javac.exe") ("@" + $compileArgs)
if ($LASTEXITCODE -ne 0) { throw "Smoke starter compilation failed." }
# Only the test starter is packaged here; production classes are supplied by the plugin dependency.
$testJarRoot = Join-Path $sandbox "test-jar"
New-Item -ItemType Directory -Force -Path (Join-Path $testJarRoot "META-INF"),(Join-Path $testJarRoot "dev/gitcommitforai") | Out-Null
Copy-Item -LiteralPath (Join-Path $classes "dev/gitcommitforai/smoke") -Destination (Join-Path $testJarRoot "dev/gitcommitforai") -Recurse -Force
'<idea-plugin><id>dev.gitcommitforai.smoke</id><name>git commit for ai smoke tests</name><version>1.0</version><vendor>Local tests</vendor><idea-version since-build="243"/><depends>com.intellij.modules.platform</depends><depends>dev.gitcommitforai.idea</depends><extensions defaultExtensionNs="com.intellij"><appStarter id="git-commit-for-ai-smoke" implementation="dev.gitcommitforai.smoke.IdeSmoke"/></extensions></idea-plugin>' | Set-Content -LiteralPath (Join-Path $testJarRoot "META-INF/plugin.xml") -Encoding utf8NoBOM
Add-Type -AssemblyName System.IO.Compression
$smokeJar = Join-Path $plugins "smoke-tests/lib/smoke-tests.jar"
New-Item -ItemType Directory -Force -Path (Split-Path $smokeJar) | Out-Null
if (Test-Path -LiteralPath $smokeJar) { [IO.File]::Delete($smokeJar) }
[IO.Compression.ZipFile]::CreateFromDirectory($testJarRoot, $smokeJar)
Copy-Item -LiteralPath (Join-Path $projectRoot "build/package/git-commit-for-ai") -Destination $plugins -Recurse -Force
$product = Get-Content -LiteralPath (Join-Path $IdeHome "product-info.json") -Raw | ConvertFrom-Json
$launch = $product.launch | Where-Object { $_.os -eq "Windows" -and $_.arch -eq "amd64" } | Select-Object -First 1
$bootCp = ($launch.bootClassPathJarNames | ForEach-Object { Join-Path $IdeHome ("lib/" + $_) }) -join ";"
$baseArgs = @("-Xmx1536m", "-Djava.awt.headless=true", "-Didea.home.path=$IdeHome", "-Didea.config.path=$sandbox/config", "-Didea.system.path=$sandbox/system", "-Didea.plugins.path=$plugins", "-Didea.log.path=$sandbox/log", "-Dgitcommitforai.smoke.output=$sandbox", "-Didea.is.internal=true", "-Didea.load.plugins.id=dev.gitcommitforai.smoke", "-Didea.initially.ask.config=false", "-Didea.vendor.name=JetBrains", "-Didea.splash=false", "-Dide.no.platform.update=true")
$baseArgs += @($launch.additionalJvmArguments | ForEach-Object { $_.Replace('%IDE_HOME%', $IdeHome) })
foreach ($phase in @("write", "verify")) {
    $runArgs = Join-Path $sandbox ("run-" + $phase + ".args")
    @($baseArgs | ForEach-Object { Quote-JavaArg $_ }) + @("-classpath",(Quote-JavaArg $bootCp),"com.intellij.idea.Main","git-commit-for-ai-smoke",$phase) | Set-Content -LiteralPath $runArgs -Encoding utf8NoBOM
    & (Join-Path $IdeHome "jbr/bin/java.exe") ("@" + $runArgs)
    if ($LASTEXITCODE -ne 0) { throw "IDE smoke phase failed: $phase" }
}
Write-Output "IDE SMOKE PASSED: plugin loaded and settings persisted across two isolated IDE processes."



