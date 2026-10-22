# Emits the table KernelMappingResolver reads. THE MEMBER JOIN BELOW IS LOSSY -- see the note in
# join-members.ps1 and tools/namespace/join-keyed.py, which produces the same C/M/F format with the
# descriptor-keyed join (78,922/78,922 members, 0 ambiguous). Measured against the merged base, the table this
# script writes maps 4,025 pairs of distinct intermediary members onto a single Mojmap member; the
# descriptor-keyed table maps none. This script is left as it is so the numbers already recorded against its
# output stay reproducible; switching the runtime table to the corrected join is its own change.
# Emit the pre-joined mapping table that KernelMappingResolver reads via -Dforbric.mappings=<file>.
#
# The resolver deliberately does not parse tiny or client.txt itself: that would put build tooling on the startup
# path and give it a second implementation to drift from the one the join scripts assert against. This emits the
# already-joined result instead.
#
# Format, tab-separated, '#' comments:
#   C <intermediaryClass>                  <officialClass>
#   M <intermediaryOwner> <intermediaryMethod> <officialMethod>
#   F <intermediaryOwner> <intermediaryField>  <officialField>
#
# Field and method records stay separate because the obfuscated names are shared between the two kinds.

param(
    [string]$dir = 'E:\project\namespace-probe',
    [string]$out = 'E:\project\namespace-probe\mappings-1.21.1.tsv'
)

$ErrorActionPreference = 'Stop'
$tiny = Join-Path $dir 'tiny-extract\mappings.tiny'
$txt  = Join-Path $dir 'client-1.21.1.txt'

# --- tiny: intermediary class/member -> obf, with the field/method split ---------------------------------
$intToObfOwner = @{}
$memberIntToObf = @{}          # "intOwner|kind|intMember" -> obfMember
$curOwnerInt = $null; $curOwnerObf = $null
$fields = 0; $methods = 0
foreach ($line in [System.IO.File]::ReadLines($tiny)) {
    if ([string]::IsNullOrEmpty($line)) { continue }
    $c0 = [int][char]$line[0]
    if ($c0 -eq 35) { continue }
    if ($c0 -eq 9) {
        $f = $line.Substring(1).Split("`t")
        if ($f.Count -lt 4 -or (-not $curOwnerInt)) { continue }
        $marker = $f[0]; $obfMember = $f[2]; $intWM = $f[3]
        if ($marker -ne 'f' -and $marker -ne 'm') { continue }
        if ($obfMember -and $intWM) { $memberIntToObf["$curOwnerInt|$marker|$intWM"] = $obfMember }
        if ($marker -eq 'f') { $fields++ } else { $methods++ }
        continue
    }
    $f = $line.Split("`t")
    if ($f.Count -eq 3 -and $f[0] -eq 'c') {
        $curOwnerInt = $f[2]; $curOwnerObf = $f[1]
        $intToObfOwner[$curOwnerInt] = $curOwnerObf
    } else { $curOwnerInt = $null; $curOwnerObf = $null }
}
Write-Host ("[tiny] member pairs={0:N0} (f={1:N0} m={2:N0})  classes={3:N0}" -f $memberIntToObf.Count, $fields, $methods, $intToObfOwner.Count)

# --- client.txt: obf class -> mojmap, (obfOwner,kind,obfMember) -> mojmap member -------------------------
$obfToMojmapClass = @{}
$memberObfToMoj = @{}
$curObf = $null
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
        $kind = if ($left -match '^\d+:\d+:') { 'm' } else { 'f' }
        $memberObfToMoj["$curObf|$kind|$right"] = $moj
        continue
    }
    $arrow = $line.IndexOf(' -> ')
    if ($arrow -lt 0) { $curObf = $null; continue }
    $mojClass = $line.Substring(0, $arrow).Trim()
    $obfClass = $line.Substring($arrow + 4).Trim().TrimEnd(':')
    if ($mojClass -and $obfClass) { $obfToMojmapClass[$obfClass] = $mojClass; $curObf = $obfClass }
}
Write-Host ("[client.txt] classes={0:N0} members={1:N0}" -f $obfToMojmapClass.Count, $memberObfToMoj.Count)

# --- write the table --------------------------------------------------------------------------------------
$sw = [System.IO.StreamWriter]::new($out, $false, [System.Text.UTF8Encoding]::new($false))
$sw.WriteLine("# Pre-joined intermediary -> official (Mojmap) table for Minecraft 1.21.1.")
$sw.WriteLine("# Emitted by tools/namespace/emit-table.ps1 from Fabric intermediary + Mojang client mappings.")
$sw.WriteLine("# Read by KernelMappingResolver via -Dforbric.mappings=<this file>.")
$sw.WriteLine("# C <intermediaryClass> <officialClass> | M <intOwner> <intMethod> <officialMethod> | F <intOwner> <intField> <officialField>")
$classOut = 0; $methodOut = 0; $fieldOut = 0
foreach ($k in $intToObfOwner.Keys) {
    $obf = $intToObfOwner[$k]
    if ($obfToMojmapClass.ContainsKey($obf)) {
        $sw.WriteLine("C`t$k`t$($obfToMojmapClass[$obf])")
        $classOut++
    }
}
foreach ($k in $memberIntToObf.Keys) {
    $parts = $k.Split('|')
    $ownerInt = $parts[0]; $kind = $parts[1]; $intMember = $parts[2]
    if (-not $intToObfOwner.ContainsKey($ownerInt)) { continue }
    $mk = "$($intToObfOwner[$ownerInt])|$kind|$($memberIntToObf[$k])"
    if (-not $memberObfToMoj.ContainsKey($mk)) { continue }
    $moj = $memberObfToMoj[$mk]
    if ($kind -eq 'm') { $sw.WriteLine("M`t$ownerInt`t$intMember`t$moj"); $methodOut++ }
    else { $sw.WriteLine("F`t$ownerInt`t$intMember`t$moj"); $fieldOut++ }
}
$sw.Dispose()
Write-Host ("[emit] $out")
Write-Host ("[emit] C={0:N0}  M={1:N0}  F={2:N0}" -f $classOut, $methodOut, $fieldOut)
Write-Host ("[emit] {0:N0} bytes" -f (Get-Item $out).Length)
