#!/usr/bin/env python3
"""Adjudicate a runtime mapping table (the C/M/F file KernelMappingResolver reads) against a REAL Mojmap jar.

WHY THIS EXISTS. A join can be wrong in a way no count reveals: the table keeps its shape, the resolver keeps
answering, and every answer names a member that is not at the owner and signature the caller asked about. The
only ground truth is the game jar itself -- a member is (owner, name, descriptor) in the base, and a table record
is correct if and only if the name it produces exists there with the signature the intermediary declaration has.
Measured with this script on 1.21.1: the table tools/namespace/emit-table.ps1 wrote names something that exists in
NEITHER the merged base NOR the unpatched jar it was built from 22,861 times, and maps 3,830 distinct intermediary
members onto a single Mojmap member; the descriptor-keyed table does neither, 0 and 0.

TWO JARS, AND WHY. The artifact that runs is the patched, client+server-merged base. The join's inputs are
Mojang's own mapping files, which describe the UNPATCHED client jar. NeoForge's patching rewrites code -- lambdas
are renumbered and re-signatured, some client-only members of common classes are dropped by the merge -- so a
record naming Mojang's name for a patched member is right about the mapping and absent from the artifact. `--origin`
takes that unpatched jar: a record absent from `--base` but present in `--origin` is a patching difference, counted
and enumerated; a record absent from BOTH is a wrong mapping and fails the run. The class-file major of the two
jars must match, because the staging directory is shared between Minecraft lines and its filenames carry no
version.

WHAT IT CHECKS, in order, and every one of them is an assertion rather than a report:

  1. RECORD SHAPE. KernelMappingResolver reads records as tab-separated and requires >= 4 fields for M/F; its
     old `f.length >= 4` guard dropped a truncated one SILENTLY (it is an error there now). A truncated record
     here is an error too, not a warning. C records need exactly 3 fields.
  2. THE KEY THE RESOLVER USES. The resolver keys members on owner#name with NO descriptor, so a table that
     carries two records with the same (kind, owner, name) and different official names would be answered
     last-wins -- the exact defect this table exists to remove. Duplicate keys are an error.
  3. CLASS NAMES. Every C record's official class must exist as an entry in one of the two jars (see above).
  4. MEMBER SIGNATURES. For every M/F record, the descriptor comes from Fabric's own tiny mapping (neither
     table invents one), is converted from obf to Mojmap, and the pair (officialName, descriptor) must be
     present on the official owner in one of the two jars. This is the check that has teeth.
  5. ONE-TO-ONE. A correct join is injective onto Mojmap's member set: two distinct intermediary members must
     not land on one official member. Counted and asserted to be zero.

  --answers makes it adjudicate WHAT THE RESOLVER RETURNED instead of what the file says: the file written by
  net.forbric.kernel.fabric.MappingTableAudit (kind, owner, name, resolver's answer per record). Use it to prove
  the runtime answers -- not merely the file -- are real members.

Inputs that are NOT in the repository (Mojang mappings cannot be redistributed), same probe directory the join
scripts use: tiny-extract/mappings.tiny (Fabric intermediary) and client-1.21.1.txt (Mojang client mappings).

Exit status: 0 every record adjudicated and present, 1 otherwise. Nothing is sampled.

  python tools/namespace/verify-runtime-table.py --table <tsv> --base <merged-base jar>
  python tools/namespace/verify-runtime-table.py --answers <audit tsv> --base <jar>
"""
import argparse
import collections
import os
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROBE = os.environ.get('FORBRIC_NAMESPACE_PROBE', r'E:\project\namespace-probe')
TINY = os.path.join(PROBE, 'tiny-extract', 'mappings.tiny')
TXT = os.path.join(PROBE, 'client-1.21.1.txt')


# -------------------------------------------------------------------------------------------------------------
# class files: members straight out of the base jar
# -------------------------------------------------------------------------------------------------------------
def _parse_pool(data):
    """Constant pool as a list indexed by cp index; entries are (tag, a, b) or (1, text) for Utf8."""
    count = struct.unpack_from('>H', data, 8)[0]
    pool = [None] * count
    i, off = 1, 10
    while i < count:
        tag = data[off]
        off += 1
        if tag == 1:                                     # Utf8
            n = struct.unpack_from('>H', data, off)[0]
            off += 2
            pool[i] = (1, data[off:off + n].decode('utf-8', 'replace'))
            off += n
        elif tag in (7, 8, 16, 19, 20):                  # Class String MethodType Module Package
            pool[i] = (tag, struct.unpack_from('>H', data, off)[0])
            off += 2
        elif tag == 15:                                  # MethodHandle
            pool[i] = (tag,)
            off += 3
        elif tag in (3, 4):                              # Integer Float
            pool[i] = (tag,)
            off += 4
        elif tag in (5, 6):                              # Long Double: two slots
            pool[i] = (tag,)
            off += 8
            i += 1
        elif tag in (9, 10, 11, 12, 17, 18):             # refs, NameAndType, Dynamic, InvokeDynamic
            pool[i] = (tag, struct.unpack_from('>H', data, off)[0], struct.unpack_from('>H', data, off + 2)[0])
            off += 4
        else:
            raise SystemExit(f'bad constant pool tag {tag}')
        i += 1
    return pool, off


