param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Target
)

$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path
$targetRoot = [IO.Path]::GetFullPath($Target)
if ($sourceRoot -eq $targetRoot) { throw 'Source and target must be different.' }
New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null

$rootFiles = @(
    'options.txt','servers.dat','servers.dat_old','command_history.txt','patchouli_data.json',
    'ponders_watched.json','TrashSlotSaveState.json','usernamecache.json','usercache.json','vss-lod-presence.dat'
)
$personalFolders = @(
    '.sable','.voxy','blueprints','datapacks','dynamic-data-pack-cache','dynamic-resource-pack-cache',
    'emotes','figura','moddata','profileImage','schematics','xaero','XaeroWaypoints_BACKUP240807'
)

foreach ($name in $rootFiles) {
    $from = Join-Path $sourceRoot $name
    if (Test-Path -LiteralPath $from -PathType Leaf) { Copy-Item -LiteralPath $from -Destination (Join-Path $targetRoot $name) -Force }
}
foreach ($name in $personalFolders) {
    $from = Join-Path $sourceRoot $name
    if (-not (Test-Path -LiteralPath $from -PathType Container)) { continue }
    $to = Join-Path $targetRoot $name
    New-Item -ItemType Directory -Path $to -Force | Out-Null
    Get-ChildItem -LiteralPath $from -Force | Copy-Item -Destination $to -Recurse -Force -ErrorAction Stop
}

$marker = Join-Path $targetRoot '.erdvyn-personal-migration.txt'
@(
    'source_profile=The Heir'
    "source_path=$sourceRoot"
    "migrated_at=$([DateTimeOffset]::Now.ToString('O'))"
    'excluded=automodpack,saves,logs,crash-reports,screenshots,Distant_Horizons_server_data'
) | Set-Content -LiteralPath $marker -Encoding UTF8

[pscustomobject]@{
    Target = $targetRoot
    Files = (Get-ChildItem -LiteralPath $targetRoot -File -Recurse).Count
    MiB = [math]::Round(((Get-ChildItem -LiteralPath $targetRoot -File -Recurse | Measure-Object Length -Sum).Sum / 1MB), 1)
}
