# Join intermediary (obf<->intermediary) with Mojang client.txt (obf<->Mojmap) into intermediary->Mojmap,
# then assert that the result names real classes in the merged base.
#
# Why a JOIN: intermediary-1.21.1-v2.jar's tiny file is "official intermediary", and its "official" column is the
# OBFUSCATED name (c, a, ...), not Mojmap. So class_XXXX cannot be turned into net/minecraft/world/item/ItemStack
# with that file alone. Mojang's client.txt supplies obf->Mojmap; joining through the obf name gives the pair.

$ErrorActionPreference = 'Stop'
$dir   = if ($args.Count -ge 1) { $args[0] } else { Join-Path $PSScriptRoot 'work' }
$tiny  = "$dir\tiny-extract\mappings.tiny"
$txt   = "$dir\client-1.21.1.txt"
$merged = if ($args.Count -ge 2) { $args[1] } else { 'forbric-kernel\build\merge1211\merged-1.21.1-collect.jar' }
if (-not (Test-Path $merged)) {
    $merged = 'E:\project\nfrt-probe-1211\build\moddev\artifacts\neoforge-21.1.249-merged.jar'
}

# --- 1. Mojang client mappings -------------------------------------------------------------------------
if (-not (Test-Path $txt)) {
    # The 1.21.1 version json carries the client mappings url; fetch it, then the file.
    $vj = Invoke-WebRequest -Uri 'https://piston-meta.mojang.com/v1/packages/499680d659b7a0ccb2fa9a809c345c5baf2ace7d/1.21.1.json' -TimeoutSec 60 -UseBasicParsing
    $meta = $vj.Content | ConvertFrom-Json
    $url = $meta.downloads.client_mappings.url
    Write-Host "[fetch] $url"
    Invoke-WebRequest -Uri $url -TimeoutSec 180 -OutFile $txt -UseBasicParsing
}
Write-Host ("[client.txt] {0:N0} bytes" -f (Get-Item $txt).Length)

# --- 2. obf -> Mojmap from client.txt -----------------------------------------------------------------
# Format:  <obf class> -> <mojmap class>:
#            <obf member> -> <mojmap member>
$obfToMojmap = @{}
$currentObf = $null
foreach ($line in [System.IO.File]::ReadLines($txt)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.StartsWith(' ') -or $line.StartsWith("`t")) {
        continue   # member lines: not needed for the class-name assertion
    }
    # client.txt is "<mojmap> -> <obf>:", so the RIGHT side is the obfuscated name.
    $arrow = $line.IndexOf(' -> ')
    if ($arrow -lt 0) { continue }
    $mojmap = $line.Substring(0, $arrow).Trim()
    $obf    = $line.Substring($arrow + 4).Trim().TrimEnd(':')
    if ($mojmap -and $obf) { $obfToMojmap[$obf] = $mojmap }
}
Write-Host ("[client.txt] obf->mojmap classes: {0:N0}" -f $obfToMojmap.Count)

# --- 3. intermediary -> obf from tiny, then join ------------------------------------------------------
$intToObf = @{}
foreach ($line in [System.IO.File]::ReadLines($tiny)) {
    if ($line.StartsWith('#')) { continue }
    if ([string]::IsNullOrEmpty($line) -or $line.StartsWith(' ')) { continue }
    # tiny v2 class line is "c`t<official>`t<intermediary>": "c" is the class marker, not a name.
    $f = $line.Split("`t")
    if ($f.Count -ne 3 -or $f[0] -ne 'c') { continue }
    $obf = $f[1]
    $int = $f[2]
    if ($obf -and $int) { $intToObf[$int] = $obf }
}
Write-Host ("[tiny] intermediary->obf classes: {0:N0}" -f $intToObf.Count)

$intToMojmap = @{}
foreach ($k in $intToObf.Keys) {
    $obf = $intToObf[$k]
    if ($obfToMojmap.ContainsKey($obf)) { $intToMojmap[$k] = $obfToMojmap[$obf] }
}
Write-Host ("[join] intermediary->mojmap classes: {0:N0}" -f $intToMojmap.Count)

# --- 4. merged base class names -----------------------------------------------------------------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($merged)
$names = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($e in $z.Entries) {
    if ($e.FullName -like '*.class') {
        [void]$names.Add(($e.FullName -replace '\.class$', ''))
    }
}
$z.Dispose()
Write-Host ("[merged] {0} classes in {1}" -f $names.Count, (Split-Path $merged -Leaf))

# --- 5. assert known intermediaries resolve to real classes in the base --------------------------------
# class_1799 = ItemStack, class_1937 = Level, class_2338 = BlockPos, class_2960 = ResourceLocation,
# class_3218 = ServerLevel, class_2680 = BlockState. Asserted by resolving, not by assuming.
$probe = @('class_1799','class_1937','class_2338','class_2960','class_3218','class_2680','class_2586','class_3222')
$ok = 0; $miss = 0; $unresolved = 0
foreach ($p in $probe) {
    $key = "net/minecraft/$p"
    if (-not $intToMojmap.ContainsKey($key)) { Write-Host ("  {0,-18} UNRESOLVED (not in join)" -f $p); $unresolved++; continue }
    $moj = $intToMojmap[$key]
    $found = $names.Contains(($moj -replace '\.', '/'))
    if ($found) { Write-Host ("  {0,-18} -> {1,-48} PRESENT" -f $p, $moj); $ok++ }
    else        { Write-Host ("  {0,-18} -> {1,-48} MISSING" -f $p, $moj); $miss++ }
}
Write-Host ""
Write-Host ("RESULT: present={0} missing={1} unresolved={2}" -f $ok, $miss, $unresolved)
if ($ok -gt 0 -and $miss -eq 0) { Write-Host "VERDICT: PASS - the join resolves intermediaries to classes the merged base actually contains" }
elseif ($ok -gt 0)             { Write-Host "VERDICT: PARTIAL - the join works but some names are absent from the base" }
else                           { Write-Host "VERDICT: FAIL - no intermediary resolved to a class in the base" }