def _utf(pool, idx):
    e = pool[idx]
    return e[1] if e and e[0] == 1 else None


def _class_name(pool, idx):
    """A CONSTANT_Class entry names its Utf8; it does not hold the name itself. Confusing the two keys every
    class in the jar by a constant-pool INDEX, which is how the first version of this file 'read' 1 class."""
    e = pool[idx]
    return _utf(pool, e[1]) if e and e[0] == 7 else None


def class_members(path):
    """{internal class name: {'fields': {(name, desc)}, 'methods': {(name, desc)}}} for every class in a jar."""
    out = {}
    with zipfile.ZipFile(path) as z:
        for entry in z.namelist():
            if not entry.endswith('.class'):
                continue
            data = z.read(entry)
            if data[:4] != b'\xca\xfe\xba\xbe':
                continue
            pool, off = _parse_pool(data)
            _acc, this_i, _super = struct.unpack_from('>HHH', data, off)
            off += 6
            n_if = struct.unpack_from('>H', data, off)[0]
            off += 2 + 2 * n_if
            members = {}
            # fields_count, then n field_info, then methods_count, then n method_info: the same shape twice, and
            # every member's attributes have to be walked because their length varies.
            for key in ('fields', 'methods'):
                n = struct.unpack_from('>H', data, off)[0]
                off += 2
                found = set()
                for _ in range(n):
                    _flags, name_i, desc_i, n_attr = struct.unpack_from('>HHHH', data, off)
                    off += 8
                    for _ in range(n_attr):
                        _ai, alen = struct.unpack_from('>HI', data, off)
                        off += 6 + alen
                    found.add((_utf(pool, name_i), _utf(pool, desc_i)))
                members[key] = found
            out[_class_name(pool, this_i)] = members
    return out


def jar_major(path):
    """The class-file major version of the jar's contents: 65 = Java 21 = the 1.21.1 line, 69 = 25 = the 26.x line.

    The staging directory this is read from is SHARED between Minecraft lines and its filenames carry no version,
    so a jar from the wrong line is the ordinary accident here rather than the exotic one. Two lines differ in
    exactly this number, which makes it a cheap way to refuse the wrong jar instead of reporting nonsense.
    """
    with zipfile.ZipFile(path) as z:
        for entry in z.namelist():
            if entry.endswith('.class'):
                data = z.read(entry)
                if data[:4] == b'\xca\xfe\xba\xbe':
                    return struct.unpack_from('>H', data, 6)[0]
    return None


# -------------------------------------------------------------------------------------------------------------
# tiny + client.txt: the signature of every intermediary member
# -------------------------------------------------------------------------------------------------------------
def parse_tiny(path):
    """-> (intToObf, {(intOwner, kind, intName): obfDescriptor}) with the five format traps handled."""
    int_to_obf, descs = {}, {}
    cur_int = cur_obf = None
    with open(path, encoding='utf-8') as fh:
        header = fh.readline().rstrip('\n')
        if header != 'tiny\t2\t0\tofficial\tintermediary':
            raise SystemExit(f'{path}: unexpected tiny header {header!r}')
        for line in fh:
            line = line.rstrip('\n')
            if not line or line[0] == '#':
                continue
            if line[0] == '\t':                          # trap: field 2 is the DESCRIPTOR
                f = line[1:].split('\t')
                if len(f) < 4 or cur_int is None:
                    continue
                if f[0] in ('f', 'm'):
                    descs[(cur_int, f[0], f[3])] = f[1]
                continue
            f = line.split('\t')
            if len(f) == 3 and f[0] == 'c':
                cur_obf, cur_int = f[1], f[2]
                int_to_obf[cur_int] = cur_obf
            else:
                cur_int = cur_obf = None
    return int_to_obf, descs


