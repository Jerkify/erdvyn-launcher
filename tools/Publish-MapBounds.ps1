$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stage=Join-Path $workspace 'outputs/map-rollout-2.1.18'
New-Item -ItemType Directory -Force -Path $stage | Out-Null
$base='https://api.erdvyn.net'
$original=Invoke-WebRequest "$base/api/pack/manifest"
[IO.File]::WriteAllText((Join-Path $stage 'manifest-before.json'),$original.Content)
$manifest=$original.Content | ConvertFrom-Json
$relative='config/erdvyn-map-bounds.properties'
$file=Join-Path $workspace 'ERDVYN_RELEASES/erdvyn-ui-0.2.2-gears-0.5.19/config/erdvyn-map-bounds.properties'
$hash=(Get-FileHash -LiteralPath $file).Hash.ToLowerInvariant()
$entry=[pscustomobject]@{path=$relative;sha256=$hash;size=(Get-Item $file).Length;url="$base/api/pack/files/$relative";managed=$true}
# Retain every unrelated manifest field and entry. Remove only the retired duplicate Gears mod.
$manifest.files=@($manifest.files | Where-Object { $_.path -ne $relative -and $_.path -ne 'mods/erdvyn_gears-0.4.37.jar' })+@($entry)
$manifest.version='2026.09.10.map.2.1.18'
$encoded=$manifest | ConvertTo-Json -Depth 40
[IO.File]::WriteAllText((Join-Path $stage 'manifest-after.json'),$encoded,[Text.UTF8Encoding]::new($false))
$credential=(Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'Erdvyn/server-secret.dpapi') -Raw).Trim() | ConvertTo-SecureString
$secret=[Net.NetworkCredential]::new('',$credential).Password
$headers=@{'X-Erdvyn-Server-Secret'=$secret;'X-Erdvyn-Sha256'=$hash}
Invoke-WebRequest "$base/api/admin/files/pack/files/$relative" -Method Put -Headers $headers -InFile $file -ContentType 'application/octet-stream' | Out-Null
$latest=Invoke-WebRequest "$base/api/pack/manifest"
if($latest.Content -ne $original.Content){throw 'Manifest changed concurrently; abort publishing and merge again'}
$output=Join-Path $stage 'manifest-after.json'
$headers['X-Erdvyn-Sha256']=(Get-FileHash $output).Hash.ToLowerInvariant()
Invoke-WebRequest "$base/api/admin/files/pack/manifest.json" -Method Put -Headers $headers -InFile $output -ContentType 'application/json' | Out-Null
$live=Invoke-RestMethod "$base/api/pack/manifest"
if($live.version -ne $manifest.version){throw 'Published manifest not visible'}
Write-Output "Published $($live.version), $(@($live.files).Count) entries; managed map bounds; removed retired duplicate Gears 0.4.37 manifest entry."
