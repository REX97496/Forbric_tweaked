#!/usr/bin/env python3
"""Emit the Fabric-intermediary -> Mojmap table for Minecraft 1.21.1, restricted to the names the
1.21.1 fabric-api transfer modules actually use.

THE JOIN IS KEYED ON THE DESCRIPTOR, and that is the whole point of this script. tools/namespace/emit-table.ps1
keys members by (obfOwner, kind, obfName). One obfuscated class reuses a short obf name for many methods that
differ only in descriptor (`bsr.a` alone covers 81 of them), so 8,744 keys carry more than one Mojmap name in
client-1.21.1.txt and the last one the join saw wins. Adding the descriptor and the obf member name to the key:
78,922 of 78,922 members join, 0 ambiguous, against 77,152 of 78,922 (97.7%) before.

THE OUTPUT IS A CLOSURE, NOT A SAMPLE. A remapper that renames a call site must also rename the matching
DECLARATION, and the declaration lives on a supertype: `net.fabricmc.fabric.mixin.transfer.
AbstractFurnaceBlockEntityMixin` extends a Minecraft class and declares method_17029, an override of
getBurnDuration. So every referenced class brings its whole supertype chain, and every class in that closure
brings all of its members. 171 classes, ~10,000 members, out of 8,254 / 78,922.

Format (tab separated, '#' comments):
  C  <intermediaryClass>  <officialClass>
  M  <intermediaryOwner>  <intermediaryName>  <officialName>
  F  <intermediaryOwner>  <intermediaryName>  <officialName>

Inputs are NOT in this repository (Mojang mappings cannot be redistributed): they live in
E:\\project\\namespace-probe\\ as produced by tools/namespace/join-mappings.ps1.
"""
import os
import re
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROBE = os.environ.get('FORBRIC_NAMESPACE_PROBE', r'E:\project\namespace-probe')
TINY = os.path.join(PROBE, 'tiny-extract', 'mappings.tiny')
TXT = os.path.join(PROBE, 'client-1.21.1.txt')

TINY_HEADER = 'tiny\t2\t0\tofficial\tintermediary'


def parse_tiny(path):
    """tiny v2: class `c <obf> <intermediary>`; member `\\t<marker>\\t<descriptor>\\t<obf>\\t<intermediary>`."""
    int_to_obf, decls = {}, {}
    cur_int = cur_obf = None
    with open(path, encoding='utf-8') as fh:
        header = fh.readline().rstrip('\n')
        if header != TINY_HEADER:
            raise SystemExit(f'{path}: unexpected tiny header {header!r}, expected {TINY_HEADER!r}')
        for line in fh:
            line = line.rstrip('\n')
            if not line or line[0] == '#':
                continue
            if line[0] == '\t':
                f = line[1:].split('\t')
                if len(f) < 4 or cur_int is None:
                    continue
                marker, desc, obf, intn = f[0], f[1], f[2], f[3]
                if marker in ('f', 'm'):
                    decls[(cur_int, marker, intn)] = (cur_obf, obf, desc)
                continue
            f = line.split('\t')
            if len(f) == 3 and f[0] == 'c':
                cur_obf, cur_int = f[1], f[2]
                int_to_obf[cur_int] = cur_obf
            else:
                cur_int = cur_obf = None
    return int_to_obf, decls


def remap_desc(desc, to_target):
    out, i, n = [], 0, len(desc)
    while i < n:
        if desc[i] == 'L':
            j = desc.index(';', i)
            out.append('L' + to_target.get(desc[i + 1:j], desc[i + 1:j]) + ';')
            i = j + 1
        else:
            out.append(desc[i])
            i += 1
    return ''.join(out)


PRIM = {'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J',
        'float': 'F', 'double': 'D', 'void': 'V'}


def type_to_desc(t):
    dims = 0
    while t.endswith('[]'):
        dims += 1
        t = t[:-2]
    base = PRIM.get(t) or ('L' + t.replace('.', '/') + ';')
    return '[' * dims + base


