param(
    [string]$AppImage,
    [string]$Version = '2.1.15'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($AppImage)) {
    $AppImage = Join-Path $projectRoot 'build\release\Erdvyn Launcher'
}
$AppImage = (Resolve-Path -LiteralPath $AppImage).Path
$dist = Join-Path $projectRoot 'build\dist'
$work = Join-Path $projectRoot 'build\installer-work'
$iss = Join-Path $work 'erdvyn-launcher.iss'
$icon = Join-Path $projectRoot 'src\main\resources\assets\erdvyn-app-icon.ico'
New-Item -ItemType Directory -Force -Path $dist,$work | Out-Null

$iscc = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
    (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 compiler was not found' }

$source = $AppImage
$output = $dist
$setupIcon = $icon
$issContent = @"
#define AppVersion "$Version"
[Setup]
AppId={{7B391B20-8F37-4D35-BE2D-8CD62DE15A2A}
AppName=Erdvyn Launcher
AppVersion={#AppVersion}
AppPublisher=Erdvyn
AppPublisherURL=https://erdvyn.net
DefaultDirName={localappdata}\Programs\Erdvyn Launcher
DefaultGroupName=Erdvyn Launcher
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=$output
OutputBaseFilename=Erdvyn-Launcher-Setup-$Version
SetupIconFile=$setupIcon
UninstallDisplayIcon={app}\Erdvyn Launcher.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
VersionInfoCompany=Erdvyn
VersionInfoDescription=Erdvyn Launcher Setup
VersionInfoProductName=Erdvyn Launcher
VersionInfoProductVersion=$Version
VersionInfoVersion=$Version

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "$source\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Erdvyn Launcher"; Filename: "{app}\Erdvyn Launcher.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Erdvyn Launcher"; Filename: "{app}\Erdvyn Launcher.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Run]
Filename: "{app}\Erdvyn Launcher.exe"; Description: "{cm:LaunchProgram,Erdvyn Launcher}"; Flags: nowait postinstall skipifsilent
"@
Set-Content -LiteralPath $iss -Value $issContent -Encoding UTF8

& $iscc $iss
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup installer build failed' }
$installer = Join-Path $dist "Erdvyn-Launcher-Setup-$Version.exe"
if (-not (Test-Path -LiteralPath $installer)) { throw 'Installer output is missing' }

$zip = Join-Path $dist "Erdvyn-Launcher-Portable-$Version.zip"
Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $AppImage '*') -DestinationPath $zip -CompressionLevel Optimal
$hashes = @($installer,$zip) | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_ -Algorithm SHA256
    "{0}  {1}" -f $hash.Hash.ToLowerInvariant(), (Split-Path -Leaf $_)
}
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashes -Encoding ASCII
& (Join-Path $PSScriptRoot 'New-LauncherManifest.ps1') -Installer $installer -Version $Version -ReleaseNotes 'Player graphics, controls and mod settings are preserved between launches'
Write-Host "INSTALLER=$installer"
Write-Host "PORTABLE=$zip"
