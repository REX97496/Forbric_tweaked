# Member-level join, with the tiny v2 field order MEASURED rather than assumed.
#
# The previous attempt read tiny member lines as [marker, obf, intermediary] and got 1,738 unique "member names"
# out of 41,243 method lines -- because field 2 is the DESCRIPTOR, not a name. Dumping raw bytes showed it:
#
#   <TAB> f <TAB> La; <TAB> a <TAB> field_40713
#    ind   mark  descriptor  obf   intermediary
#
# so after stripping the leading tab the four fields are [marker, descriptor, obf, intermediary].
#
# class lines are different: "c<TAB><obf><TAB><intermediary>" -- three fields, marker first, no descriptor.
#
# client.txt is "<mojmapClass> -> <obfClass>:" with members indented four spaces as
#   "<type> <mojmapField> -> <obfField>"                     for fields
#   "<l1>:<l2>:<ret> <mojmapMethod>(<args>) -> <obfMethod>"  for methods
# and "# {...}" annotation lines sit between a class line and its members -- treating those as class lines
# reset the current owner and silently zeroed every member under it.

$ErrorActionPreference = 'Stop'
$dir    = if ($args.Count -ge 1) { $args[0] } else { Join-Path $PSScriptRoot 'work' }
$merged = if ($args.Count -ge 2) { $args[1] } else { 'E:\Programs\Minecraft-Forbric-mod-loader-main\Minecraft-Forbric-mod-loader-main\forbric-kernel\build\merge1211\merged-1.21.1-collect.jar' }
$tiny   = Join-Path $dir 'tiny-extract\mappings.tiny'
$txt    = Join-Path $dir 'client-1.21.1.txt'

# --- 1. tiny: intermediary member -> (obf owner, obf member) ------------------------------------------
$intToObfOwner = @{}
$intMember     = @{}
$classCount = 0; $fieldCount = 0; $methodCount = 0; $odd = 0
$curOwnerInt = $null; $curOwnerObf = $null
foreach ($line in [System.IO.File]::ReadLines($tiny)) {
    if ([string]::IsNullOrEmpty($line)) { continue }
    $c0 = [int][char]$line[0]
    if ($c0 -eq 35) { continue }
    if ($c0 -eq 9) {
        $f = $line.Substring(1).Split("`t")
        if ($f.Count -lt 4 -or (-not $curOwnerInt)) { $odd++; continue }
        $marker = $f[0]; $obfMember = $f[2]; $intWM = $f[3]
        if ($marker -eq 'f') { $fieldCount++ } elseif ($marker -eq 'm') { $methodCount++ } else { $odd++; continue }
        if ($obfMember -and $intWM) { $intMember["$curOwnerInt|$marker|$intWM"] = $obfMember }
        continue
    }
    $f = $line.Split("`t")
    if ($f.Count -eq 3 -and $f[0] -eq 'c') {
        $curOwnerObf = $f[1]; $curOwnerInt = $f[2]
        $intToObfOwner[$curOwnerInt] = $curOwnerObf
        $classCount++
    } else { $curOwnerInt = $null; $curOwnerObf = $null }
}
$uniqInt = ($intMember.Keys | ForEach-Object { $_.Substring($_.IndexOf('|') + 1) } | Sort-Object -Unique).Count
Write-Host ("[tiny] classes={0} fields={1} methods={2} odd={3}" -f $classCount, $fieldCount, $methodCount, $odd)
Write-Host ("[tiny] member pairs={0:N0}  unique intermediary names={1:N0}" -f $intMember.Count, $uniqInt)