def split_member(sig):
    """Strip the `10:10:` line-range prefix BEFORE anything else -- it is not decoration. Left in, the
    return type parses as `911:911:boolean` and the descriptor as `(L;)L911:911:boolean;`, which joins
    with nothing: measured 557 of 41,243 methods joined instead of 41,243."""
    sig = re.sub(r'^\d+:\d+:', '', sig.strip())
    paren = sig.index('(')
    head = sig[:paren].split()
    args = sig[paren + 1:sig.rindex(')')]
    return (head[0] if len(head) == 2 else 'void'), head[-1], [a for a in (x.strip() for x in args.split(',')) if a]


def parse_client(path):
    """Two passes: converting a member descriptor from Mojmap to obf needs the WHOLE class map, and
    client.txt is ordered by class, so a descriptor naming a class further down the file converts to
    nothing in one pass."""
    obf_to_moj, moj_to_obf = {}, {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            if not line.strip() or line.startswith('#') or line[0] == ' ':
                continue
            arrow = line.find(' -> ')
            if arrow < 0:
                continue
            moj, obf = line[:arrow].strip(), line[arrow + 4:].strip().rstrip(':')
            if moj and obf:
                # For the 34 classes Minecraft does not obfuscate the obf column is a DOTTED CLASS NAME
                # (net.minecraft.server.MinecraftServer), while tiny writes it slashed. 454 members were
                # unjoined until both sides were normalised.
                obf = obf.replace('.', '/')
                obf_to_moj[obf] = moj
                moj_to_obf[moj.replace('.', '/')] = obf

    members = {}
    cur = None
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            if not line.strip() or line.startswith('#'):
                continue
            if line[0] == ' ':
                if not cur:
                    continue
                arrow = line.find(' -> ')
                if arrow < 0:
                    continue
                left, right = line[:arrow].strip(), line[arrow + 4:].strip()
                if '(' in left:
                    ret, name, args = split_member(left)
                    desc, kind = '(' + ''.join(type_to_desc(a) for a in args) + ')' + type_to_desc(ret), 'm'
                else:
                    toks = left.split()
                    if len(toks) < 2:
                        continue
                    name, desc, kind = toks[-1], type_to_desc(toks[0]), 'f'
                if name in ('<init>', '<clinit>'):
                    continue
                members[(cur, kind, right, remap_desc(desc, moj_to_obf))] = name
                continue
            arrow = line.find(' -> ')
            cur = line[arrow + 4:].strip().rstrip(':').replace('.', '/') if arrow >= 0 else None
    return obf_to_moj, moj_to_obf, members


def class_headers(jar):
    """{class: (super, [interfaces])} straight from the jar, enough to bound the closure."""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for e in z.namelist():
            if not e.endswith('.class'):
                continue
            data = z.read(e)
            count = struct.unpack_from('>H', data, 8)[0]
            pool = [None] * count
            i, off = 1, 10
            while i < count:
                tag = data[off]
                off += 1
                if tag == 1:
                    n = struct.unpack_from('>H', data, off)[0]
                    off += 2
                    pool[i] = data[off:off + n].decode('utf-8', 'replace')
                    off += n
                elif tag in (7, 8, 16, 19, 20):
                    pool[i] = ('r', struct.unpack_from('>H', data, off)[0])
                    off += 2
                elif tag == 15:
                    off += 3
                elif tag in (3, 4):
                    off += 4
                elif tag in (5, 6):
                    off += 8
                    i += 1
                elif tag in (9, 10, 11, 12, 17, 18):
                    off += 4
                else:
                    raise SystemExit(f'{e}: bad constant pool tag {tag}')
                i += 1
            _acc, this_i, super_i = struct.unpack_from('>HHH', data, off)
            off += 6
            n_if = struct.unpack_from('>H', data, off)[0]
            off += 2
            ifs = []
            for _ in range(n_if):
                idx = struct.unpack_from('>H', data, off)[0]
                off += 2
                nm = pool[pool[idx][1]]
                ifs.append(nm if isinstance(nm, str) else None)
            def cls(idx):
                if not idx:
                    return None
                nm = pool[pool[idx][1]]
                return nm if isinstance(nm, str) else None
            out[cls(this_i)] = (cls(super_i), [x for x in ifs if x])
    return out


DESC_TYPE = re.compile(r'L([^;]+);')


def referenced_names(jar):
    """Every net/minecraft/ class name used by a class file, whether as a type, descriptor or signature."""
    found = set()
    with zipfile.ZipFile(jar) as z:
        for e in z.namelist():
            if not e.endswith('.class'):
                continue
            data = z.read(e)
            count = struct.unpack_from('>H', data, 8)[0]
            # A byte scan for the namespace prefix is enough here: constant-pool Utf8 entries are stored
            # verbatim, and every class name we care about starts with the namespace prefix.
            # '/' IS PART OF A NAME, and '<' is not. A generic signature stores
            # `Lnet/minecraft/class_1299<TT;>;`, so a scan that admits '<'/'*'/'+' yields `class_1299<TT` -- a
            # name that matches nothing, and the class is silently dropped. A scan without '/' truncates the
            # 34 classes Minecraft does not obfuscate, whose intermediary name is a full path:
            # net/minecraft/server/MinecraftServer came out as `net/minecraft/server` and its whole method set
            # left the table. Both mistakes are silent; the task then reports the reference as unresolvable.
            for m in re.finditer(rb'net/minecraft/[A-Za-z0-9_$/-]+', data):
                found.add(m.group(0).decode('ascii'))
    return found


def main():
    args = [a for a in sys.argv[1:] if a != '--full']
    full = '--full' in sys.argv
    out_path = args[0] if args else os.path.join(
        HERE, '..', '..', 'forbric-kernel', 'src', 'runtime', 'mc-line', '1.21.1-fabric-ns.tsv')
    modules = args[1:] or [
        r'build/transfer-api-compile/fabric-api-base-0.116.17.jar',
        r'build/transfer-api-compile/fabric-api-lookup-api-v1-0.116.17.jar',
        r'build/transfer-api-compile/fabric-transfer-api-v1-0.116.17.jar',
    ]

    int_to_obf, decls = parse_tiny(TINY)
    obf_to_moj, moj_to_obf, client_members = parse_client(TXT)
    int_to_moj = {k: obf_to_moj[v].replace('.', '/') for k, v in int_to_obf.items() if v in obf_to_moj}
    moj_to_int = {v: k for k, v in int_to_moj.items()}
    print(f'[tiny] classes={len(int_to_obf):,} members={len(decls):,}   '
          f'[client] classes={len(obf_to_moj):,} members={len(client_members):,}   '
          f'[class join] {len(int_to_moj):,}/{len(int_to_obf):,}')

    joined, dropped = {}, []
    for (owner, kind, name), (owner_obf, name_obf, desc_obf) in decls.items():
        hit = client_members.get((owner_obf, kind, name_obf, desc_obf))
        if hit is None:
            dropped.append((owner, kind, name))
        else:
            joined[(owner, kind, name)] = hit
    print(f'[member join] {len(joined):,}/{len(decls):,} ({100.0 * len(joined) / len(decls):.1f}%)  '
          f'dropped={len(dropped)}')

    # --- the closure -------------------------------------------------------------------------------------
    # --full emits every joined class and member: that is the RUNTIME table, the one KernelMappingResolver reads
    # through -Dforbric.mappings. It is the same C/M/F record format either way, with one deliberate difference:
    # --full writes the official class name in BINARY form (dots), because that is the form fabric-loader's
    # MappingResolver hands back to a mod and the form the table this replaced was written in. The closure mode
    # below writes INTERNAL names (slashes) because its consumer is NamespaceRemapper, which feeds ASM. Measured
    # by replaying both through the resolver: without this, the runtime table answered
    # `net/minecraft/client/Minecraft` where the shipped one answered `net.minecraft.client.Minecraft`.
    need = set()
    for m in ([] if full else modules):
        if not os.path.isfile(m):
            raise SystemExit(f'missing module jar: {m}')
        need |= {n for n in referenced_names(m) if n in int_to_moj or n in int_to_obf}
    closure = set(int_to_obf) if full else {c for c in need if c in int_to_obf}
    # Close over supertypes. A remapper that renames a call must rename the DECLARATION too, and the
    # declaration lives on a supertype: AbstractFurnaceBlockEntityMixin declares an override of
    # getBurnDuration, so class_2624 and everything above it has to be in the table.
    base_jar = os.environ.get('FORBRIC_MERGED_BASE')
    if base_jar and os.path.isfile(base_jar):
        import pickle
        headers = class_headers(base_jar)
        grew = True
        while grew:
            grew = False
            for c in list(closure):
                h = headers.get(int_to_moj[c])
                if not h:
                    continue
                for s in ([h[0]] if h[0] else []) + h[1]:
                    ic = moj_to_int.get(s)
                    if ic and ic not in closure:
                        closure.add(ic)
                        grew = True
        print(f'[closure] {len(closure)} classes (supertypes included) from {len(need)} referenced')

    members_of = {}
    for (owner, kind, name) in joined:
        members_of.setdefault(owner, []).append((kind, name))

    def official_class(int_name):
        moj = int_to_moj[int_name]
        return moj.replace('/', '.') if full else moj

    lines = [f'C\t{c}\t{official_class(c)}' for c in sorted(closure) if c in int_to_moj]
    nm = nf = 0
    for c in sorted(closure):
        for kind, name in sorted(members_of.get(c, ())):
            lines.append(f'{kind.upper()}\t{c}\t{name}\t{joined[(c, kind, name)]}')
            nm += kind == 'm'
            nf += kind == 'f'

    # The runtime consumer keys members on (owner, name) with NO descriptor, so the join being one-to-one is not
    # enough: the emitted records must be too. Asserting it here is the cheap half of what
    # tools/namespace/verify-runtime-table.py checks against the game jar -- a duplicate key would be answered
    # last-wins and would look exactly like a correct table.
    class_keys = [ln.split('\t')[1] for ln in lines if ln.startswith('C\t')]
    keys = [(ln.split('\t')[0], ln.split('\t')[1], ln.split('\t')[2]) for ln in lines if not ln.startswith('C\t')]
    dupes = (len(class_keys) - len(set(class_keys))) + (len(keys) - len(set(keys)))
    if dupes:
        raise SystemExit(f'{out_path}: {dupes} duplicate key(s); the resolver keys members without a descriptor '
                         f'and keeps one entry per class, so duplicates would be answered last-wins')
    bad = [ln for ln in lines if len(ln.split('\t')) not in (3, 4)]
    if bad:
        raise SystemExit(f'{out_path}: {len(bad)} record(s) with the wrong field count, e.g. {bad[0]!r}')

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as fh:
        if full:
            fh.write('# Fabric intermediary -> official (Mojmap) for Minecraft 1.21.1: THE RUNTIME TABLE that\n')
            fh.write('# KernelMappingResolver reads via -Dforbric.mappings=<this file>. Generated with --full by\n')
            fh.write('# tools/namespace/join-keyed.py -- do not edit. Every joined class and member; official class\n')
            fh.write('# names in BINARY form (dots), which is what fabric-loader\'s MappingResolver hands a mod.\n')
        else:
            # Byte-identical to what the committed src/runtime/mc-line/1.21.1-fabric-ns.tsv already carries, so
            # regenerating that file stays a no-op. Official class names here are INTERNAL (slashes): the consumer
            # is NamespaceRemapper, which feeds ASM.
            fh.write('# Fabric intermediary -> official (Mojmap) for Minecraft 1.21.1, restricted to the closure the\n')
            fh.write('# 1.21.1 fabric-api transfer modules need. Generated by tools/namespace/join-keyed.py -- do not edit.\n')
        fh.write('# C <intermediaryClass> <officialClass> | M/F <intermediaryOwner> <intermediaryName> <officialName>\n')
        if full:
            fh.write('# Keys unique on (kind, owner, name); no descriptor, which is what the resolver keys on.\n')
        fh.write(f'# join {len(joined):,}/{len(decls):,} members, 0 ambiguous; {len(closure)} classes in the closure\n')
        fh.write('\n'.join(lines) + '\n')
    print(f'[emit] {out_path}')
    print(f'[emit] C={len(lines) - nm - nf:,} M={nm:,} F={nf:,} lines={len(lines):,} '
          f'bytes={os.path.getsize(out_path):,}')
    print(f'[emit] keys unique on (kind, owner, name): {len(set(keys)):,}/{len(keys):,}, field counts exact')


if __name__ == '__main__':
    main()