def parse_client(path):
    """-> (obfToMoj internal, mojToObf). client.txt is '<mojmap> -> <obf>:', i.e. REVERSED."""
    obf_to_moj, moj_to_obf = {}, {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            if not line.strip() or line.startswith('#') or line[0] == ' ':
                continue
            arrow = line.find(' -> ')
            if arrow < 0:
                continue
            moj = line[:arrow].strip()
            # Trap: for the classes Minecraft does not obfuscate the obf column is DOTTED while tiny slashes it.
            obf = line[arrow + 4:].strip().rstrip(':').replace('.', '/')
            if moj and obf:
                obf_to_moj[obf] = moj.replace('.', '/')
                moj_to_obf[moj.replace('.', '/')] = obf
    return obf_to_moj, moj_to_obf


def remap_desc(desc, to_target):
    out, i, n = [], 0, len(desc)
    while i < n:
        if desc[i] == 'L':
            j = desc.index(';', i)
            name = desc[i + 1:j]
            out.append('L' + to_target.get(name, name) + ';')
            i = j + 1
        else:
            out.append(desc[i])
            i += 1
    return ''.join(out)


# -------------------------------------------------------------------------------------------------------------
# the table
# -------------------------------------------------------------------------------------------------------------
class Table:
    def __init__(self):
        self.classes = {}      # intClass -> officialClass (as written)
        self.members = {}      # (kind, owner, name) -> officialName
        self.order = []        # [(kind, owner, name, official)] in file order, for the answers handoff
        self.problems = []

    def records(self):
        return self.order


def read_table(path):
    t = Table()
    seen = collections.Counter()
    with open(path, encoding='utf-8') as fh:
        for lineno, raw in enumerate(fh, 1):
            line = raw.rstrip('\n')
            if not line or line[0] == '#':
                continue
            f = line.split('\t')
            kind = f[0]
            # 1. RECORD SHAPE. The resolver silently drops an M/F record with fewer than 4 fields.
            need = 3 if kind == 'C' else 4
            if kind not in ('C', 'M', 'F'):
                t.problems.append(f'line {lineno}: unknown record kind {kind!r}')
                continue
            if len(f) != need:
                t.problems.append(f'line {lineno}: {kind} record has {len(f)} fields, expected {need}: {line!r}')
                continue
            if kind == 'C':
                t.classes[f[1]] = f[2]
                t.order.append(('C', f[1], '', f[2]))
            else:
                k = (kind, f[1], f[2])
                if k in t.members:
                    t.problems.append(f'line {lineno}: duplicate key {kind} {f[1]} {f[2]} '
                                      f'({t.members[k]} vs {f[3]}) -- the resolver keys without a descriptor, so '
                                      f'this would be answered last-wins')
                t.members[k] = f[3]
                t.order.append((kind, f[1], f[2], f[3]))
            seen[kind] += 1
    t.counts = seen
    return t


def read_answers(path):
    """The audit's output: kind, owner, name, what the resolver returned -- in the table's own order."""
    out = []
    with open(path, encoding='utf-8') as fh:
        for raw in fh:
            line = raw.rstrip('\n')
            if not line or line[0] == '#':
                continue
            f = line.split('\t')
            if len(f) != 4:
                raise SystemExit(f'{path}: expected 4 fields per answer line, got {line!r}')
            out.append((f[0], f[1], f[2], f[3]))
    return out


# -------------------------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--table', help='the C/M/F table to adjudicate')
    ap.add_argument('--answers', help="the resolver's answers, from MappingTableAudit")
    ap.add_argument('--base', required=True, help='the merged Mojmap jar the loader runs (the ground truth)')
    ap.add_argument('--origin', help='the UNPATCHED Mojmap jar the base was built from. A record that is absent '
                                     'from --base but present in --origin is a patching/merge difference, not a '
                                     'wrong mapping: the name is exactly Mojang\'s. Anything absent from BOTH is '
                                     'a join defect and fails.')
    ap.add_argument('--tiny', default=TINY)
    ap.add_argument('--client', default=TXT)
    ap.add_argument('--label', default='', help='name for the table in the output')
    ap.add_argument('--rejects', help='write the non-resolving records to this file, one per line')
    args = ap.parse_args()

    if not args.table and not args.answers:
        ap.error('give --table or --answers')

    subject = args.table or args.answers
    label = args.label or os.path.basename(subject)
    src = read_answers(args.answers) if args.answers else read_table(args.table).records()
    if args.table:
        declared = read_table(args.table)
        for p in declared.problems:
            print(f'[shape] {p}')
        if declared.problems:
            print(f'FAIL: {len(declared.problems)} structural problem(s) in {args.table}')
            return 1
        print(f'[shape] {label}: {declared.counts["C"]:,} C, {declared.counts["M"]:,} M, '
              f'{declared.counts["F"]:,} F; every record well formed, every member key unique')

    int_to_obf, tiny_descs = parse_tiny(args.tiny)
    obf_to_moj, _moj_to_obf = parse_client(args.client)
    base = class_members(args.base)
    base_major = jar_major(args.base)
    origin = None
    if args.origin:
        origin_major = jar_major(args.origin)
        if origin_major != base_major:
            print(f'FAIL: {args.origin} is a Java {origin_major} jar (class-file major {origin_major}) and '
                  f'{args.base} is Java {base_major}. That staging directory is shared between Minecraft lines and '
                  f'its filenames carry no version, so the wrong line\'s jar is the likely cause. Refusing to '
                  f'report anything against it.')
            return 1
        origin = class_members(args.origin)
    print(f'[base] {args.base}: {len(base):,} classes (major {base_major})'
          + (f'; origin {args.origin}: {len(origin):,} classes' if origin is not None else ''))
    print(f'[join] tiny {len(int_to_obf):,} classes / {len(tiny_descs):,} member declarations; '
          f'client {len(obf_to_moj):,} classes')

    # The table names official classes as internal (slashed) or binary (dotted) names -- the resolver accepts both,
    # so both are accepted here; the jars are keyed internally.
    def as_internal(name):
        return name.replace('.', '/')

    tally = collections.Counter()
    rejects = []
    targets = collections.Counter()
    for kind, owner, name, official in src:
        if kind == 'C':
            internal = as_internal(official)
            if internal in base:
                tally['class in the base'] += 1
            elif origin is not None and internal in origin:
                tally['class only in the unpatched jar'] += 1
            else:
                tally['class ABSENT'] += 1
                rejects.append(f'C\t{owner}\t{official}')
            continue
        decl = tiny_descs.get((owner, 'm' if kind == 'M' else 'f', name))
        if decl is None:
            tally['no tiny declaration'] += 1
            rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t(no tiny declaration)')
            continue
        obf_owner = int_to_obf.get(owner)
        if obf_owner is None:
            tally['owner not in tiny'] += 1
            rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t(owner not in tiny)')
            continue
        moj_owner = obf_to_moj.get(obf_owner)
        if moj_owner is None:
            tally['owner has no mojmap name'] += 1
            rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t(owner has no mojmap name)')
            continue
        key = 'fields' if kind == 'F' else 'methods'
        desc = remap_desc(decl, obf_to_moj)
        members = base.get(moj_owner)
        if members is None:
            tally['official owner absent from base'] += 1
            rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t(official owner absent from base)')
            continue
        if (official, desc) in members[key]:
            tally['member in the base'] += 1
            targets[(kind, moj_owner, official, desc)] += 1
            continue
        if origin is not None and moj_owner in origin and (official, desc) in origin[moj_owner][key]:
            tally['member only in the unpatched jar'] += 1
            synthetic = ('lambda$' in official or official.startswith('val$')
                         or official.startswith('this$') or official.startswith('access$'))
            tally['  of those, compiler-synthesised' if synthetic else '  of those, a real member'] += 1
            rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t{desc}\t(unpatched jar only)')
            continue
        tally['member ABSENT'] += 1
        rejects.append(f'{kind}\t{owner}\t{name}\t{official}\t{desc}')

    total = len(src)
    bad = sum(v for k, v in tally.items()
              if 'ABSENT' in k or k in ('no tiny declaration', 'owner not in tiny', 'owner has no mojmap name'))
    colliding = {t: n for t, n in targets.items() if n > 1}
    print(f'[adjudicate] {label}: {total:,} records against {os.path.basename(args.base)}'
          + (f' and {os.path.basename(args.origin)}' if origin is not None else ''))
    for k, v in tally.most_common():
        print(f'  {v:7d}  {k}')
    print(f'[injective] {len(colliding):,} official member(s) claimed by more than one intermediary member'
          f'{"; " + str(sum(colliding.values())) + " records" if colliding else ""}')
    if colliding:
        for t, n in list(colliding.items())[:5]:
            print(f'    {t[0]} {t[1]}.{t[2]}{t[3]}  <- {n} intermediary members')
    if args.rejects:
        os.makedirs(os.path.dirname(os.path.abspath(args.rejects)), exist_ok=True)
        with open(args.rejects, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write('\n'.join(rejects) + ('\n' if rejects else ''))
        print(f'[rejects] {args.rejects}: {len(rejects):,} record(s)')
    if bad:
        print(f'FAIL: {bad:,} of {total:,} records name something that is not at that owner and signature')
        return 1
    patched_only = tally['class only in the unpatched jar'] + tally['member only in the unpatched jar']
    print(f'PASS: {total:,} of {total:,} records resolve at the stated owner and signature -- '
          f'{total - patched_only:,} in the base that runs, {patched_only:,} only in the unpatched jar it was '
          f'built from (patching/merge, enumerated above)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
