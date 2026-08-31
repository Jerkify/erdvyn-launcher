param(
    [Parameter(Mandatory=$true)][string]$Installer,
    [Parameter(Mandatory=$true)][string]$Version,
    [string]$InstallerUrl,
    [string]$ReleaseNotes = '',
    [string]$Output
)

$ErrorActionPreference = 'Stop'
$resolvedInstaller = (Resolve-Path -LiteralPath $Installer).Path
if ([string]::IsNullOrWhiteSpace($InstallerUrl)) {
    $InstallerUrl = '/api/launcher/files/' + [uri]::EscapeDataString((Split-Path -Leaf $resolvedInstaller))
}
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path (Split-Path -Parent $resolvedInstaller) 'launcher-manifest.json'
}
$manifest = [ordered]@{
    version = $Version
    installer_url = $InstallerUrl
    sha256 = (Get-FileHash -LiteralPath $resolvedInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
    size = (Get-Item -LiteralPath $resolvedInstaller).Length
    release_notes = $ReleaseNotes
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $Output -Encoding UTF8
Write-Host "LAUNCHER_MANIFEST=$((Resolve-Path -LiteralPath $Output).Path)"