# --- 2. client.txt: (obf owner, obf member) -> mojmap member -----------------------------------------
$obfToMojmapClass = @{}
$member = @{}
$curObf = $null
$memberLines = 0
foreach ($line in [System.IO.File]::ReadLines($txt)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.StartsWith('#')) { continue }
    if ([int][char]$line[0] -eq 32) {
        if (-not $curObf) { continue }
        $arrow = $line.IndexOf(' -> ')
        if ($arrow -lt 0) { continue }
        $left  = $line.Substring(0, $arrow).Trim()
        $right = $line.Substring($arrow + 4).Trim()
        $tokens = $left -split '\s+'
        if ($tokens.Count -lt 2) { continue }
        $moj = $tokens[$tokens.Count - 1]
        $paren = $moj.IndexOf('(')
        if ($paren -ge 0) { $moj = $moj.Substring(0, $paren) }
        if (-not $moj -or $moj -eq '<init>' -or $moj -eq '<clinit>') { continue }
        # "<l1>:<l2>:<ret> <name>" is a METHOD line; "<type> <name>" is a FIELD line. Both obfuscate their
        # names to the same short identifiers, so a key without this dimension lets a field take a method's
        # mapping -- measured: field_18276 had come out as "copyPosition".
        $isMethod = $left -match '^\d+:\d+:'
        $kind = if ($isMethod) { 'm' } else { 'f' }
        $member["$curObf|$kind|$right"] = $moj
        $memberLines++
        continue
    }
    $arrow = $line.IndexOf(' -> ')
    if ($arrow -lt 0) { $curObf = $null; continue }
    $mojClass = $line.Substring(0, $arrow).Trim()
    $obfClass = $line.Substring($arrow + 4).Trim().TrimEnd(':')
    if ($mojClass -and $obfClass) { $obfToMojmapClass[$obfClass] = $mojClass; $curObf = $obfClass }
}
Write-Host ("[client.txt] classes={0:N0} member lines={1:N0}" -f $obfToMojmapClass.Count, $memberLines)

# --- 3. join ----------------------------------------------------------------------------------------
$joined = @{}
foreach ($k in $intMember.Keys) {
    $bar = $k.IndexOf('|')
    $ownerInt = $k.Substring(0, $bar)
    if (-not $intToObfOwner.ContainsKey($ownerInt)) { continue }
    $ownerObf = $intToObfOwner[$ownerInt]
    $parts = $k.Split("|")     # intermediaryOwner | f-or-m | intermediaryMember
    $kind = $parts[1]
    $mk = "$ownerObf|$kind|$($intMember[$k])"
    if ($member.ContainsKey($mk)) { $joined[$k] = $member[$mk] }
}
Write-Host ("[join] intermediary member pairs -> mojmap: {0:N0}" -f $joined.Count)

# --- 4. assert with probes TAKEN FROM TINY, not guessed ----------------------------------------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$classes = New-Object 'System.Collections.Generic.HashSet[string]'
$z = [System.IO.Compression.ZipFile]::OpenRead($merged)
foreach ($e in $z.Entries) { if ($e.FullName -like '*.class') { [void]$classes.Add(($e.FullName -replace '\.class$', '')) } }
$z.Dispose()
Write-Host ("[merged] {0:N0} classes" -f $classes.Count)

$probes = @($intMember.Keys | Select-Object -First 8)
$ok = 0; $miss = 0; $nojoin = 0
foreach ($p in $probes) {
    $pp = $p.Split("|"); $ownerInt = $pp[0]; $kind = $pp[1]; $memberInt = $pp[2]
    if (-not $intToObfOwner.ContainsKey($ownerInt)) { Write-Host ("  {0,-30} NO-OWNER" -f $memberInt); $miss++; continue }
    $ownerMoj = $obfToMojmapClass[$intToObfOwner[$ownerInt]]
    if (-not $ownerMoj) { Write-Host ("  {0,-30} NO-MOJMAP-OWNER" -f $memberInt); $miss++; continue }
    $ownerPath = $ownerMoj -replace '\.', '/'
    $ownerPresent = $classes.Contains($ownerPath)
    if (-not $joined.ContainsKey($p)) { Write-Host ("  {0,-30} {1,-30} NO-JOIN" -f $memberInt, $ownerMoj); $nojoin++; continue }
    if ($ownerPresent) { Write-Host ("  {0,-30} {1,-30} -> {2}" -f $memberInt, $ownerMoj, $joined[$p]); $ok++ }
    else { Write-Host ("  {0,-30} {1,-30} OWNER-MISSING" -f $memberInt, $ownerMoj); $miss++ }
}
Write-Host ""
Write-Host ("RESULT: joined={0:N0}  probes ok={1} bad={2} no-join={3}" -f $joined.Count, $ok, $miss, $nojoin)
if ($joined.Count -gt 10000 -and $ok -gt 0 -and $miss -eq 0) {
    Write-Host "VERDICT: PASS - member mappings join in bulk and every probed owner is a real class in the base"
} else {
    Write-Host "VERDICT: FAIL"
}
Write-Host ""
Write-Host "NOT ASSERTED: that each mapped member NAME is correct -- that needs parsing the class file and matching"
Write-Host "the descriptor, which this script does not do. Owner-level presence and bulk join volume are what it proves."
