param(
    [switch]$SkipVideos,
    [string]$Version = '2.1.21'
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot=$env:JAVA_HOME
if([string]::IsNullOrWhiteSpace($javaRoot)){
    $javaRoot='C:\Program Files\Java\jdk-21.0.10'
}
if(-not (Test-Path -LiteralPath "$javaRoot\bin\jpackage.exe")){throw 'Java 21 JDK with jpackage is required'}
$env:JAVA_HOME=$javaRoot
$env:Path="$javaRoot\bin;$env:Path"
Push-Location $projectRoot
try {
    $env:RELEASE_VERSION=$Version
    & .\gradlew.bat installDist --no-daemon
    if($LASTEXITCODE -ne 0){throw 'Gradle build failed'}
    $work=Join-Path $projectRoot 'build\release-work'
    $input=Join-Path $work 'input'
    $runtime=Join-Path $work 'runtime'
    $release=Join-Path $projectRoot 'build\release'
    foreach($target in @($input,$runtime,(Join-Path $release 'Erdvyn Launcher'))){
        $absolute=[IO.Path]::GetFullPath($target)
        if(-not $absolute.StartsWith([IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))+'\',[StringComparison]::OrdinalIgnoreCase)){throw "Unsafe build target: $absolute"}
    }
    if(Test-Path -LiteralPath $input){Remove-Item -LiteralPath $input -Recurse -Force}
    New-Item -ItemType Directory -Force -Path $input,$release | Out-Null
    Copy-Item -Path (Join-Path $projectRoot 'build\install\erdvyn-launcher\lib\*') -Destination $input -Force
    $mainJar=Get-ChildItem -LiteralPath $input -Filter 'ErdvynLauncher-*.jar' | Select-Object -First 1
    if(-not $mainJar){throw 'Launcher jar is missing'}
    $iconPng=Join-Path $projectRoot 'src\main\resources\assets\erdvyn-app-icon.png'
    $iconIco=Join-Path $projectRoot 'src\main\resources\assets\erdvyn-app-icon.ico'
    New-Item -ItemType Directory -Force -Path (Join-Path $work 'icon-tool') | Out-Null
    & "$javaRoot\bin\javac.exe" -d (Join-Path $work 'icon-tool') (Join-Path $projectRoot 'tools\IconGenerator.java')
    & "$javaRoot\bin\java.exe" -cp (Join-Path $work 'icon-tool') IconGenerator (Join-Path $projectRoot 'src\main\resources\assets\erdvyn-logo.png') $iconPng
    & "$javaRoot\bin\java.exe" -cp (Join-Path $work 'icon-tool') IconGenerator (Join-Path $projectRoot 'src\main\resources\assets\erdvyn-logo.png') $iconIco
    if(Test-Path -LiteralPath $runtime){Remove-Item -LiteralPath $runtime -Recurse -Force}
    $modules=((Get-ChildItem -LiteralPath "$javaRoot\jmods" -Filter '*.jmod' -File | ForEach-Object BaseName) + @('javafx.base','javafx.graphics','javafx.media','javafx.swing')) -join ','
    & "$javaRoot\bin\jlink.exe" --module-path "$javaRoot\jmods;$input" --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $runtime
    if($LASTEXITCODE -ne 0){throw 'Runtime image build failed'}
    $appImage=Join-Path $release 'Erdvyn Launcher'
    if(Test-Path -LiteralPath $appImage){Remove-Item -LiteralPath $appImage -Recurse -Force}
    & "$javaRoot\bin\jpackage.exe" --type app-image --dest $release --input $input --name 'Erdvyn Launcher' --main-jar $mainJar.Name --main-class 'tr.erdvyn.launcher.ErdvynLauncher' --app-version $Version --vendor 'Erdvyn' --description 'Erdvyn: The Frontier Launcher' --icon $iconIco --runtime-image $runtime --java-options '-Dprism.order=d3d,sw'
    if($LASTEXITCODE -ne 0){throw 'Application image build failed'}
    if(-not $SkipVideos){
        $ffmpeg=Get-ChildItem -LiteralPath "$env:USERPROFILE\Downloads\ffmpeg-master-latest-win64-gpl-shared" -Recurse -Filter ffmpeg.exe | Select-Object -First 1 -ExpandProperty FullName
        $source=Join-Path $env:USERPROFILE 'Videos\ErdvynLauncher'
        $videoDest=Join-Path $appImage 'videos'
        New-Item -ItemType Directory -Force -Path $videoDest | Out-Null
        if($ffmpeg -and (Test-Path -LiteralPath $source)){
            Get-ChildItem -LiteralPath $source -Filter 'erdvyn-*.mp4' | Sort-Object Name | ForEach-Object {
                $target=Join-Path $videoDest $_.Name
                & $ffmpeg -hide_banner -loglevel error -y -i $_.FullName -t 45 -vf 'scale=720:378:force_original_aspect_ratio=increase,crop=720:378' -an -c:v libx264 -preset veryfast -crf 32 -movflags +faststart $target
                if($LASTEXITCODE -ne 0){throw "Video compression failed: $($_.Name)"}
            }
        }
    }
    & (Join-Path $PSScriptRoot 'build-installer.ps1') -AppImage $appImage -Version $Version
    if($LASTEXITCODE -ne 0){throw 'Installer build failed'}
    Write-Host "RELEASE=$appImage"
} finally { Pop-Location }
