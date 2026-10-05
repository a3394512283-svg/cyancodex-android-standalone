# 构建两个默认插件 JAR: javac 编译(对 API 桩) -> d8 转 dex -> jar 打包 -> 放入 App assets
#
# 路径解析(不再硬编码机器路径):
#   ANDROID_HOME / ANDROID_SDK_ROOT -> Android SDK (默认 $env:LOCALAPPDATA\Android\Sdk)
#   JAVA_HOME                       -> JDK, 需含 bin\javac.exe 与 bin\jar.exe
$ErrorActionPreference = 'Continue'
$PSNativeCommandUseErrorActionPreference = $false

$root = $PSScriptRoot
$out = "$root\build"
$stubOut = "$out\stub"
$classesOut = "$out\classes"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$jdkBin = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin" } else { '' }

$javac = "$jdkBin\javac.exe"
$jarExe = "$jdkBin\jar.exe"
$androidJar = "$sdk\platforms\android-36\android.jar"
$d8 = "$sdk\build-tools\36.1.0\d8.bat"
$assets = "$root\..\android\app\src\main\assets\plugins"

if (-not $jdkBin) { Write-Output "ERROR JAVA_HOME 未设置 (需要含 javac/jar 的 JDK)"; exit 1 }
if (-not (Test-Path $androidJar)) { Write-Output "ERROR android.jar missing: $androidJar"; exit 1 }

New-Item -ItemType Directory -Force -Path $stubOut | Out-Null
New-Item -ItemType Directory -Force -Path $classesOut | Out-Null
New-Item -ItemType Directory -Force -Path $assets | Out-Null

# 1) 编译 API 桩 (引用 android.content.Context, 需要 android.jar)
$stubSrc = Get-ChildItem "$root\stub" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& $javac --release 8 -Xlint:-options -cp $androidJar -d $stubOut $stubSrc
if ($LASTEXITCODE -ne 0) { Write-Output "stub compile FAILED"; exit 1 }
Write-Output "stub compiled"

# 2) 编译插件
$bgSrc = Get-ChildItem "$root\background" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$devSrc = Get-ChildItem "$root\dev" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$builderSrc = Get-ChildItem "$root\builder" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
& $javac --release 8 -Xlint:-options -cp "$stubOut;$androidJar" -d $classesOut $bgSrc
& $javac --release 8 -Xlint:-options -cp "$stubOut;$androidJar" -d $classesOut $devSrc
& $javac --release 8 -Xlint:-options -cp "$stubOut;$androidJar" -d $classesOut $builderSrc
if ($LASTEXITCODE -ne 0) { Write-Output "plugins compile FAILED"; exit 1 }
Write-Output "plugins compiled"

function Build-Plugin([string]$name, [string]$implFqn, [string]$filter) {
    $tmp = "$out\$name"
    $dexOut = "$tmp\dex"
    # 每次重建都清空, 否则 jar 会把上一次的产物也打进去 (jar 里套 jar)
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $tmp, $dexOut | Out-Null
    $cls = Get-ChildItem $classesOut -Recurse -Filter '*.class' | Where-Object { $_.FullName -match $filter } | ForEach-Object { $_.FullName }
    & $d8 --release --lib $androidJar --output $dexOut $cls
    $svc = "$tmp\META-INF\services"
    New-Item -ItemType Directory -Force -Path $svc | Out-Null
    Copy-Item "$dexOut\classes.dex" "$tmp\classes.dex" -Force
    Remove-Item $dexOut -Recurse -Force -ErrorAction SilentlyContinue
    Set-Content -Path "$svc\com.canglan.cyancodex.plugin.CyanPlugin" -Value $implFqn -Encoding ascii -NoNewline
    # 产物先放到 $tmp 外面, 避免被打进自己
    & $jarExe cf "$out\$name.jar" -C $tmp .
    Copy-Item "$out\$name.jar" $assets -Force
    $sz = (Get-Item "$out\$name.jar").Length
    Write-Output "built $name.jar $sz bytes"
}

Build-Plugin 'background' 'dev.cyancodex.plugins.background.BackgroundPlugin' 'plugins[\\/]background[\\/]'
Build-Plugin 'dev' 'dev.cyancodex.plugins.dev.DevExamplePlugin' 'plugins[\\/]dev[\\/]'
Build-Plugin 'builder' 'dev.cyancodex.blocks.GeneratedPlugin' 'blocks[\\/]GeneratedPlugin'

# builder.jar 是"插件工坊"的运行时模板, 不是可直接加载的插件 (它没有行为规格),
# 因此放到 assets 根目录而不是 assets/plugins, 避免被当成一个空插件加载
$tpl = Join-Path (Split-Path $assets -Parent) 'plugin_template.jar'
Move-Item "$assets\builder.jar" $tpl -Force
Write-Output "builder template -> assets/plugin_template.jar"

Get-ChildItem $assets | ForEach-Object { Write-Output ("asset: " + $_.Name + " (" + $_.Length + "B)") }
