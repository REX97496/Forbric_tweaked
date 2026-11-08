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
#
# Every dictionary below is ORDINAL. A PowerShell @{} is case-insensitive, and the obfuscator gives one class a
# field `e` and methods `e`/`E`: measured, BuiltInRegistries dropped from BLOCK to LOOT_FUNCTION_TYPE because
# `lt|f|e` and `lt|f|E` were the same key, and the client then cast a LootItemFunctionType to a Block.

param(
    [string]$dir = 'E:\project\namespace-probe',
    [string]$out = 'E:\project\namespace-probe\mappings-1.21.1.tsv'
)

$ErrorActionPreference = 'Stop'
$tiny = Join-Path $dir 'tiny-extract\mappings.tiny'
$txt  = Join-Path $dir 'client-1.21.1.txt'

# client.txt prints erased signatures with official type names; tiny prints them with obf class names. Turning both
# into a JVM descriptor is what lets an overload keep its own name: Mojang reuses `c` for every overload, so the
# name alone cannot tell `fromCodecWithRegistriesTrusted` from the three others in the same class.
function ConvertTo-JvmType([string]$name) {
    # Generic arguments are printed on the type (`CompletableFuture<List<V>>`); the JVM descriptor keeps only the
    # erasure, and a leftover `<` used to make the whole row look unresolvable.
    while ($name -match '<[^<>]*>') { $name = $name -replace '<[^<>]*>', '' }
    $name = $name.Trim()
    switch ($name) {
        'void'    { return 'V' } 'boolean' { return 'Z' } 'byte' { return 'B' } 'char' { return 'C' }
        'short'   { return 'S' } 'int'     { return 'I' } 'long' { return 'J' } 'float' { return 'F' }
        'double'  { return 'D' }
    }
    if ($name.EndsWith('[]')) { return '[' + (ConvertTo-JvmType $name.Substring(0, $name.Length - 2)) }
    if ($name -match '^[A-Z]$' -or $name.Contains('<') -or $name -eq '?') { return $null }
    return 'L' + ($name -replace '\.', '/') + ';'
}
# --- tiny: intermediary class/member -> obf, with the field/method split ---------------------------------
$intToObfOwner = [hashtable]::new([System.StringComparer]::Ordinal)
$memberIntToObf = [hashtable]::new([System.StringComparer]::Ordinal)   # "intOwner|kind|intMember" -> obfMember
$memberIntDesc = [hashtable]::new([System.StringComparer]::Ordinal)  # same key -> descriptor in the obf namespace
$memberSigToMoj = [hashtable]::new([System.StringComparer]::Ordinal) # "obfOwner|kind|descriptor|obfMember" -> mojmap member
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
        $intDesc = $f[1]
        if ($obfMember -and $intWM) {
            $memberIntToObf["$curOwnerInt|$marker|$intWM"] = $obfMember
            $memberIntDesc["$curOwnerInt|$marker|$intWM"] = $intDesc
        }
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
$obfToMojmapClass = [hashtable]::new([System.StringComparer]::Ordinal)
$memberObfToMoj = [hashtable]::new([System.StringComparer]::Ordinal)
$memberObfPoisoned = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
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
        $kind = if ($left.Contains('(')) { 'm' } else { 'f' }
        $moj = $tokens[$tokens.Count - 1]
        $paren = $moj.IndexOf('(')
        if ($paren -ge 0) { $moj = $moj.Substring(0, $paren) }
        if (-not $moj -or $moj -eq '<init>' -or $moj -eq '<clinit>') { continue }
        # A method row always carries its argument list; the `line:column:` prefix is only printed for members
        # whose source spans were recorded, so keying the kind off it filed every unannotated method as a FIELD
        # (measured: `bnf|f|V|a` for ProfilerFiller.push, and then the name-only fallback renamed push to tee).
        # Erased descriptor, so an overload can be joined to the one intermediary name that owns its shape.
        $sig = $null
        if ($kind -eq 'm') {
            # The row starts with `line:column:`, and that prefix glues itself to the return type when the row is split.
            $sigLeft = $left -replace '^\d+:\d+:', ''
            $lp = $sigLeft.IndexOf('(')
            if ($lp -gt 0 -and $sigLeft.EndsWith(')')) {
                $ret = (($sigLeft.Substring(0, $lp)).Trim() -split '\s+')[0]
                $argText = $sigLeft.Substring($lp + 1, $sigLeft.Length - $lp - 2).Trim()
                $args = @()
                if ($argText) { $args = $argText -split ',' | ForEach-Object { $_.Trim() } }
                $retD = ConvertTo-JvmType $ret
                $argD = @(); $ok = [bool]$retD
                foreach ($a in $args) { $d = ConvertTo-JvmType $a; if (-not $d) { $ok = $false; break }; $argD += $d }
                if ($ok) { $sig = '(' + ($argD -join '') + ')' + $retD }
            }
        } else {
            $sig = ConvertTo-JvmType (($left -split '\s+')[0])
        }
        if ($sig) {
            $sk = "$curObf|$kind|$sig|$right"
            if (-not $memberSigToMoj.ContainsKey($sk)) { $memberSigToMoj[$sk] = $moj }
        }
        # One obf name can carry several official names (Mojang reuses `c` for every overload). FIRST wins,
        # and rows read from a later class block are not allowed to steal the key: client.txt carries a
        # `net.minecraft.nbt.NbtAccounter lambda$fromCodec$4() -> c` row inside the ByteBufCodecs block,
        # which used to rename four ByteBufCodecs methods to a name their owner never declares. Guessing
        # per overload is left to the runtime, which can ask the base by descriptor.
        $mk = "$curObf|$kind|$right"
        if (-not $memberObfToMoj.ContainsKey($mk)) { $memberObfToMoj[$mk] = $moj }
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
    $obfOwner = $intToObfOwner[$ownerInt]
    $obfMember = $memberIntToObf[$k]
    $moj = $null
    $desc = $memberIntDesc[$k]
    if ($desc) {
        $resolved = $desc
        $missing = $false
        foreach ($m in [regex]::Matches($desc, 'L([^;]+);')) {
            $name = $m.Groups[1].Value
            if ($obfToMojmapClass.ContainsKey($name)) { $resolved = $resolved.Replace('L' + $name + ';', 'L' + ($obfToMojmapClass[$name] -replace '\.', '/') + ';'); continue }
            # `java/util/List` is printed with its separators already, and only a short lowercase token is an obf name.
            if ($name -match '^[a-z]{1,4}\d*(\$[a-z0-9]+)*$') { $missing = $true; break }
        }
        if (-not $missing) {
            $sk = "$obfOwner|$kind|$resolved|$obfMember"
            if ($memberSigToMoj.ContainsKey($sk)) { $moj = $memberSigToMoj[$sk] }
        }
    }
    if (-not $moj) {
        # Name-only fallback survives for FIELDS (an obf field name is unique per owner). For METHODS it is
        # exactly the bug that renamed ProfilerFiller.push to tee: one obf letter carries every overload, so the
        # first row to claim `bnf|m|a` wins and the rest inherit a name their descriptor never had. Leave the
        # method unmapped instead; GuestJarRemap answers it from the base by descriptor.
        if ($kind -eq 'm') { continue }
        $mk = "$obfOwner|$kind|$obfMember"
        if (-not $memberObfToMoj.ContainsKey($mk)) { continue }
        $moj = $memberObfToMoj[$mk]
    }
    if ($kind -eq 'm') { $sw.WriteLine("M`t$ownerInt`t$intMember`t$moj"); $methodOut++ }
    else { $sw.WriteLine("F`t$ownerInt`t$intMember`t$moj"); $fieldOut++ }
}
$sw.Dispose()
Write-Host ("[emit] $out")
Write-Host ("[emit] C={0:N0}  M={1:N0}  F={2:N0}" -f $classOut, $methodOut, $fieldOut)
Write-Host ("[emit] {0:N0} bytes" -f (Get-Item $out).Length)
